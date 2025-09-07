package org.samsung.aipp.aippintellij.debugAssist;

import com.google.gson.Gson;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.*;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.samsung.aipp.aippintellij.util.Constants;

import javax.swing.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class DebugDataCollector {

    private static final Logger logger = Logger.getInstance(DebugDataCollector.class);

    private static final DebugDataCollector instance = new DebugDataCollector();

    private final List<SnapshotItem> latestSnapshot = new ArrayList<>();
    private final List<StackItem> latestStack = new ArrayList<>();
    private ExceptionDetail latestException = null;
    private static Boolean cachedIsPyCharm = null; // Cache PyCharm detection

    private DebugDataCollector() {
        logger.debug(isPyCharmEnvironment()
                ? "[DebugDataCollector] PyCharm debugger environment detected."
                : "[DebugDataCollector] Running IntelliJ debugger environment.");
    }

    public static DebugDataCollector getInstance() {
        return instance;
    }

    public List<SnapshotItem> getSnapshot() {
        return new ArrayList<>(latestSnapshot);
    }

    public List<StackItem> getCallStack() {
        return new ArrayList<>(latestStack);
    }

    public ExceptionDetail getExceptionDetail() {
        return latestException;
    }

    public void clearDebugData() {
        latestSnapshot.clear();
        latestStack.clear();
        latestException = null;
    }

    private static boolean isPyCharmEnvironment() {
        if (cachedIsPyCharm != null) return cachedIsPyCharm;
        try {
            Class.forName("com.jetbrains.python.debugger.PyDebugValue");
            cachedIsPyCharm = true;
        } catch (Throwable t) {
            cachedIsPyCharm = false;
        }
        logger.debug("isPyCharmEnvironment detected: " + cachedIsPyCharm);
        return cachedIsPyCharm;
    }

    private static boolean isPyDebugValue(Object o) {
        if (o == null) return false;
        try {
            Class<?> cls = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
            return cls.isInstance(o);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void collectStackItems(XDebugProcess debugProcess, Consumer<ContextItem> callback) {
        List<StackItem> stackItems = new ArrayList<>();
        XExecutionStack stack = null;
        try {
            stack = debugProcess.getSession().getSuspendContext().getActiveExecutionStack();
        } catch (Throwable t) {
            logger.warn("Unable to obtain execution stack: " + t.getMessage());
        }
        if (stack == null) {
            callback.accept(new ContextItem(stackItems, false, ContextItem.Type.STACK));
            return;
        }
        stack.computeStackFrames(0, new XExecutionStack.XStackFrameContainer() {
            @Override
            public void addStackFrames(@NotNull List<? extends XStackFrame> frames, boolean last) {
                for (XStackFrame frame : frames) {
                    if (stackItems.size() >= Constants.MAX_CALLSTACK_ITEMS) break;
                    XSourcePosition pos = frame.getSourcePosition();
                    if (pos != null) {
                        String file = pos.getFile().getPath();
                        int line = pos.getLine() + 1;
                        String language = pos.getFile().getExtension();
                        String functionText = extractEnclosingFunctionSafe(debugProcess, pos);
                        stackItems.add(new StackItem(file, line, functionText != null ? functionText : "", language));
                    }
                }
                trimToJsonSize(stackItems, Constants.MAX_CALLSTACK_JSON_SIZE_BYTES);
                instance.latestStack.clear();
                instance.latestStack.addAll(stackItems);
                callback.accept(new ContextItem(stackItems, true, ContextItem.Type.STACK));
            }
            @Override public void errorOccurred(@NotNull String errorMessage) {
                callback.accept(new ContextItem(stackItems, false, ContextItem.Type.STACK));
            }
        });
    }

    private static void trimToJsonSize(List<?> items, int maxBytes) {
        Gson gson = new Gson();
        while (!items.isEmpty() && gson.toJson(items).length() > maxBytes) items.remove(items.size() - 1);
    }

    @Nullable
    private static String extractEnclosingFunctionSafe(XDebugProcess debugProcess, XSourcePosition pos) {
        try {
            Project project = debugProcess.getSession().getProject();
            VirtualFile file = pos.getFile();
            int line = pos.getLine();
            return extractEnclosingFunctionReflective(project, file, line);
        } catch (Throwable t) {
            logger.debug("extractEnclosingFunctionSafe failed: " + t.getMessage());
            return null;
        }
    }

    public static void collectSnapshot(XStackFrame currentStackFrame, Consumer<ContextItem> callback) {
        List<MutableSnapshotItem> snapshotItems = new ArrayList<>();
        AtomicInteger debuggerCalls = new AtomicInteger(0);
        AtomicBoolean limitReached = new AtomicBoolean(false);

        try {
            currentStackFrame.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                    AtomicInteger pending = new AtomicInteger(children.size());
                    if (children.size() == 0) complete();
                    for (int i = 0; i < children.size(); i++) {
                        if (limitReached.get() || debuggerCalls.get() >= Constants.MAX_CALLS_TO_DEBUGGER
                                || new Gson().toJson(snapshotItems).length() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES) {
                            limitReached.set(true);
                            complete();
                            return;
                        }
                        String varName = children.getName(i);
                        XValue childValue = children.getValue(i);
                        MutableSnapshotItem mutableItem = new MutableSnapshotItem(varName, "unknown", "unavailable", "Local");
                        snapshotItems.add(mutableItem);
                        debuggerCalls.incrementAndGet();

                        if (isPyCharmEnvironment()) {
                            logger.debug("Trying PyCharm-specific collection for variable: " + varName);
                            collectPyCharmValueAndChildren(currentStackFrame, childValue, mutableItem, 0, () -> {
                                if (pending.decrementAndGet() == 0) complete();
                            });
                        } else {
                            collectValueAndChildren(childValue, mutableItem, 0, () -> {
                                if (pending.decrementAndGet() == 0) complete();
                            });
                        }
                    }
                }

                private void complete() {
                    List<SnapshotItem> result = new ArrayList<>();
                    for (MutableSnapshotItem item : snapshotItems) {
                        result.add(item.toSnapshotItem());
                    }
                    instance.latestSnapshot.clear();
                    instance.latestSnapshot.addAll(result);
                    callback.accept(new ContextItem(result, true, ContextItem.Type.SNAPSHOT));
                }

                @Override public void tooManyChildren(int remaining) {}
                @Override public void setAlreadySorted(boolean alreadySorted) {}
                @Override public void setErrorMessage(@NotNull String errorMessage) {}
                @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) {}
                @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
            });
        } catch (Throwable t) {
            logger.warn("collectSnapshot outer error: " + t.getMessage());
            List<SnapshotItem> result = new ArrayList<>();
            for (MutableSnapshotItem item : snapshotItems) result.add(item.toSnapshotItem());
            instance.latestSnapshot.clear();
            instance.latestSnapshot.addAll(result);
            callback.accept(new ContextItem(result, false, ContextItem.Type.SNAPSHOT));
        }
    }

    /**
     * Robust PyCharm collection:
     * - Prefer presentation (what the UI shows)
     * - Then try PyDebugValue reflection for metadata/children
     * - Then computeChildren fallback (XValue native)
     * - Finally, as last resort, evaluate repr(var) in frame (async)
     *
     * Guarantees: onComplete.run() will be invoked once.
     */
    private static void collectPyCharmValueAndChildren(XStackFrame frame, XValue value, MutableSnapshotItem parent, int currentDepth, Runnable onComplete) {
        if (currentDepth >= Constants.MAX_DEPTH_OF_NESTED_VARIABLES) { onComplete.run(); return; }

        final AtomicBoolean finished = new AtomicBoolean(false);
        final Runnable finishOnce = () -> { if (finished.compareAndSet(false, true)) onComplete.run(); };

        try {
            // Always try to read presentation first (most reliable)
            value.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    try {
                        // 1) Presentation (what the IDE shows)
                        String pres = renderPresentationText(presentation);
                        logger.debug("collectPyCharm: presentation for '" + parent.name + "' => '" + pres + "', type=" + presentation.getType() + ", hasChildren=" + hasChildren);
                        if (pres != null && !pres.isEmpty() && !"Collecting data...".equals(pres)) {
                            parent.value = pres;
                        }
                        if (presentation.getType() != null) parent.type = presentation.getType();
                    } catch (Throwable t) {
                        logger.debug("collectPyCharm: presentation parsing failed: " + t.getMessage());
                    }

                    // 2) Try reflection to get descriptor and children (PyCharm-specific)
                    try {
                        Object pyDebugValue = tryGetPyDebugValue(value);
                        if (pyDebugValue != null) {
                            logger.debug("collectPyCharm: found pyDebugValue descriptor for '" + parent.name + "': " + pyDebugValue.getClass().getName());
                            String reflName = tryGetPyName(pyDebugValue);
                            String reflVal  = tryGetPyValue(pyDebugValue);
                            String reflType = tryGetPyType(pyDebugValue);
                            if (reflName != null && !reflName.isEmpty()) parent.name = reflName;
                            if (reflVal != null && !reflVal.isEmpty() && !"unavailable".equals(reflVal)) parent.value = reflVal;
                            if (reflType != null) parent.type = reflType;

                            List<Object> pyChildren = tryGetPyChildren(pyDebugValue);
                            if (pyChildren != null && !pyChildren.isEmpty()) {
                                logger.debug("collectPyCharm: descriptor provided " + pyChildren.size() + " children for '" + parent.name + "'");
                                AtomicInteger pending = new AtomicInteger(pyChildren.size());
                                for (Object childObj : pyChildren) {
                                    try {
                                        // If it's an XValue already -> recurse properly
                                        if (childObj instanceof XValue) {
                                            XValue childX = (XValue) childObj;
                                            // Name unknown until presentation/reflection resolves it; create placeholder
                                            MutableSnapshotItem childItem = new MutableSnapshotItem("unknown", "unknown", "unavailable", "Field");
                                            parent.children.add(childItem);
                                            collectPyCharmValueAndChildren(frame, childX, childItem, currentDepth + 1, () -> {
                                                if (pending.decrementAndGet() == 0) finishOnce.run();
                                            });
                                        } else {
                                            // Descriptor-only child (not XValue): best-effort leaf using reflection
                                            String cName = tryGetPyName(childObj);
                                            String cType = tryGetPyType(childObj);
                                            String cVal  = tryGetPyValue(childObj);
                                            MutableSnapshotItem childItem = new MutableSnapshotItem(
                                                    cName != null ? cName : "unknown",
                                                    cType != null ? cType : "unknown",
                                                    cVal  != null ? cVal  : "unavailable",
                                                    "Field"
                                            );
                                            parent.children.add(childItem);
                                            if (pending.decrementAndGet() == 0) finishOnce.run();
                                        }
                                    } catch (Throwable inner) {
                                        // ensure pending decremented even on error
                                        if (pending.decrementAndGet() == 0) finishOnce.run();
                                    }
                                }
                                return; // descriptor children handled (or scheduled) — exit
                            } else {
                                logger.debug("collectPyCharm: descriptor returned no usable children for '" + parent.name + "'");
                            }
                        } else {
                            logger.debug("collectPyCharm: no pyDebugValue descriptor for '" + parent.name + "'");
                        }
                    } catch (Throwable reflErr) {
                        logger.debug("collectPyCharm: reflection attempt failed: " + reflErr.getMessage());
                    }

                    // 3) Fallback to XValue.computeChildren (the most reliable fallback for nested XValue)
                    try {
                        if (hasChildren) {
                            value.computeChildren(new XCompositeNode() {
                                @Override
                                public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                                    if (children.size() == 0) { finishOnce.run(); return; }
                                    AtomicInteger pending = new AtomicInteger(children.size());
                                    for (int i = 0; i < children.size(); i++) {
                                        String childName = children.getName(i);
                                        XValue childVal = children.getValue(i);
                                        MutableSnapshotItem childItem = new MutableSnapshotItem(childName != null ? childName : "unknown", "unknown", "unavailable", "Field");
                                        parent.children.add(childItem);
                                        collectPyCharmValueAndChildren(frame, childVal, childItem, currentDepth + 1, () -> {
                                            if (pending.decrementAndGet() == 0) finishOnce.run();
                                        });
                                    }
                                }
                                @Override public void tooManyChildren(int remaining) { finishOnce.run(); }
                                @Override public void setAlreadySorted(boolean alreadySorted) {}
                                @Override public void setErrorMessage(@NotNull String errorMessage) { finishOnce.run(); }
                                @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { finishOnce.run(); }
                                @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
                            });
                            return; // computeChildren scheduled; will call finishOnce when done
                        }
                    } catch (Throwable childErr) {
                        logger.debug("collectPyCharm: computeChildren failed: " + childErr.getMessage());
                    }

                    // 4) Last resort: evaluate repr(var) in the frame (async) and use its presentation
                    try {
                        boolean needEval = (parent.value == null || parent.value.isEmpty() || "unavailable".equals(parent.value));
                        if (needEval && frame.getEvaluator() != null && parent.name != null && !parent.name.isEmpty()) {
                            String expr = "repr(" + parent.name + ")";
                            logger.debug("collectPyCharm: using evaluator expr=" + expr + " for '" + parent.name + "'");
                            frame.getEvaluator().evaluate(expr, new XDebuggerEvaluator.XEvaluationCallback() {
                                @Override
                                public void evaluated(@NotNull XValue result) {
                                    try {
                                        result.computePresentation(new XValueNode() {
                                            @Override
                                            public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                                                String ev = renderPresentationText(presentation);
                                                if (ev != null && !ev.isEmpty()) parent.value = ev;
                                            }
                                            @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                                            @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {}
                                        }, XValuePlace.TREE);
                                    } catch (Throwable e) {
                                        logger.debug("collectPyCharm: eval presentation parse failed: " + e.getMessage());
                                    } finally {
                                        finishOnce.run();
                                    }
                                }
                                @Override
                                public void errorOccurred(@NotNull String errorMessage) {
                                    logger.debug("collectPyCharm: evaluator error for '" + parent.name + "': " + errorMessage);
                                    finishOnce.run();
                                }
                            }, null);
                            return; // evaluation scheduled
                        }
                    } catch (Throwable e) {
                        logger.debug("collectPyCharm: evaluator attempt failed: " + e.getMessage());
                    }

                    // Nothing else to do — finish
                    finishOnce.run();
                }

                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {}
            }, XValuePlace.TREE);
        } catch (Throwable t) {
            logger.warn("collectPyCharmValueAndChildren outer failure: " + t.getMessage());
            parent.value = "Value not available";
            finishOnce.run();
        }
    }

    /**
     * Attempts to get PyCharm children, using several reflection strategies and broad checks.
     * Returns a list which may contain XValue instances or descriptor objects.
     * If none found, returns empty list.
     */
    private static List<Object> tryGetPyChildren(Object pyDebugValue) {
        try {
            if (pyDebugValue == null) return Collections.emptyList();

            // 1) direct getChildren()
            try {
                Method getChildren = pyDebugValue.getClass().getMethod("getChildren");
                Object res = getChildren.invoke(pyDebugValue);
                if (res instanceof List) {
                    List<?> raw = (List<?>) res;
                    List<Object> out = new ArrayList<>();
                    for (Object o : raw) if (o != null) out.add(o);
                    if (!out.isEmpty()) {
                        logger.debug("tryGetPyChildren: got " + out.size() + " children via getChildren()");
                        return out;
                    }
                }
            } catch (NoSuchMethodException ignored) {}

            // 2) some descriptors return getValue() which then has getChildren()
            try {
                Method getValue = pyDebugValue.getClass().getMethod("getValue");
                Object val = getValue.invoke(pyDebugValue);
                if (val != null) {
                    try {
                        Method getChildren2 = val.getClass().getMethod("getChildren");
                        Object childrenObj2 = getChildren2.invoke(val);
                        if (childrenObj2 instanceof List) {
                            List<?> raw = (List<?>) childrenObj2;
                            List<Object> out = new ArrayList<>();
                            for (Object o : raw) if (o != null) out.add(o);
                            if (!out.isEmpty()) {
                                logger.debug("tryGetPyChildren: got " + out.size() + " children via getValue().getChildren()");
                                return out;
                            }
                        }
                    } catch (NoSuchMethodException ignored) {}
                }
            } catch (NoSuchMethodException ignored) {}

            // 3) Some descriptors expose 'getTreeChildren' or similar — try generic attempt (best-effort)
            try {
                Method[] methods = pyDebugValue.getClass().getMethods();
                for (Method m : methods) {
                    String name = m.getName().toLowerCase();
                    if (name.contains("children") && m.getParameterCount() == 0 && List.class.isAssignableFrom(m.getReturnType())) {
                        try {
                            Object got = m.invoke(pyDebugValue);
                            if (got instanceof List) {
                                List<?> raw = (List<?>) got;
                                List<Object> out = new ArrayList<>();
                                for (Object o : raw) if (o != null) out.add(o);
                                if (!out.isEmpty()) {
                                    logger.debug("tryGetPyChildren: got " + out.size() + " children via " + m.getName());
                                    return out;
                                }
                            }
                        } catch (Throwable ex) { /* ignore per-method */ }
                    }
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            logger.debug("tryGetPyChildren failed: " + t.getMessage());
        }
        logger.debug("tryGetPyChildren: no usable children found");
        return Collections.emptyList();
    }

    private static Object tryGetPyDebugValue(XValue xValue) {
        try {
            if (xValue == null) return null;

            // 1) Common path: NodeDescriptorProvider
            try {
                if (xValue instanceof com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) {
                    Object desc = ((com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) xValue).getDescriptor();
                    if (desc != null) {
                        logger.debug("tryGetPyDebugValue: got descriptor via NodeDescriptorProvider: " + desc.getClass().getName());
                        return desc;
                    }
                }
            } catch (Throwable ignored) {}

            // 2) Reflective getDescriptor (classloader boundaries)
            try {
                Class<?> nodeProviderClass = Class.forName("com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider");
                if (nodeProviderClass.isInstance(xValue)) {
                    Method gd = nodeProviderClass.getMethod("getDescriptor");
                    Object desc = gd.invoke(xValue);
                    if (desc != null) {
                        logger.debug("tryGetPyDebugValue: got descriptor via reflection getDescriptor(): " + desc.getClass().getName());
                        return desc;
                    }
                }
            } catch (Throwable ignored) {}

            // 3) Sometimes the xValue itself *is* a PyDebugValue (rare) — check by class name
            try {
                if (isPyDebugValue(xValue)) {
                    logger.debug("tryGetPyDebugValue: xValue is PyDebugValue instance");
                    return xValue;
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            logger.debug("tryGetPyDebugValue error: " + t.getMessage());
        }
        return null;
    }

    private static String tryGetPyName(Object pyDebugValue) {
        try {
            Method getName = pyDebugValue.getClass().getMethod("getName");
            Object name = getName.invoke(pyDebugValue);
            return name != null ? name.toString() : null;
        } catch (Throwable ignored) {}
        return null;
    }
    private static String tryGetPyValue(Object pyDebugValue) {
        try {
            Method getValue = pyDebugValue.getClass().getMethod("getValue");
            Object val = getValue.invoke(pyDebugValue);
            return val != null ? val.toString() : null;
        } catch (Throwable ignored) {}
        return null;
    }
    private static String tryGetPyType(Object pyDebugValue) {
        try {
            Method getType = pyDebugValue.getClass().getMethod("getType");
            Object type = getType.invoke(pyDebugValue);
            return type != null ? type.toString() : null;
        } catch (Throwable ignored) {}
        return "unknown";
    }

    /**
     * Recursively collects child values for Java (native) debugger.
     */
    private static void collectValueAndChildren(XValue value, MutableSnapshotItem parent, int currentDepth, Runnable onComplete) {
        if (currentDepth >= Constants.MAX_DEPTH_OF_NESTED_VARIABLES) { onComplete.run(); return; }
        try {
            value.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    try {
                        if (presentation.getType() != null) parent.type = presentation.getType();
                        String rendered = renderPresentationText(presentation);
                        if ((parent.value == null || parent.value.isEmpty() || "unavailable".equals(parent.value)) && rendered != null && !rendered.isEmpty())
                            parent.value = rendered;
                    } catch (Exception e) {
                        parent.value = "Value not available";
                    } finally {
                        if (hasChildren) {
                            value.computeChildren(new XCompositeNode() {
                                @Override
                                public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                                    AtomicInteger pending = new AtomicInteger(children.size());
                                    if (children.size() == 0) { onComplete.run(); return; }
                                    for (int i = 0; i < children.size(); i++) {
                                        String childName = children.getName(i);
                                        XValue childValue = children.getValue(i);
                                        MutableSnapshotItem childItem = new MutableSnapshotItem(childName, "unknown", "unavailable", "Field");
                                        parent.children.add(childItem);
                                        collectValueAndChildren(childValue, childItem, currentDepth + 1, () -> {
                                            if (pending.decrementAndGet() == 0) onComplete.run();
                                        });
                                    }
                                }
                                @Override public void tooManyChildren(int remaining) { onComplete.run(); }
                                @Override public void setAlreadySorted(boolean alreadySorted) {}
                                @Override public void setErrorMessage(@NotNull String errorMessage) { onComplete.run(); }
                                @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { onComplete.run(); }
                                @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
                            });
                        } else {
                            onComplete.run();
                        }
                    }
                }
                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {}
            }, XValuePlace.TREE);
        } catch (Throwable t) {
            logger.warn("collectValueAndChildren failed: " + t.getMessage());
            parent.value = "Value not available";
            onComplete.run();
        }
    }

    public static void collectException(XStackFrame frame, Consumer<ContextItem> callback) {
        frame.computeChildren(new XCompositeNode() {
            @Override
            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                boolean foundException = false;
                for (int i = 0; i < children.size(); i++) {
                    String name = children.getName(i);
                    XValue value = children.getValue(i);
                    if (isPyCharmEnvironment() && "__exception__".equals(name)) {
                        foundException = true;
                        logger.debug("Found __exception__ in PyCharm frame: " + name);
                        processPyCharmExceptionTuple(value, frame, callback);
                        break;
                    } else if (isExceptionCandidateSafe(name, value)) {
                        foundException = true;
                        logger.debug("Found Java-style exception in frame: " + name);
                        processExceptionSafe(value, frame, callback);
                        break;
                    }
                }
                // Fallback for PyCharm: check for __exception__ by name
                if (!foundException && isPyCharmEnvironment()) {
                    for (int i = 0; i < children.size(); i++) {
                        if ("__exception__".equals(children.getName(i))) {
                            logger.debug("Fallback: Found __exception__ by name in PyCharm frame");
                            processPyCharmExceptionTuple(children.getValue(i), frame, callback);
                            return;
                        }
                    }
                }
                if (!foundException) {
                    logger.debug("No exception detected; collecting snapshot instead.");
                    collectSnapshot(frame, callback);
                }
            }
            @Override public void tooManyChildren(int remaining) {}
            @Override public void setAlreadySorted(boolean alreadySorted) {}
            @Override public void setErrorMessage(@NotNull String errorMessage) {}
            @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) {}
            @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
        });
    }

    // --- Exception helpers (unchanged from previous) ---

    private static void processPyCharmExceptionTuple(XValue exceptionTuple, XStackFrame frame, Consumer<ContextItem> callback) {
        // Try to compute children; if not 3 members, fallback to reflection-based extraction.
        final AtomicBoolean usedFallback = new AtomicBoolean(false);
        try {
            exceptionTuple.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList tupleChildren, boolean last) {
                    String type = null, message = null, stackTrace = null;
                    if (tupleChildren.size() == 3) {
                        logger.debug("PyCharm exception tuple has expected size=3");
                        type = extractTypeString(tupleChildren.getValue(0));
                        message = extractExceptionMessage(tupleChildren.getValue(1));
                        stackTrace = extractTraceback(tupleChildren.getValue(2));
                    } else {
                        logger.warn("PyCharm exception tuple has unexpected size=" + tupleChildren.size() + "; will fallback.");
                        usedFallback.set(true);
                    }

                    if (usedFallback.get()) {
                        Object py = tryGetPyDebugValue(exceptionTuple);
                        if (py != null) {
                            String t = tryGetPyType(py);
                            String m = tryGetPyValue(py);
                            String st = null;
                            List<Object> children = tryGetPyChildren(py);
                            if (children != null) {
                                StringBuilder tbSb = new StringBuilder();
                                for (Object c : children) {
                                    String cn = tryGetPyName(c);
                                    if ("__traceback__".equals(cn) || (cn != null && cn.toLowerCase().contains("trace"))) {
                                        String tv = tryGetPyValue(c);
                                        if (tv != null) tbSb.append(tv).append("\n");
                                    }
                                }
                                if (tbSb.length() > 0) st = tbSb.toString();
                            }
                            type = (t != null ? t : type);
                            message = (m != null ? m : message);
                            stackTrace = (st != null ? st : stackTrace);
                        } else {
                            String base = extractBaseMessageSafe(null, exceptionTuple);
                            message = (message == null || message.isEmpty()) ? base : message;
                        }
                    }

                    logger.debug("Exception detail: type=" + type + " message=" + message + " stackTrace=" + (stackTrace != null ? stackTrace.substring(0, Math.min(200, stackTrace.length())) : "null"));
                    ExceptionDetail detail = new ExceptionDetail(message, type, stackTrace,
                            frame.getSourcePosition() != null ? frame.getSourcePosition().getFile().getPath() : "unknown",
                            frame.getSourcePosition() != null ? frame.getSourcePosition().getLine() : -1);
                    instance.latestException = detail;
                    callback.accept(new ContextItem(detail, true, ContextItem.Type.EXCEPTION));
                }

                @Override public void tooManyChildren(int remaining) {}
                @Override public void setAlreadySorted(boolean alreadySorted) {}
                @Override public void setErrorMessage(@NotNull String errorMessage) {}
                @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) {}
                @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
            });
        } catch (Throwable t) {
            logger.warn("processPyCharmExceptionTuple failed: " + t.getMessage());
            try {
                Object py = tryGetPyDebugValue(exceptionTuple);
                String type = py != null ? tryGetPyType(py) : "unknown";
                String message = py != null ? tryGetPyValue(py) : extractBaseMessageSafe(null, exceptionTuple);
                ExceptionDetail detail = new ExceptionDetail(message, type, null,
                        frame.getSourcePosition() != null ? frame.getSourcePosition().getFile().getPath() : "unknown",
                        frame.getSourcePosition() != null ? frame.getSourcePosition().getLine() : -1);
                instance.latestException = detail;
                callback.accept(new ContextItem(detail, true, ContextItem.Type.EXCEPTION));
            } catch (Throwable ignore) {
                logger.warn("Final fallback in processPyCharmExceptionTuple failed; collecting snapshot.");
                collectSnapshot(frame, callback);
            }
        }
    }

    private static String extractTypeString(XValue value) {
        final String[] type = {"unknown"};
        try {
            value.computePresentation(new XValueNode() {
                @Override public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    if (presentation.getType() != null) type[0] = presentation.getType();
                }
                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {}
            }, XValuePlace.TREE);
        } catch (Throwable ignored) {}
        return type[0];
    }

    private static String extractExceptionMessage(XValue value) {
        final StringBuilder sb = new StringBuilder();
        try {
            value.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                    for (int i = 0; i < children.size(); i++) {
                        if ("args".equals(children.getName(i))) {
                            XValue argValue = children.getValue(i);
                            argValue.computePresentation(new XValueNode() {
                                @Override
                                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                                    sb.append(renderPresentationText(presentation));
                                }
                                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                                @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {}
                            }, XValuePlace.TREE);
                        }
                    }
                }
                @Override public void tooManyChildren(int remaining) {}
                @Override public void setAlreadySorted(boolean alreadySorted) {}
                @Override public void setErrorMessage(@NotNull String errorMessage) {}
                @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) {}
                @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
            });
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private static String extractTraceback(XValue value) {
        final StringBuilder sb = new StringBuilder();
        try {
            value.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                    for (int i = 0; i < children.size(); i++) {
                        if ("tb_frame".equals(children.getName(i))) {
                            XValue frameValue = children.getValue(i);
                            frameValue.computePresentation(new XValueNode() {
                                @Override
                                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                                    sb.append(renderPresentationText(presentation)).append("\n");
                                }
                                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                                @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {}
                            }, XValuePlace.TREE);
                        }
                    }
                }
                @Override public void tooManyChildren(int remaining) {}
                @Override public void setAlreadySorted(boolean alreadySorted) {}
                @Override public void setErrorMessage(@NotNull String errorMessage) {}
                @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) {}
                @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
            });
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private static boolean isExceptionCandidateSafe(String name, XValue value) {
        try {
            boolean nameSuggests = (name != null && name.toLowerCase().contains("exception"));
            String type = getExceptionType(value);
            boolean typeSuggests = type != null && type.toLowerCase().contains("exception");
            boolean descriptorSuggests = false;
            try {
                Method getDescriptorMethod = null;
                if (value instanceof com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) {
                    Object desc = ((com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) value).getDescriptor();
                    descriptorSuggests = isPyDebugValue(desc);
                } else {
                    Class<?> nodeProviderClass = Class.forName("com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider");
                    if (nodeProviderClass.isInstance(value)) {
                        Method gd = nodeProviderClass.getMethod("getDescriptor");
                        Object desc = gd.invoke(value);
                        descriptorSuggests = isPyDebugValue(desc);
                    }
                }
            } catch (Throwable t) {}
            return nameSuggests || typeSuggests || descriptorSuggests;
        } catch (Throwable t) {
            logger.debug("isExceptionCandidateSafe error: " + t.getMessage());
            return false;
        }
    }

    private static void processExceptionSafe(XValue value, XStackFrame frame, Consumer<ContextItem> callback) {
        try {
            Object descriptorObj = null;
            try {
                if (value instanceof com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) {
                    descriptorObj = ((com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) value).getDescriptor();
                } else {
                    Class<?> nodeProviderClass = Class.forName("com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider");
                    if (nodeProviderClass.isInstance(value)) {
                        Method gd = nodeProviderClass.getMethod("getDescriptor");
                        descriptorObj = gd.invoke(value);
                    }
                }
            } catch (Throwable t) {
                descriptorObj = null;
            }

            String type = getExceptionType(value);
            ExceptionState state = new ExceptionState(descriptorObj != null ? descriptorObj : value, type, frame);

            Object finalDescriptorObj = descriptorObj;
            value.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList exChildren, boolean last) {
                    for (int j = 0; j < exChildren.size(); j++) {
                        String fieldName = exChildren.getName(j);
                        XValue fieldValue = exChildren.getValue(j);
                        if ("detailMessage".equals(fieldName) || "message".equalsIgnoreCase(fieldName) || "args".equals(fieldName))
                            processDetailMessage(fieldValue, state, callback);
                        else if ("stackTrace".equals(fieldName) || fieldName.toLowerCase().contains("traceback") || "__traceback__".equals(fieldName))
                            processStackTrace(fieldValue, state, callback);
                    }
                    if (state.message == null) {
                        String base = extractBaseMessageSafe(finalDescriptorObj, value);
                        state.setMessage(base);
                    }
                    if (state.isComplete()) completeExceptionState(state, callback);
                }
                @Override public void tooManyChildren(int remaining) {}
                @Override public void setAlreadySorted(boolean alreadySorted) {}
                @Override public void setErrorMessage(@NotNull String errorMessage) {}
                @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) {}
                @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
            });
        } catch (Throwable t) {
            logger.warn("processExceptionSafe error: " + t.getMessage());
            collectSnapshot(frame, callback);
        }
    }

    private static void processDetailMessage(XValue fieldValue, ExceptionState state, Consumer<ContextItem> callback) {
        try {
            fieldValue.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    String message = renderPresentationText(presentation);
                    if (!"Collecting data...".equals(message)) {
                        state.setDetailMessage(message);
                        if (state.isComplete()) completeExceptionState(state, callback);
                    }
                }
                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {}
            }, XValuePlace.TREE);
        } catch (Throwable t) { logger.debug("processDetailMessage failed: " + t.getMessage()); }
    }

    private static void processStackTrace(XValue fieldValue, ExceptionState state, Consumer<ContextItem> callback) {
        try {
            fieldValue.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    state.setStackTrace(renderPresentationText(presentation));
                    if (state.isComplete()) completeExceptionState(state, callback);
                }
                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {}
            }, XValuePlace.TREE);
        } catch (Throwable t) { logger.debug("processStackTrace failed: " + t.getMessage()); }
    }

    private static String renderPresentationText(XValuePresentation presentation) {
        StringBuilder sb = new StringBuilder();
        try {
            presentation.renderValue(new XValuePresentation.XValueTextRenderer() {
                @Override public void renderValue(@Nullable String value) { if (value != null) sb.append(value); }
                @Override public void renderStringValue(@Nullable String value) { if (value != null) sb.append(value); }
                @Override public void renderNumericValue(@Nullable String value) { if (value != null) sb.append(value); }
                @Override public void renderKeywordValue(@Nullable String value) { if (value != null) sb.append(value); }
                @Override public void renderValue(@Nullable String value, @NotNull TextAttributesKey key) { if (value != null) sb.append(value); }
                @Override public void renderStringValue(@Nullable String value, @Nullable String ref, int unused) { if (value != null) sb.append(value); }
                @Override public void renderComment(@NotNull String comment) {}
                @Override public void renderSpecialSymbol(@NotNull String symbol) { sb.append(symbol); }
                @Override public void renderError(@NotNull String error) { sb.append(error); }
            });
        } catch (Throwable t) { logger.debug("renderPresentationText failed: " + t.getMessage()); }
        return sb.toString();
    }

    private static void completeExceptionState(ExceptionState state, Consumer<ContextItem> callback) {
        try {
            ExceptionDetail detail = state.buildExceptionDetail();
            instance.latestException = detail;
            callback.accept(new ContextItem(detail, true, ContextItem.Type.EXCEPTION));
        } catch (Throwable t) { logger.warn("completeExceptionState failed: " + t.getMessage()); }
    }

    private static String getExceptionType(XValue value) {
        final String[] type = {"unknown"};
        try {
            value.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    String typeStr = presentation.getType();
                    if (typeStr != null && typeStr.toLowerCase().contains("exception")) type[0] = typeStr;
                }
                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {}
            }, XValuePlace.TREE);
        } catch (Throwable t) { logger.debug("getExceptionType failed: " + t.getMessage()); }
        return type[0];
    }

    private static String extractBaseMessageSafe(Object descriptorObj, XValue xValue) {
        try {
            if (descriptorObj != null) {
                try {
                    Class<?> vdiClass = Class.forName("com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl");
                    if (vdiClass.isInstance(descriptorObj)) {
                        Method getValue = vdiClass.getMethod("getValue");
                        Object val = getValue.invoke(descriptorObj);
                        if (val != null) return val.toString();
                    }
                } catch (Throwable ignored) {}
            }
            if (descriptorObj != null && isPyDebugValue(descriptorObj)) {
                try {
                    Class<?> pyCls = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
                    Method getValue = pyCls.getMethod("getValue");
                    Object val = getValue.invoke(descriptorObj);
                    if (val != null) return val.toString();
                } catch (Throwable ignored) {}
            }
            final String[] rendered = {"null"};
            try {
                xValue.computePresentation(new XValueNode() {
                    @Override
                    public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                        StringBuilder sb = new StringBuilder();
                        presentation.renderValue(new XValuePresentation.XValueTextRenderer() {
                            @Override public void renderValue(@NotNull String value) { sb.append(value); }
                            @Override public void renderStringValue(@NotNull String value) { sb.append(value); }
                            @Override public void renderNumericValue(@NotNull String value) { sb.append(value); }
                            @Override public void renderKeywordValue(@NotNull String value) { sb.append(value); }
                            @Override public void renderValue(@NotNull String value, @NotNull TextAttributesKey key) { sb.append(value); }
                            @Override public void renderStringValue(@NotNull String value, @Nullable String ref, int unused) { sb.append(value); }
                            @Override public void renderComment(@NotNull String comment) {}
                            @Override public void renderSpecialSymbol(@NotNull String symbol) { sb.append(symbol); }
                            @Override public void renderError(@NotNull String error) { sb.append(error); }
                        });
                        rendered[0] = sb.toString();
                    }
                    @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                    @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {}
                }, XValuePlace.TREE);
            } catch (Throwable t) { logger.debug("extractBaseMessageSafe presentation render error: " + t.getMessage()); }
            return rendered[0];
        } catch (Throwable t) {
            logger.warn("extractBaseMessageSafe overall error: " + t.getMessage());
            return "null";
        }
    }

    @Nullable
    private static String extractEnclosingFunctionReflective(@NotNull Project project, @NotNull VirtualFile file, int line) {
        try {
            Class<?> psiManagerClass = Class.forName("com.intellij.psi.PsiManager");
            Class<?> psiFileClass = Class.forName("com.intellij.psi.PsiFile");
            Class<?> psiElementClass = Class.forName("com.intellij.psi.PsiElement");

            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document == null || line >= document.getLineCount()) return null;

            Object psiManager = psiManagerClass.getMethod("getInstance", Project.class).invoke(null, project);
            Object psiFile = psiManagerClass.getMethod("findFile", VirtualFile.class).invoke(psiManager, file);
            if (psiFile == null) return null;

            int offset = document.getLineStartOffset(line);
            Object elementAt = psiFileClass.getMethod("findElementAt", int.class).invoke(psiFile, offset);
            if (elementAt == null) return null;

            Class<?> psiTreeUtilClass = Class.forName("com.intellij.psi.util.PsiTreeUtil");
            Class<?> psiMethodClass = null;
            Class<?> psiLambdaClass = null;
            try { psiMethodClass = Class.forName("com.intellij.psi.PsiMethod"); } catch (Throwable ignored) {}
            try { psiLambdaClass = Class.forName("com.intellij.psi.PsiLambdaExpression"); } catch (Throwable ignored) {}

            Object functionElement = null;
            if (psiMethodClass != null) {
                functionElement = (psiLambdaClass != null)
                        ? psiTreeUtilClass.getMethod("getParentOfType", psiElementClass, Class.class, Class.class)
                        .invoke(null, elementAt, psiMethodClass, psiLambdaClass)
                        : psiTreeUtilClass.getMethod("getParentOfType", psiElementClass, Class.class)
                        .invoke(null, elementAt, psiMethodClass);
            } else return null;
            if (functionElement == null) return null;

            String fullText = (String) functionElement.getClass().getMethod("getText").invoke(functionElement);
            if (fullText == null) return null;

            String[] lines = fullText.split("\n");
            int prefix = Constants.ENCLOSING_FUNCTION_PREFIX_LINES, suffix = Constants.ENCLOSING_FUNCTION_SUFFIX_LINES;
            if (lines.length <= prefix + suffix) return fullText;

            int functionStartOffset = (int) functionElement.getClass().getMethod("getTextRange").invoke(functionElement)
                    .getClass().getMethod("getStartOffset").invoke(functionElement.getClass().getMethod("getTextRange").invoke(functionElement));
            int functionStartLine = document.getLineNumber(functionStartOffset);
            int targetLineInFunction = line - functionStartLine;
            int startLine = Math.max(targetLineInFunction - prefix, 0), endLine = Math.min(targetLineInFunction + suffix, lines.length - 1);

            StringBuilder clipped = new StringBuilder();
            for (int i = startLine; i <= endLine; i++) clipped.append(lines[i]).append("\n");
            return clipped.toString().trim();
        } catch (ClassNotFoundException cnfe) {
            logger.debug("PSI not available in runtime: " + cnfe.getMessage());
            return null;
        } catch (Throwable t) {
            logger.warn("extractEnclosingFunctionReflective error: " + t.getMessage());
            return null;
        }
    }
}
