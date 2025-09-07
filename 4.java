package org.samsung.aipp.aippintellij.debugAssist;

import com.google.gson.Gson;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
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
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * DebugDataCollector
 *
 * Cleaned / reorganized version with:
 *  - read-action safe PSI access
 *  - defensive reflection for PyCharm types
 *  - cycle / size guards for snapshot traversal
 *  - clearer logging and helper extraction methods
 *
 * Note: relies on surrounding types (SnapshotItem, MutableSnapshotItem, StackItem,
 * ExceptionDetail, ExceptionState, ContextItem, Constants) being available in project.
 */
public class DebugDataCollector {

    private static final Logger LOG = Logger.getInstance(DebugDataCollector.class);
    private static final DebugDataCollector INSTANCE = new DebugDataCollector();

    private final List<SnapshotItem> latestSnapshot = new ArrayList<>();
    private final List<StackItem> latestStack = new ArrayList<>();
    private ExceptionDetail latestException = null;

    private static Boolean cachedIsPyCharm = null;

    private DebugDataCollector() {
        LOG.debug(isPyCharmEnvironment()
                ? "[DebugDataCollector] PyCharm debugger environment detected."
                : "[DebugDataCollector] Running IntelliJ debugger environment.");
    }

    public static DebugDataCollector getInstance() { return INSTANCE; }

    public List<SnapshotItem> getSnapshot() { return new ArrayList<>(latestSnapshot); }

    public List<StackItem> getCallStack() { return new ArrayList<>(latestStack); }

    public ExceptionDetail getExceptionDetail() { return latestException; }

    public void clearDebugData() {
        latestSnapshot.clear();
        latestStack.clear();
        latestException = null;
    }

    // -------------------------
    // Environment detection
    // -------------------------
    private static boolean isPyCharmEnvironment() {
        if (cachedIsPyCharm != null) return cachedIsPyCharm;
        try {
            Class.forName("com.jetbrains.python.debugger.PyDebugValue");
            cachedIsPyCharm = true;
        } catch (Throwable t) {
            cachedIsPyCharm = false;
        }
        LOG.debug("isPyCharmEnvironment detected: " + cachedIsPyCharm);
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

    // -------------------------
    // Stack collection
    // -------------------------
    public static void collectStackItems(XDebugProcess debugProcess, Consumer<ContextItem> callback) {
        List<StackItem> stackItems = new ArrayList<>();
        XExecutionStack stack = null;
        try {
            if (debugProcess != null && debugProcess.getSession() != null && debugProcess.getSession().getSuspendContext() != null) {
                stack = debugProcess.getSession().getSuspendContext().getActiveExecutionStack();
            }
        } catch (Throwable t) {
            LOG.warn("Unable to obtain execution stack: " + t.getMessage());
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
                    try {
                        XSourcePosition pos = frame.getSourcePosition();
                        if (pos != null) {
                            VirtualFile vf = pos.getFile();
                            if (vf == null) continue;
                            String file = vf.getPath();
                            int lineOneBased = pos.getLine() + 1;
                            String language = vf.getExtension();
                            String functionText = extractEnclosingFunctionSafe(debugProcess, pos);
                            stackItems.add(new StackItem(file, lineOneBased, functionText != null ? functionText : "", language));
                        } else {
                            // Add placeholder for frames without source position
                            stackItems.add(new StackItem("unknown", -1, frameToStringSafe(frame), "unknown"));
                        }
                    } catch (Throwable frameErr) {
                        LOG.debug("Error processing stack frame: " + frameErr.getMessage());
                        stackItems.add(new StackItem("unknown", -1, "frame-error", "unknown"));
                    }
                }
                trimToJsonSize(stackItems, Constants.MAX_CALLSTACK_JSON_SIZE_BYTES);
                INSTANCE.latestStack.clear();
                INSTANCE.latestStack.addAll(stackItems);
                callback.accept(new ContextItem(stackItems, true, ContextItem.Type.STACK));
            }

            @Override
            public void errorOccurred(@NotNull String errorMessage) {
                LOG.warn("computeStackFrames.errorOccurred: " + errorMessage);
                callback.accept(new ContextItem(stackItems, false, ContextItem.Type.STACK));
            }
        });
    }

    private static String frameToStringSafe(XStackFrame frame) {
        try { return frame.toString(); } catch (Throwable t) { return "frame"; }
    }

    private static void trimToJsonSize(List<?> items, int maxBytes) {
        Gson gson = new Gson();
        while (!items.isEmpty() && gson.toJson(items).length() > maxBytes) items.remove(items.size() - 1);
    }

    // -------------------------
    // Snapshot collection (top-level)
    // -------------------------
    public static void collectSnapshot(XStackFrame currentStackFrame, Consumer<ContextItem> callback) {
        List<MutableSnapshotItem> snapshotItems = new ArrayList<>();
        AtomicInteger debuggerCalls = new AtomicInteger(0);
        AtomicBoolean limitReached = new AtomicBoolean(false);

        // global guards for traversal
        final AtomicInteger totalNodes = new AtomicInteger(0);
        final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        try {
            currentStackFrame.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                    AtomicInteger pending = new AtomicInteger(children.size());
                    if (children.size() == 0) {
                        complete();
                        return;
                    }
                    for (int i = 0; i < children.size(); i++) {
                        if (limitReached.get()
                                || debuggerCalls.get() >= Constants.MAX_CALLS_TO_DEBUGGER
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
                            LOG.debug("Trying PyCharm-specific collection for variable: " + varName);
                            collectPyCharmValueAndChildren(currentStackFrame, childValue, mutableItem, 0, visited, totalNodes, () -> {
                                if (pending.decrementAndGet() == 0) complete();
                            });
                        } else {
                            collectValueAndChildren(childValue, mutableItem, 0, visited, totalNodes, () -> {
                                if (pending.decrementAndGet() == 0) complete();
                            });
                        }
                    }
                }

                private void complete() {
                    List<SnapshotItem> result = new ArrayList<>();
                    for (MutableSnapshotItem item : snapshotItems) result.add(item.toSnapshotItem());
                    INSTANCE.latestSnapshot.clear();
                    INSTANCE.latestSnapshot.addAll(result);
                    callback.accept(new ContextItem(result, true, ContextItem.Type.SNAPSHOT));
                }

                @Override public void tooManyChildren(int remaining) {}
                @Override public void setAlreadySorted(boolean alreadySorted) {}
                @Override public void setErrorMessage(@NotNull String errorMessage) {}
                @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) {}
                @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
            });
        } catch (Throwable t) {
            LOG.warn("collectSnapshot outer error: " + t.getMessage());
            List<SnapshotItem> result = new ArrayList<>();
            for (MutableSnapshotItem item : snapshotItems) result.add(item.toSnapshotItem());
            INSTANCE.latestSnapshot.clear();
            INSTANCE.latestSnapshot.addAll(result);
            callback.accept(new ContextItem(result, false, ContextItem.Type.SNAPSHOT));
        }
    }

    // -------------------------
    // Generic XValue traversal (non-Python specific)
    // -------------------------
    private static void collectValueAndChildren(XValue value, MutableSnapshotItem parent, int currentDepth,
                                                Set<Object> visited, AtomicInteger totalNodes, Runnable onComplete) {
        if (currentDepth >= Constants.MAX_DEPTH_OF_NESTED_VARIABLES) { onComplete.run(); return; }

        if (totalNodes.incrementAndGet() > Constants.MAX_TOTAL_NODES) {
            parent.value = "[truncated: max nodes]";
            onComplete.run();
            return;
        }

        if (value == null) { onComplete.run(); return; }

        // identity check to avoid infinite recursion
        if (!visited.add(value)) { parent.value = "[cyclic]"; onComplete.run(); return; }

        final XValue xValueLocal = value;
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Runnable finishOnce = () -> { if (finished.compareAndSet(false, true)) onComplete.run(); };

        try {
            xValueLocal.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    try {
                        if (presentation.getType() != null) parent.type = presentation.getType();
                        String rendered = renderPresentationText(presentation);
                        if ((parent.value == null || parent.value.isEmpty() || "unavailable".equals(parent.value))
                                && rendered != null && !rendered.isEmpty()) {
                            parent.value = rendered;
                        }
                    } catch (Exception e) {
                        parent.value = "Value not available";
                    } finally {
                        if (hasChildren) {
                            xValueLocal.computeChildren(new XCompositeNode() {
                                @Override
                                public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                                    int childCount = Math.min(children.size(), Constants.MAX_CHILDREN_PER_NODE);
                                    if (children.size() > Constants.MAX_CHILDREN_PER_NODE) {
                                        parent.value = (parent.value == null ? "" : parent.value) + " [truncated children]";
                                    }
                                    if (childCount == 0) { finishOnce.run(); return; }
                                    AtomicInteger pending = new AtomicInteger(childCount);
                                    for (int i = 0; i < childCount; i++) {
                                        String childName = children.getName(i);
                                        XValue childValue = children.getValue(i);
                                        MutableSnapshotItem childItem = new MutableSnapshotItem(childName, "unknown", "unavailable", "Field");
                                        parent.children.add(childItem);
                                        try {
                                            collectValueAndChildren(childValue, childItem, currentDepth + 1, visited, totalNodes, () -> {
                                                if (pending.decrementAndGet() == 0) finishOnce.run();
                                            });
                                        } catch (Throwable inner) {
                                            if (pending.decrementAndGet() == 0) finishOnce.run();
                                        }
                                    }
                                }
                                @Override public void tooManyChildren(int remaining) { finishOnce.run(); }
                                @Override public void setAlreadySorted(boolean alreadySorted) {}
                                @Override public void setErrorMessage(@NotNull String errorMessage) { finishOnce.run(); }
                                @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { finishOnce.run(); }
                                @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
                            });
                        } else {
                            finishOnce.run();
                        }
                    }
                }

                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull String typeStr, @NotNull String valueStr, boolean hasChildren) {
                    try {
                        if (typeStr != null && !typeStr.isEmpty()) parent.type = typeStr;
                        if (valueStr != null && !valueStr.isEmpty()) parent.value = valueStr;
                    } catch (Throwable t) { parent.value = "Value not available"; }

                    if (hasChildren) {
                        xValueLocal.computeChildren(new XCompositeNode() {
                            @Override
                            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                                int childCount = Math.min(children.size(), Constants.MAX_CHILDREN_PER_NODE);
                                if (childCount == 0) { finishOnce.run(); return; }
                                AtomicInteger pending = new AtomicInteger(childCount);
                                for (int i = 0; i < childCount; i++) {
                                    String childName = children.getName(i);
                                    XValue childValue = children.getValue(i);
                                    MutableSnapshotItem childItem = new MutableSnapshotItem(childName, "unknown", "unavailable", "Field");
                                    parent.children.add(childItem);
                                    try {
                                        collectValueAndChildren(childValue, childItem, currentDepth + 1, visited, totalNodes, () -> {
                                            if (pending.decrementAndGet() == 0) finishOnce.run();
                                        });
                                    } catch (Throwable inner) {
                                        if (pending.decrementAndGet() == 0) finishOnce.run();
                                    }
                                }
                            }
                            @Override public void tooManyChildren(int remaining) { finishOnce.run(); }
                            @Override public void setAlreadySorted(boolean alreadySorted) {}
                            @Override public void setErrorMessage(@NotNull String errorMessage) { finishOnce.run(); }
                            @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { finishOnce.run(); }
                            @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
                        });
                    } else {
                        finishOnce.run();
                    }
                }

                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
            }, XValuePlace.TREE);
        } catch (Throwable t) {
            LOG.warn("collectValueAndChildren failed: " + t.getMessage());
            parent.value = "Value not available";
            onComplete.run();
        }
    }

    // -------------------------
    // PyCharm-specific traversal (reflection + fallback)
    // -------------------------
    private static void collectPyCharmValueAndChildren(XStackFrame frame, XValue value, MutableSnapshotItem parent,
                                                      int currentDepth, Set<Object> visited, AtomicInteger totalNodes, Runnable onComplete) {
        if (currentDepth >= Constants.MAX_DEPTH_OF_NESTED_VARIABLES) { onComplete.run(); return; }

        if (totalNodes.incrementAndGet() > Constants.MAX_TOTAL_NODES) {
            parent.value = "[truncated: max nodes]";
            onComplete.run();
            return;
        }

        if (value == null) { onComplete.run(); return; }
        if (!visited.add(value)) { parent.value = "[cyclic]"; onComplete.run(); return; }

        final XValue xValueLocal = value;
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Runnable finishOnce = () -> { if (finished.compareAndSet(false, true)) onComplete.run(); };

        try {
            Object pyDebugValue = tryGetPyDebugValue(value);
            if (pyDebugValue != null) {
                try {
                    String name = tryGetPyName(pyDebugValue);
                    String val = tryGetPyValue(pyDebugValue);
                    String type = tryGetPyType(pyDebugValue);
                    if (name != null && !name.isEmpty()) parent.name = name;
                    if (val != null && !"unavailable".equals(val) && !val.isEmpty()) parent.value = val;
                    if (type != null && !type.isEmpty()) parent.type = type;

                    List<Object> pyChildren = tryGetPyChildren(pyDebugValue);
                    if (pyChildren != null && !pyChildren.isEmpty()) {
                        int childCount = Math.min(pyChildren.size(), Constants.MAX_CHILDREN_PER_NODE);
                        AtomicInteger pending = new AtomicInteger(childCount);
                        for (int i = 0; i < childCount; i++) {
                            Object child = pyChildren.get(i);
                            try {
                                if (child instanceof XValue) {
                                    MutableSnapshotItem childItem = new MutableSnapshotItem("unknown", "unknown", "unavailable", "Field");
                                    parent.children.add(childItem);
                                    collectPyCharmValueAndChildren(frame, (XValue) child, childItem, currentDepth + 1, visited, totalNodes, () -> {
                                        if (pending.decrementAndGet() == 0) finishOnce.run();
                                    });
                                } else {
                                    MutableSnapshotItem childItem = createSnapshotFromDescriptor(child);
                                    parent.children.add(childItem);
                                    if (pending.decrementAndGet() == 0) finishOnce.run();
                                }
                            } catch (Throwable inner) {
                                if (pending.decrementAndGet() == 0) finishOnce.run();
                            }
                        }
                        return;
                    }
                } catch (Throwable ignored) {
                    // fall back to presentation-based extraction below
                }
            }

            // Fallback: presentation + children traversal (identical to non-py path)
            xValueLocal.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    try {
                        String pres = renderPresentationText(presentation);
                        if (pres != null && !pres.isEmpty() && !"Collecting data...".equals(pres)) parent.value = pres;
                        if (presentation.getType() != null) parent.type = presentation.getType();
                    } catch (Throwable t) { /* ignore */ }

                    if (hasChildren) {
                        xValueLocal.computeChildren(new XCompositeNode() {
                            @Override
                            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                                int childCount = Math.min(children.size(), Constants.MAX_CHILDREN_PER_NODE);
                                if (childCount == 0) { finishOnce.run(); return; }
                                AtomicInteger pending = new AtomicInteger(childCount);
                                for (int i = 0; i < childCount; i++) {
                                    String childName = children.getName(i);
                                    XValue childValue = children.getValue(i);
                                    MutableSnapshotItem childItem = new MutableSnapshotItem(childName != null ? childName : "unknown", "unknown", "unavailable", "Field");
                                    parent.children.add(childItem);
                                    try {
                                        collectPyCharmValueAndChildren(frame, childValue, childItem, currentDepth + 1, visited, totalNodes, () -> {
                                            if (pending.decrementAndGet() == 0) finishOnce.run();
                                        });
                                    } catch (Throwable inner) {
                                        if (pending.decrementAndGet() == 0) finishOnce.run();
                                    }
                                }
                            }
                            @Override public void tooManyChildren(int remaining) { finishOnce.run(); }
                            @Override public void setAlreadySorted(boolean alreadySorted) {}
                            @Override public void setErrorMessage(@NotNull String errorMessage) { finishOnce.run(); }
                            @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { finishOnce.run(); }
                            @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
                        });
                    } else {
                        finishOnce.run();
                    }
                }

                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull String typeStr, @NotNull String valueStr, boolean hasChildren) {
                    try {
                        if (valueStr != null && !valueStr.isEmpty()) parent.value = valueStr;
                        if (typeStr != null && !typeStr.isEmpty()) parent.type = typeStr;
                    } catch (Throwable t) {}
                    if (hasChildren) {
                        xValueLocal.computeChildren(new XCompositeNode() {
                            @Override
                            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                                int childCount = Math.min(children.size(), Constants.MAX_CHILDREN_PER_NODE);
                                if (childCount == 0) { finishOnce.run(); return; }
                                AtomicInteger pending = new AtomicInteger(childCount);
                                for (int i = 0; i < childCount; i++) {
                                    String childName = children.getName(i);
                                    XValue childValue = children.getValue(i);
                                    MutableSnapshotItem childItem = new MutableSnapshotItem(childName, "unknown", "unavailable", "Field");
                                    parent.children.add(childItem);
                                    try {
                                        collectPyCharmValueAndChildren(frame, childValue, childItem, currentDepth + 1, visited, totalNodes, () -> {
                                            if (pending.decrementAndGet() == 0) finishOnce.run();
                                        });
                                    } catch (Throwable inner) {
                                        if (pending.decrementAndGet() == 0) finishOnce.run();
                                    }
                                }
                            }
                            @Override public void tooManyChildren(int remaining) { finishOnce.run(); }
                            @Override public void setAlreadySorted(boolean alreadySorted) {}
                            @Override public void setErrorMessage(@NotNull String errorMessage) { finishOnce.run(); }
                            @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { finishOnce.run(); }
                            @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
                        });
                    } else {
                        finishOnce.run();
                    }
                }

                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
            }, XValuePlace.TREE);

        } catch (Throwable t) {
            LOG.warn("collectPyCharmValueAndChildren failed: " + t.getMessage());
            parent.value = "Value not available";
            onComplete.run();
        }
    }

    // -------------------------
    // PyCharm descriptor helpers (reflection-heavy, defensive)
    // -------------------------
    private static Object tryGetPyDebugValue(XValue xValue) {
        try {
            if (xValue == null) return null;

            // Try NodeDescriptorProvider (direct)
            try {
                if (xValue instanceof com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) {
                    Object desc = ((com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) xValue).getDescriptor();
                    if (desc != null) {
                        LOG.debug("tryGetPyDebugValue: got descriptor via NodeDescriptorProvider: " + safeClassName(desc));
                        return desc;
                    }
                }
            } catch (Throwable ignored) {}

            // Try reflection for NodeDescriptorProvider
            try {
                Class<?> nodeProviderClass = Class.forName("com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider");
                if (nodeProviderClass.isInstance(xValue)) {
                    Method gd = nodeProviderClass.getMethod("getDescriptor");
                    Object desc = gd.invoke(xValue);
                    if (desc != null) {
                        LOG.debug("tryGetPyDebugValue: got descriptor via reflection getDescriptor(): " + safeClassName(desc));
                        return desc;
                    }
                }
            } catch (Throwable ignored) {}

            // If xValue itself is PyDebugValue
            try {
                if (isPyDebugValue(xValue)) {
                    LOG.debug("tryGetPyDebugValue: xValue is PyDebugValue instance: " + safeClassName(xValue));
                    return xValue;
                }
            } catch (Throwable ignored) {}

        } catch (Throwable t) {
            LOG.debug("tryGetPyDebugValue error: " + t.getMessage());
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
            return type != null ? type.toString() : "unknown";
        } catch (Throwable ignored) {}
        return "unknown";
    }

    private static List<Object> tryGetPyChildren(Object pyDebugValue) {
        try {
            if (pyDebugValue == null) return Collections.emptyList();

            // Try direct getChildren()
            try {
                Method getChildren = pyDebugValue.getClass().getMethod("getChildren");
                Object res = getChildren.invoke(pyDebugValue);
                List<Object> out = listFromPossibleCollection(res);
                if (!out.isEmpty()) {
                    LOG.debug("tryGetPyChildren: got " + out.size() + " children via getChildren()");
                    return out;
                }
            } catch (NoSuchMethodException ignored) {}

            // Try value.getChildren()
            try {
                Method getValue = pyDebugValue.getClass().getMethod("getValue");
                Object val = getValue.invoke(pyDebugValue);
                if (val != null) {
                    try {
                        Method getChildren2 = val.getClass().getMethod("getChildren");
                        Object childrenObj2 = getChildren2.invoke(val);
                        List<Object> out = listFromPossibleCollection(childrenObj2);
                        if (!out.isEmpty()) {
                            LOG.debug("tryGetPyChildren: got " + out.size() + " children via getValue().getChildren()");
                            return out;
                        }
                    } catch (NoSuchMethodException ignored) {}
                }
            } catch (NoSuchMethodException ignored) {}

            // Heuristic: inspect descriptor fields or methods that might contain children
            List<Object> inspected = inspectDescriptorForChildren(pyDebugValue);
            if (!inspected.isEmpty()) return inspected;

        } catch (Throwable t) {
            LOG.debug("tryGetPyChildren failed early: " + t.getMessage());
        }
        LOG.debug("tryGetPyChildren: no usable children found");
        return Collections.emptyList();
    }

    private static List<Object> inspectDescriptorForChildren(Object descriptor) {
        if (descriptor == null) return Collections.emptyList();
        List<Object> out = new ArrayList<>();
        try {
            // declared fields
            Field[] fields = descriptor.getClass().getDeclaredFields();
            for (Field f : fields) {
                try {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(descriptor);
                    List<Object> list = listFromPossibleCollection(val);
                    if (!list.isEmpty()) {
                        out.addAll(list);
                        LOG.debug("inspectDescriptorForChildren: found children via field " + f.getName());
                    }
                } catch (Throwable ignored) {}
            }

            // zero-arg methods which sound like containers
            Method[] methods = descriptor.getClass().getMethods();
            for (Method m : methods) {
                try {
                    if (m.getParameterCount() != 0) continue;
                    String name = m.getName().toLowerCase();
                    if (!(name.contains("children") || name.contains("items") || name.contains("values") || name.contains("elements"))) continue;
                    Object res = m.invoke(descriptor);
                    List<Object> list = listFromPossibleCollection(res);
                    if (!list.isEmpty()) {
                        out.addAll(list);
                        LOG.debug("inspectDescriptorForChildren: found children via method " + m.getName());
                    }
                } catch (Throwable ignored) {}
            }

            // fallback heuristics
            for (Method m : methods) {
                try {
                    if (m.getParameterCount() != 0) continue;
                    String name = m.getName().toLowerCase();
                    if (name.contains("toarray") || name.contains("aslist")) {
                        Object res = m.invoke(descriptor);
                        List<Object> list = listFromPossibleCollection(res);
                        if (!list.isEmpty()) {
                            out.addAll(list);
                            LOG.debug("inspectDescriptorForChildren: found children via fallback method " + m.getName());
                        }
                    }
                } catch (Throwable ignored) {}
            }

        } catch (Throwable t) {
            LOG.debug("inspectDescriptorForChildren failed: " + t.getMessage());
        }
        return out;
    }

    private static List<Object> listFromPossibleCollection(Object obj) {
        List<Object> out = new ArrayList<>();
        try {
            if (obj == null) return out;
            if (obj instanceof List) { out.addAll((List<?>) obj); return out; }
            if (obj instanceof Collection) { out.addAll((Collection<?>) obj); return out; }
            if (obj instanceof Map) { out.addAll(((Map<?, ?>) obj).entrySet()); return out; }
            if (obj instanceof Iterable) { for (Object o : (Iterable<?>) obj) out.add(o); return out; }
            if (obj.getClass().isArray()) {
                int len = Array.getLength(obj);
                for (int i = 0; i < len; i++) out.add(Array.get(obj, i));
                return out;
            }
            out.add(obj);
            return out;
        } catch (Throwable t) {
            LOG.debug("listFromPossibleCollection failed: " + t.getMessage());
            return out;
        }
    }

    private static MutableSnapshotItem createSnapshotFromDescriptor(Object desc) {
        try {
            String name = null, type = null, value = null;
            try { Method m = desc.getClass().getMethod("getName"); Object r = m.invoke(desc); if (r != null) name = r.toString(); } catch (Throwable ignored) {}
            try { Method m = desc.getClass().getMethod("getType"); Object r = m.invoke(desc); if (r != null) type = r.toString(); } catch (Throwable ignored) {}
            try { Method m = desc.getClass().getMethod("getValue"); Object r = m.invoke(desc); if (r != null) value = r.toString(); } catch (Throwable ignored) {}
            if ((value == null || value.isEmpty()) && desc != null) {
                try { value = desc.toString(); } catch (Throwable ignored) {}
            }
            if (name == null || name.isEmpty()) name = "unknown";
            if (type == null || type.isEmpty()) type = "unknown";
            if (value == null || value.isEmpty()) value = "unavailable";
            MutableSnapshotItem msi = new MutableSnapshotItem(name, type, value, "Field");

            List<Object> nested = tryGetPyChildren(desc);
            if (nested == null || nested.isEmpty()) nested = inspectDescriptorForChildren(desc);
            if (nested != null && !nested.isEmpty()) {
                for (Object c : nested) {
                    MutableSnapshotItem child = createSnapshotFromDescriptor(c);
                    msi.children.add(child);
                }
            }
            return msi;
        } catch (Throwable t) {
            LOG.debug("createSnapshotFromDescriptor failed: " + t.getMessage());
            return new MutableSnapshotItem("unknown", "unknown", "unavailable", "Field");
        }
    }

    // -------------------------
    // Exception processing
    // -------------------------
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
                        LOG.debug("Found __exception__ in PyCharm frame: " + name);
                        processPyCharmExceptionTuple(value, frame, callback);
                        break;
                    } else if (isExceptionCandidateSafe(name, value)) {
                        foundException = true;
                        LOG.debug("Found Java-style exception in frame: " + name);
                        processExceptionSafe(value, frame, callback);
                        break;
                    }
                }
                if (!foundException && isPyCharmEnvironment()) {
                    for (int i = 0; i < children.size(); i++) {
                        if ("__exception__".equals(children.getName(i))) {
                            LOG.debug("Fallback: Found __exception__ by name in PyCharm frame");
                            processPyCharmExceptionTuple(children.getValue(i), frame, callback);
                            return;
                        }
                    }
                }
                if (!foundException) {
                    LOG.debug("No exception detected; collecting snapshot instead.");
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

    private static void processPyCharmExceptionTuple(XValue exceptionTuple, XStackFrame frame, Consumer<ContextItem> callback) {
        final AtomicBoolean usedFallback = new AtomicBoolean(false);
        try {
            exceptionTuple.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList tupleChildren, boolean last) {
                    String type = null, message = null, stackTrace = null;
                    if (tupleChildren.size() == 3) {
                        LOG.debug("PyCharm exception tuple has expected size=3");
                        type = extractTypeString(tupleChildren.getValue(0));
                        message = extractExceptionMessage(tupleChildren.getValue(1));
                        stackTrace = extractTraceback(tupleChildren.getValue(2));
                    } else {
                        LOG.warn("PyCharm exception tuple has unexpected size=" + tupleChildren.size() + "; will fallback.");
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

                    ExceptionDetail detail = new ExceptionDetail(message, type, stackTrace,
                            frame.getSourcePosition() != null ? frame.getSourcePosition().getFile().getPath() : "unknown",
                            frame.getSourcePosition() != null ? frame.getSourcePosition().getLine() : -1);
                    INSTANCE.latestException = detail;
                    callback.accept(new ContextItem(detail, true, ContextItem.Type.EXCEPTION));
                }
                @Override public void tooManyChildren(int remaining) {}
                @Override public void setAlreadySorted(boolean alreadySorted) {}
                @Override public void setErrorMessage(@NotNull String errorMessage) {}
                @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) {}
                @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
            });
        } catch (Throwable t) {
            LOG.warn("processPyCharmExceptionTuple failed: " + t.getMessage());
            try {
                Object py = tryGetPyDebugValue(exceptionTuple);
                String type = py != null ? tryGetPyType(py) : "unknown";
                String message = py != null ? tryGetPyValue(py) : extractBaseMessageSafe(null, exceptionTuple);
                ExceptionDetail detail = new ExceptionDetail(message, type, null,
                        frame.getSourcePosition() != null ? frame.getSourcePosition().getFile().getPath() : "unknown",
                        frame.getSourcePosition() != null ? frame.getSourcePosition().getLine() : -1);
                INSTANCE.latestException = detail;
                callback.accept(new ContextItem(detail, true, ContextItem.Type.EXCEPTION));
            } catch (Throwable ignore) {
                LOG.warn("Final fallback in processPyCharmExceptionTuple failed; collecting snapshot.");
                collectSnapshot(frame, callback);
            }
        }
    }

    private static String extractTypeString(XValue value) {
        final String[] outType = {"unknown"};
        try {
            value.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    if (presentation.getType() != null) outType[0] = presentation.getType();
                }
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String typeStr, @NotNull String value, boolean hasChildren) {
                    if (typeStr != null && typeStr.toLowerCase().contains("exception")) outType[0] = typeStr;
                }
                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
            }, XValuePlace.TREE);
        } catch (Throwable ignored) {}
        return outType[0];
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
                                @Override public void setPresentation(@Nullable Icon icon, @NotNull String typeStr, @NotNull String value, boolean hasChildren) {
                                    sb.append(value);
                                }
                                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
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
                                @Override public void setPresentation(@Nullable Icon icon, @NotNull String typeStr, @NotNull String value, boolean hasChildren) {
                                    sb.append(value).append("\n");
                                }
                                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
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
            } catch (Throwable t) { /* ignore */ }
            return nameSuggests || typeSuggests || descriptorSuggests;
        } catch (Throwable t) {
            LOG.debug("isExceptionCandidateSafe error: " + t.getMessage());
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
            LOG.warn("processExceptionSafe error: " + t.getMessage());
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
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String typeStr, @NotNull String value, boolean hasChildren) {
                    if (!"Collecting data...".equals(value)) {
                        state.setDetailMessage(value);
                        if (state.isComplete()) completeExceptionState(state, callback);
                    }
                }
                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
            }, XValuePlace.TREE);
        } catch (Throwable t) { LOG.debug("processDetailMessage failed: " + t.getMessage()); }
    }

    private static void processStackTrace(XValue fieldValue, ExceptionState state, Consumer<ContextItem> callback) {
        try {
            fieldValue.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    state.setStackTrace(renderPresentationText(presentation));
                    if (state.isComplete()) completeExceptionState(state, callback);
                }
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String typeStr, @NotNull String value, boolean hasChildren) {
                    state.setStackTrace(value);
                    if (state.isComplete()) completeExceptionState(state, callback);
                }
                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
            }, XValuePlace.TREE);
        } catch (Throwable t) { LOG.debug("processStackTrace failed: " + t.getMessage()); }
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
        } catch (Throwable t) { LOG.debug("renderPresentationText failed: " + t.getMessage()); }
        return sb.toString();
    }

    private static void completeExceptionState(ExceptionState state, Consumer<ContextItem> callback) {
        try {
            ExceptionDetail detail = state.buildExceptionDetail();
            INSTANCE.latestException = detail;
            callback.accept(new ContextItem(detail, true, ContextItem.Type.EXCEPTION));
        } catch (Throwable t) { LOG.warn("completeExceptionState failed: " + t.getMessage()); }
    }

    private static String getExceptionType(XValue value) {
        final String[] outType = {"unknown"};
        try {
            value.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    String typeStr = presentation.getType();
                    if (typeStr != null && typeStr.toLowerCase().contains("exception")) outType[0] = typeStr;
                }
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String typeStr, @NotNull String value, boolean hasChildren) {
                    if (typeStr != null && typeStr.toLowerCase().contains("exception")) outType[0] = typeStr;
                }
                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
            }, XValuePlace.TREE);
        } catch (Throwable t) { LOG.debug("getExceptionType failed: " + t.getMessage()); }
        return outType[0];
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
                    @Override public void setPresentation(@Nullable Icon icon, @NotNull String typeStr, @NotNull String value, boolean hasChildren) {
                        rendered[0] = value;
                    }
                    @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                }, XValuePlace.TREE);
            } catch (Throwable t) { LOG.debug("extractBaseMessageSafe presentation render error: " + t.getMessage()); }
            return rendered[0];
        } catch (Throwable t) {
            LOG.warn("extractBaseMessageSafe overall error: " + t.getMessage());
            return "null";
        }
    }

    // -------------------------
    // PSI helper: read-action safe, reflective Python/Java handling
    // -------------------------
    @Nullable
    private static String extractEnclosingFunctionReflective(@NotNull Project project, @NotNull VirtualFile file, int line) {
        final AtomicReference<String> out = new AtomicReference<>(null);

        Runnable readTask = () -> {
            try {
                Document document = FileDocumentManager.getInstance().getDocument(file);
                if (document == null || line >= document.getLineCount()) { out.set(null); return; }

                // Resolve PsiManager reflectively
                Object psiManager;
                try {
                    Class<?> psiManagerClass = Class.forName("com.intellij.psi.PsiManager");
                    Method getInstance = psiManagerClass.getMethod("getInstance", Project.class);
                    psiManager = getInstance.invoke(null, project);
                } catch (ClassNotFoundException cnfe) {
                    out.set(null);
                    return;
                }

                Object psiFile = psiManager.getClass().getMethod("findFile", VirtualFile.class).invoke(psiManager, file);
                if (psiFile == null) { out.set(null); return; }

                int offset = document.getLineStartOffset(line);
                Class<?> psiFileClass = Class.forName("com.intellij.psi.PsiFile");
                Object elementAt = psiFileClass.getMethod("findElementAt", int.class).invoke(psiFile, offset);
                if (elementAt == null) { out.set(null); return; }

                Class<?> psiTreeUtilClass = Class.forName("com.intellij.psi.util.PsiTreeUtil");

                // 1) Try Python PyFunction reflectively
                try {
                    Class<?> pyFunctionClass = Class.forName("com.jetbrains.python.psi.PyFunction");
                    Object functionElement = psiTreeUtilClass
                            .getMethod("getParentOfType", Class.forName("com.intellij.psi.PsiElement"), Class.class)
                            .invoke(null, elementAt, pyFunctionClass);
                    if (functionElement != null) {
                        String fullText = (String) functionElement.getClass().getMethod("getText").invoke(functionElement);
                        if (fullText != null) {
                            String[] lines = fullText.split("\n");
                            int prefix = Constants.ENCLOSING_FUNCTION_PREFIX_LINES, suffix = Constants.ENCLOSING_FUNCTION_SUFFIX_LINES;
                            if (lines.length <= prefix + suffix) { out.set(fullText); return; }
                            try {
                                Object textRange = functionElement.getClass().getMethod("getTextRange").invoke(functionElement);
                                int functionStartOffset = (int) textRange.getClass().getMethod("getStartOffset").invoke(textRange);
                                int functionStartLine = document.getLineNumber(functionStartOffset);
                                int targetLineInFunction = Math.max(0, line - functionStartLine);
                                int startLine = Math.max(targetLineInFunction - prefix, 0);
                                int endLine = Math.min(targetLineInFunction + suffix, lines.length - 1);
                                StringBuilder clipped = new StringBuilder();
                                for (int i = startLine; i <= endLine; i++) clipped.append(lines[i]).append("\n");
                                out.set(clipped.toString().trim());
                                return;
                            } catch (Throwable ignoreRange) {
                                out.set(fullText.length() > 2000 ? fullText.substring(0, 2000) + "..." : fullText);
                                return;
                            }
                        }
                    }
                } catch (ClassNotFoundException ignored) {
                    // Not a PyCharm environment or Python plugin not loaded - continue to fallback.
                }

                // 2) Fallback to Java PsiMethod reflectively
                try {
                    Class<?> psiElementClass = Class.forName("com.intellij.psi.PsiElement");
                    Class<?> psiMethodClass = Class.forName("com.intellij.psi.PsiMethod");
                    Object functionElement = psiTreeUtilClass.getMethod("getParentOfType", psiElementClass, Class.class)
                            .invoke(null, elementAt, psiMethodClass);
                    if (functionElement != null) {
                        String fullText = (String) functionElement.getClass().getMethod("getText").invoke(functionElement);
                        if (fullText == null) { out.set(null); return; }

                        String[] lines = fullText.split("\n");
                        int prefix = Constants.ENCLOSING_FUNCTION_PREFIX_LINES, suffix = Constants.ENCLOSING_FUNCTION_SUFFIX_LINES;
                        if (lines.length <= prefix + suffix) { out.set(fullText); return; }

                        Object textRange = functionElement.getClass().getMethod("getTextRange").invoke(functionElement);
                        int functionStartOffset = (int) textRange.getClass().getMethod("getStartOffset").invoke(textRange);
                        int functionStartLine = document.getLineNumber(functionStartOffset);
                        int targetLineInFunction = Math.max(0, line - functionStartLine);
                        int startLine = Math.max(targetLineInFunction - prefix, 0);
                        int endLine = Math.min(targetLineInFunction + suffix, lines.length - 1);
                        StringBuilder clipped = new StringBuilder();
                        for (int i = startLine; i <= endLine; i++) clipped.append(lines[i]).append("\n");
                        out.set(clipped.toString().trim());
                        return;
                    }
                } catch (ClassNotFoundException ignored) {
                    // Java PSI not available either
                }

                out.set(null);
            } catch (Throwable t) {
                LOG.warn("extractEnclosingFunctionReflective(readAction) error: " + t.getMessage());
                out.set(null);
            }
        };

        try {
            ApplicationManager.getApplication().runReadAction(readTask);
        } catch (Throwable t) {
            try {
                String result = ReadAction.compute(() -> {
                    readTask.run();
                    return out.get();
                });
                return result;
            } catch (Throwable ex) {
                LOG.warn("extractEnclosingFunctionReflective: read action fallback failed: " + ex.getMessage());
                return out.get();
            }
        }

        return out.get();
    }

    // -------------------------
    // Misc helpers: dumping / safe stringification
    // -------------------------
    private static void dumpDescriptorToFile(Object desc, String varName) {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String fname = System.getProperty("user.home") + "/aipp_debug_dump_" + ts + ".log";
            try (PrintWriter pw = new PrintWriter(new FileWriter(fname, true))) {
                pw.println("===== AIPP Debug Dump =====");
                pw.println("Timestamp: " + new Date());
                pw.println("VarName: " + varName);
                if (desc == null) {
                    pw.println("Descriptor: null");
                } else {
                    pw.println("Descriptor class: " + desc.getClass().getName());
                    pw.println("Descriptor.toString():");
                    try { pw.println(String.valueOf(desc.toString())); } catch (Throwable t) { pw.println("toString() failed: " + t.getMessage()); }
                    pw.println("--- Methods (zero-arg) and sample returns ---");
                    Method[] methods = desc.getClass().getMethods();
                    for (Method m : methods) {
                        if (m.getParameterCount() == 0) {
                            String mname = m.getName();
                            if (mname.equals("getClass")) continue;
                            try {
                                Object res;
                                try { res = m.invoke(desc); } catch (Throwable it) { res = ("INVOCATION_FAILED: " + it.getMessage()); }
                                if (res == null) {
                                    pw.println(mname + " -> null");
                                } else {
                                    String rcls = res.getClass().getName();
                                    String rstr;
                                    try { rstr = String.valueOf(res.toString()); } catch (Throwable t) { rstr = "toString failed: " + t.getMessage(); }
                                    if (rstr.length() > 1000) rstr = rstr.substring(0, 1000) + "...(truncated)";
                                    pw.println(mname + " -> (" + rcls + ") " + rstr);
                                }
                            } catch (Throwable inv) {
                                pw.println(mname + " -> invocation exception: " + inv.getMessage());
                            }
                        }
                    }
                    pw.println("--- Fields (declared) ---");
                    Field[] fields = desc.getClass().getDeclaredFields();
                    for (Field f : fields) {
                        try {
                            f.setAccessible(true);
                            Object fv = f.get(desc);
                            String fval = (fv == null) ? "null" : fv.getClass().getName() + ":" + safeToString(fv, 200);
                            pw.println(f.getName() + " -> " + fval);
                        } catch (Throwable tt) {
                            pw.println(f.getName() + " -> (access failed: " + tt.getMessage() + ")");
                        }
                    }
                }
                pw.flush();
            }
            LOG.warn("Descriptor dump written: " + fname);
        } catch (Throwable t) {
            LOG.warn("dumpDescriptorToFile failed: " + t.getMessage());
        }
    }

    private static String safeClassName(Object o) { return o == null ? "null" : o.getClass().getName(); }
    private static String safeToString(Object o, int max) {
        try {
            String s = String.valueOf(o);
            if (s.length() > max) return s.substring(0, max) + "...";
            return s;
        } catch (Throwable t) { return "(toString failed)"; }
    }
}
