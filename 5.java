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
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * DebugDataCollector
 *
 * Single-file collector for callstack, snapshot and exception extraction.
 * - Safe for PyCharm and IntelliJ (reflection fallbacks).
 * - Guards against runaway traversal via:
 *     - MAX_DEPTH_OF_NESTED_VARIABLES
 *     - MAX_CHILDREN_PER_NODE
 *     - MAX_TOTAL_NODES
 *     - MAX_SNAPSHOT_JSON_SIZE_BYTES (approximated via estimated size)
 * - Uses IdentityHashMap-based visited set to detect cycles.
 *
 * NOTE: relies on external types (SnapshotItem, MutableSnapshotItem, StackItem, ExceptionDetail,
 * ContextItem, ExceptionState, Constants).
 */
public class DebugDataCollector {

    private static final Logger logger = Logger.getInstance(DebugDataCollector.class);

    private static final DebugDataCollector instance = new DebugDataCollector();

    // Latest cached results
    private final List<SnapshotItem> latestSnapshot = new ArrayList<>();
    private final List<StackItem> latestStack = new ArrayList<>();
    private ExceptionDetail latestException = null;

    // Cached detection of PyCharm runtime classes
    private static Boolean cachedIsPyCharm = null;

    // Estimated overhead per node for quick size heuristics
    private static final int ESTIMATED_NODE_OVERHEAD = 40;

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

    // -------------------------
    // Environment helpers
    // -------------------------

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

    // -------------------------
    // Call stack collection
    // -------------------------

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

                // Trim by JSON size if needed (final safeguard)
                trimToJsonSize(stackItems, Constants.MAX_CALLSTACK_JSON_SIZE_BYTES);

                instance.latestStack.clear();
                instance.latestStack.addAll(stackItems);
                callback.accept(new ContextItem(stackItems, true, ContextItem.Type.STACK));
            }

            @Override
            public void errorOccurred(@NotNull String errorMessage) {
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

    // -------------------------
    // Snapshot collection (entry)
    // -------------------------

    public static void collectSnapshot(XStackFrame currentStackFrame, Consumer<ContextItem> callback) {
        List<MutableSnapshotItem> snapshotItems = new ArrayList<>();
        AtomicInteger debuggerCalls = new AtomicInteger(0);
        AtomicBoolean limitReached = new AtomicBoolean(false);

        // shared traversal guards
        final AtomicInteger totalNodes = new AtomicInteger(0);
        final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        final AtomicInteger estimatedSize = new AtomicInteger(0);

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
                        if (limitReached.get() || debuggerCalls.get() >= Constants.MAX_CALLS_TO_DEBUGGER
                                || estimatedSize.get() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES) {
                            limitReached.set(true);
                            complete();
                            return;
                        }

                        String varName = children.getName(i);
                        XValue childValue = children.getValue(i);

                        MutableSnapshotItem mutableItem = new MutableSnapshotItem(varName, "unknown", "unavailable", "Local");
                        snapshotItems.add(mutableItem);
                        debuggerCalls.incrementAndGet();

                        // account for node overhead and name length
                        estimatedSize.addAndGet(ESTIMATED_NODE_OVERHEAD + (varName != null ? varName.length() : 0));

                        if (isPyCharmEnvironment()) {
                            logger.debug("Trying PyCharm-specific collection for variable: " + varName);
                            collectPyCharmValueAndChildren(currentStackFrame, childValue, mutableItem, 0, visited, totalNodes, estimatedSize, () -> {
                                if (pending.decrementAndGet() == 0) complete();
                            });
                        } else {
                            collectValueAndChildren(childValue, mutableItem, 0, visited, totalNodes, estimatedSize, () -> {
                                if (pending.decrementAndGet() == 0) complete();
                            });
                        }
                    }
                }

                private void complete() {
                    List<SnapshotItem> result = new ArrayList<>();
                    for (MutableSnapshotItem item : snapshotItems) result.add(item.toSnapshotItem());
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

    // -------------------------
    // Native (XValue) recursive collector
    // -------------------------
    private static void collectValueAndChildren(XValue value, MutableSnapshotItem parent, int currentDepth,
                                                Set<Object> visited, AtomicInteger totalNodes, AtomicInteger estimatedSize,
                                                Runnable onComplete) {
        if (currentDepth >= Constants.MAX_DEPTH_OF_NESTED_VARIABLES) { onComplete.run(); return; }

        if (estimatedSize.get() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES) {
            parent.value = "[truncated: size]";
            onComplete.run();
            return;
        }

        if (totalNodes.incrementAndGet() > Constants.MAX_TOTAL_NODES) {
            parent.value = "[truncated: max nodes]";
            onComplete.run();
            return;
        }

        Object identityKey = value;
        if (identityKey == null) { onComplete.run(); return; }
        if (!visited.add(identityKey)) { parent.value = "[cyclic]"; onComplete.run(); return; }

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
                            estimatedSize.addAndGet(rendered.length());
                        }
                    } catch (Exception e) {
                        parent.value = "Value not available";
                    } finally {
                        if (estimatedSize.get() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES) {
                            parent.value = "[truncated: size]";
                            finishOnce.run();
                            return;
                        }
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
                                        if (estimatedSize.get() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES) {
                                            if (pending.decrementAndGet() == 0) finishOnce.run();
                                            continue;
                                        }
                                        String childName = children.getName(i);
                                        XValue childValue = children.getValue(i);
                                        MutableSnapshotItem childItem = new MutableSnapshotItem(childName, "unknown", "unavailable", "Field");
                                        parent.children.add(childItem);
                                        estimatedSize.addAndGet(ESTIMATED_NODE_OVERHEAD + (childName != null ? childName.length() : 0));
                                        try {
                                            collectValueAndChildren(childValue, childItem, currentDepth + 1, visited, totalNodes, estimatedSize, () -> {
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
                        if (valueStr != null && !valueStr.isEmpty()) {
                            parent.value = valueStr;
                            estimatedSize.addAndGet(valueStr.length());
                        }
                    } catch (Throwable t) {
                        parent.value = "Value not available";
                    }

                    if (estimatedSize.get() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES) {
                        parent.value = "[truncated: size]";
                        finishOnce.run();
                        return;
                    }

                    if (hasChildren) {
                        xValueLocal.computeChildren(new XCompositeNode() {
                            @Override
                            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                                int childCount = Math.min(children.size(), Constants.MAX_CHILDREN_PER_NODE);
                                if (childCount == 0) { finishOnce.run(); return; }
                                AtomicInteger pending = new AtomicInteger(childCount);
                                for (int i = 0; i < childCount; i++) {
                                    if (estimatedSize.get() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES) {
                                        if (pending.decrementAndGet() == 0) finishOnce.run();
                                        continue;
                                    }
                                    String childName = children.getName(i);
                                    XValue childValue = children.getValue(i);
                                    MutableSnapshotItem childItem = new MutableSnapshotItem(childName, "unknown", "unavailable", "Field");
                                    parent.children.add(childItem);
                                    estimatedSize.addAndGet(ESTIMATED_NODE_OVERHEAD + (childName != null ? childName.length() : 0));
                                    try {
                                        collectValueAndChildren(childValue, childItem, currentDepth + 1, visited, totalNodes, estimatedSize, () -> {
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
            logger.warn("collectValueAndChildren failed: " + t.getMessage());
            parent.value = "Value not available";
            onComplete.run();
        }
    }

    // -------------------------
    // PyCharm-specific recursive collector
    // -------------------------
    private static void collectPyCharmValueAndChildren(XStackFrame frame, XValue value, MutableSnapshotItem parent,
                                                      int currentDepth, Set<Object> visited, AtomicInteger totalNodes,
                                                      AtomicInteger estimatedSize, Runnable onComplete) {
        if (currentDepth >= Constants.MAX_DEPTH_OF_NESTED_VARIABLES) { onComplete.run(); return; }

        if (estimatedSize.get() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES) {
            parent.value = "[truncated: size]";
            onComplete.run();
            return;
        }

        if (totalNodes.incrementAndGet() > Constants.MAX_TOTAL_NODES) {
            parent.value = "[truncated: max nodes]";
            onComplete.run();
            return;
        }

        Object identityKey = value;
        if (identityKey == null) { onComplete.run(); return; }
        if (!visited.add(identityKey)) { parent.value = "[cyclic]"; onComplete.run(); return; }

        final XValue xValueLocal = value;
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Runnable finishOnce = () -> { if (finished.compareAndSet(false, true)) onComplete.run(); };

        try {
            Object pyDebugValue = tryGetPyDebugValue(value);
            boolean usedReflection = false;

            if (pyDebugValue != null) {
                try {
                    String name = tryGetPyName(pyDebugValue);
                    String val = tryGetPyValue(pyDebugValue);
                    String type = tryGetPyType(pyDebugValue);
                    if (name != null && !name.isEmpty()) {
                        parent.name = name;
                        estimatedSize.addAndGet(name.length());
                    }
                    if (val != null && !"unavailable".equals(val) && !val.isEmpty()) {
                        parent.value = val;
                        estimatedSize.addAndGet(val.length());
                    }
                    if (type != null && !type.isEmpty()) parent.type = type;
                    usedReflection = true;

                    List<Object> pyChildren = tryGetPyChildren(pyDebugValue);
                    if (pyChildren != null && !pyChildren.isEmpty()) {
                        int childCount = Math.min(pyChildren.size(), Constants.MAX_CHILDREN_PER_NODE);
                        AtomicInteger pending = new AtomicInteger(childCount);
                        for (int i = 0; i < childCount; i++) {
                            if (estimatedSize.get() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES) {
                                if (pending.decrementAndGet() == 0) finishOnce.run();
                                continue;
                            }
                            Object child = pyChildren.get(i);
                            try {
                                if (child instanceof XValue) {
                                    MutableSnapshotItem childItem = new MutableSnapshotItem("unknown", "unknown", "unavailable", "Field");
                                    parent.children.add(childItem);
                                    estimatedSize.addAndGet(ESTIMATED_NODE_OVERHEAD);
                                    collectPyCharmValueAndChildren(frame, (XValue) child, childItem, currentDepth + 1, visited, totalNodes, estimatedSize, () -> {
                                        if (pending.decrementAndGet() == 0) finishOnce.run();
                                    });
                                } else {
                                    MutableSnapshotItem childItem = createSnapshotFromDescriptor(child);
                                    parent.children.add(childItem);
                                    estimatedSize.addAndGet(ESTIMATED_NODE_OVERHEAD);
                                    if (pending.decrementAndGet() == 0) finishOnce.run();
                                }
                            } catch (Throwable inner) {
                                if (pending.decrementAndGet() == 0) finishOnce.run();
                            }
                        }
                        return;
                    }
                } catch (Throwable ignore) {
                    // fallback to presentation/children traversal below
                }
            }

            // Fallback to the XValue presentation/children path (same safe behavior as native path)
            xValueLocal.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    try {
                        String pres = renderPresentationText(presentation);
                        if (pres != null && !pres.isEmpty() && !"Collecting data...".equals(pres)) {
                            parent.value = pres;
                            estimatedSize.addAndGet(pres.length());
                        }
                        if (presentation.getType() != null) parent.type = presentation.getType();
                    } catch (Throwable t) {
                        // swallow presentation errors - fallback to onComplete
                    }

                    if (estimatedSize.get() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES) {
                        parent.value = "[truncated: size]";
                        finishOnce.run();
                        return;
                    }

                    if (hasChildren) {
                        xValueLocal.computeChildren(new XCompositeNode() {
                            @Override
                            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                                int childCount = Math.min(children.size(), Constants.MAX_CHILDREN_PER_NODE);
                                if (childCount == 0) { finishOnce.run(); return; }
                                AtomicInteger pending = new AtomicInteger(childCount);
                                for (int i = 0; i < childCount; i++) {
                                    if (estimatedSize.get() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES) {
                                        if (pending.decrementAndGet() == 0) finishOnce.run();
                                        continue;
                                    }
                                    String childName = children.getName(i);
                                    XValue childValue = children.getValue(i);
                                    MutableSnapshotItem childItem = new MutableSnapshotItem(childName != null ? childName : "unknown", "unknown", "unavailable", "Field");
                                    parent.children.add(childItem);
                                    estimatedSize.addAndGet(ESTIMATED_NODE_OVERHEAD + (childName != null ? childName.length() : 0));
                                    try {
                                        collectPyCharmValueAndChildren(frame, childValue, childItem, currentDepth + 1, visited, totalNodes, estimatedSize, () -> {
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
                        if (valueStr != null && !valueStr.isEmpty()) {
                            parent.value = valueStr;
                            estimatedSize.addAndGet(valueStr.length());
                        }
                        if (typeStr != null && !typeStr.isEmpty()) parent.type = typeStr;
                    } catch (Throwable t) {}
                    if (estimatedSize.get() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES) { parent.value = "[truncated: size]"; finishOnce.run(); return; }

                    if (hasChildren) {
                        xValueLocal.computeChildren(new XCompositeNode() {
                            @Override
                            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                                int childCount = Math.min(children.size(), Constants.MAX_CHILDREN_PER_NODE);
                                if (childCount == 0) { finishOnce.run(); return; }
                                AtomicInteger pending = new AtomicInteger(childCount);
                                for (int i = 0; i < childCount; i++) {
                                    if (estimatedSize.get() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES) {
                                        if (pending.decrementAndGet() == 0) finishOnce.run();
                                        continue;
                                    }
                                    String childName = children.getName(i);
                                    XValue childValue = children.getValue(i);
                                    MutableSnapshotItem childItem = new MutableSnapshotItem(childName, "unknown", "unavailable", "Field");
                                    parent.children.add(childItem);
                                    estimatedSize.addAndGet(ESTIMATED_NODE_OVERHEAD + (childName != null ? childName.length() : 0));
                                    try {
                                        collectPyCharmValueAndChildren(frame, childValue, childItem, currentDepth + 1, visited, totalNodes, estimatedSize, () -> {
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
            logger.warn("collectPyCharmValueAndChildren failed: " + t.getMessage());
            parent.value = "Value not available";
            onComplete.run();
        }
    }

    // -------------------------
    // PyCharm descriptor helpers / reflection utilities
    // -------------------------
    private static List<Object> tryGetPyChildren(Object pyDebugValue) {
        try {
            if (pyDebugValue == null) return Collections.emptyList();

            // Direct getChildren() on descriptor
            try {
                Method getChildren = pyDebugValue.getClass().getMethod("getChildren");
                Object res = getChildren.invoke(pyDebugValue);
                List<Object> out = listFromPossibleCollection(res);
                if (!out.isEmpty()) return out;
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
                        if (!out.isEmpty()) return out;
                    } catch (NoSuchMethodException ignored) {}
                }
            } catch (NoSuchMethodException ignored) {}

            // fallback: inspect fields/methods heuristically
            List<Object> inspected = inspectDescriptorForChildren(pyDebugValue);
            if (!inspected.isEmpty()) return inspected;
        } catch (Throwable t) {
            logger.debug("tryGetPyChildren failed: " + t.getMessage());
        }
        return Collections.emptyList();
    }

    // Inspect descriptor fields/methods heuristically to find collections of children
    private static List<Object> inspectDescriptorForChildren(Object descriptor) {
        if (descriptor == null) return Collections.emptyList();
        List<Object> out = new ArrayList<>();
        try {
            Field[] fields = descriptor.getClass().getDeclaredFields();
            for (Field f : fields) {
                try {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(descriptor);
                    List<Object> list = listFromPossibleCollection(val);
                    if (!list.isEmpty()) out.addAll(list);
                } catch (Throwable ignored) {}
            }

            Method[] methods = descriptor.getClass().getMethods();
            for (Method m : methods) {
                try {
                    if (m.getParameterCount() != 0) continue;
                    String name = m.getName().toLowerCase();
                    if (!(name.contains("children") || name.contains("items") || name.contains("values") || name.contains("elements"))) continue;
                    Object res = m.invoke(descriptor);
                    List<Object> list = listFromPossibleCollection(res);
                    if (!list.isEmpty()) out.addAll(list);
                } catch (Throwable ignored) {}
            }

            // Additional fallback methods
            for (Method m : methods) {
                try {
                    if (m.getParameterCount() != 0) continue;
                    String name = m.getName().toLowerCase();
                    if (name.contains("toarray") || name.contains("aslist")) {
                        Object res = m.invoke(descriptor);
                        List<Object> list = listFromPossibleCollection(res);
                        if (!list.isEmpty()) out.addAll(list);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            logger.debug("inspectDescriptorForChildren failed: " + t.getMessage());
        }
        return out;
    }

    // Convert arrays/collections/maps/iterables into a List<Object>
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
            // Single object (not a collection)
            out.add(obj);
            return out;
        } catch (Throwable t) {
            logger.debug("listFromPossibleCollection failed: " + t.getMessage());
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

            // try to populate nested children (bounded)
            List<Object> nested = tryGetPyChildren(desc);
            if (nested == null || nested.isEmpty()) nested = inspectDescriptorForChildren(desc);
            if (nested != null && !nested.isEmpty()) {
                int count = Math.min(nested.size(), Constants.MAX_CHILDREN_PER_NODE);
                for (int i = 0; i < count; i++) {
                    MutableSnapshotItem child = createSnapshotFromDescriptor(nested.get(i));
                    msi.children.add(child);
                }
            }
            return msi;
        } catch (Throwable t) {
            logger.debug("createSnapshotFromDescriptor failed: " + t.getMessage());
            return new MutableSnapshotItem("unknown", "unknown", "unavailable", "Field");
        }
    }

    private static Object tryGetPyDebugValue(XValue xValue) {
        try {
            if (xValue == null) return null;

            // First: NodeDescriptorProvider direct interface
            try {
                if (xValue instanceof com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) {
                    Object desc = ((com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) xValue).getDescriptor();
                    if (desc != null) {
                        logger.debug("tryGetPyDebugValue: got descriptor via NodeDescriptorProvider: " + safeClassName(desc));
                        return desc;
                    }
                }
            } catch (Throwable ignored) {}

            // Second: reflection-based getDescriptor()
            try {
                Class<?> nodeProviderClass = Class.forName("com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider");
                if (nodeProviderClass.isInstance(xValue)) {
                    Method gd = nodeProviderClass.getMethod("getDescriptor");
                    Object desc = gd.invoke(xValue);
                    if (desc != null) {
                        logger.debug("tryGetPyDebugValue: got descriptor via reflection getDescriptor(): " + safeClassName(desc));
                        return desc;
                    }
                }
            } catch (Throwable ignored) {}

            // Third: xValue itself might be a PyDebugValue
            if (isPyDebugValue(xValue)) {
                logger.debug("tryGetPyDebugValue: xValue is PyDebugValue instance: " + safeClassName(xValue));
                return xValue;
            }
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

    // Optional: write descriptor diagnostics to disk (useful when debugging descriptor shapes)
    @SuppressWarnings("unused")
    private static void dumpDescriptorToFile(Object desc, String varName) {
        try {
            String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            String fname = System.getProperty("user.home") + "/aipp_debug_dump_" + ts + ".log";
            try (java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(fname, true))) {
                pw.println("===== AIPP Debug Dump =====");
                pw.println("Timestamp: " + new Date());
                pw.println("VarName: " + varName);
                if (desc == null) {
                    pw.println("Descriptor: null");
                } else {
                    pw.println("Descriptor class: " + desc.getClass().getName());
                    try { pw.println(String.valueOf(desc.toString())); } catch (Throwable t) { pw.println("toString() failed: " + t.getMessage()); }
                    pw.println("--- Methods (zero-arg) and sample returns ---");
                    Method[] methods = desc.getClass().getMethods();
                    for (Method m : methods) {
                        if (m.getParameterCount() == 0) {
                            try {
                                Object res = null;
                                try { res = m.invoke(desc); } catch (Throwable it) { res = ("INVOCATION_FAILED: " + it.getMessage()); }
                                pw.println(m.getName() + " -> " + (res == null ? "null" : res.getClass().getName() + ":" + safeToString(res, 200)));
                            } catch (Throwable inv) {
                                pw.println(m.getName() + " -> invocation exception: " + inv.getMessage());
                            }
                        }
                    }
                    pw.println("--- Fields (declared) ---");
                    Field[] fields = desc.getClass().getDeclaredFields();
                    for (Field f : fields) {
                        try {
                            f.setAccessible(true);
                            Object fv = f.get(desc);
                            pw.println(f.getName() + " -> " + (fv == null ? "null" : fv.getClass().getName() + ":" + safeToString(fv, 200)));
                        } catch (Throwable tt) {
                            pw.println(f.getName() + " -> (access failed: " + tt.getMessage() + ")");
                        }
                    }
                }
                pw.flush();
            }
            logger.warn("Descriptor dump written: " + fname);
        } catch (Throwable t) {
            logger.warn("dumpDescriptorToFile failed: " + t.getMessage());
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

    // -------------------------
    // Exception handling
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

                // Fallback: explicit __exception__ search on PyCharm
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

    private static void processPyCharmExceptionTuple(XValue exceptionTuple, XStackFrame frame, Consumer<ContextItem> callback) {
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
        final String[] outType = {"unknown"};
        try {
            value.computePresentation(new XValueNode() {
                @Override public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
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
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String typeStr, @NotNull String value, boolean hasChildren) {
                    if (!"Collecting data...".equals(value)) {
                        state.setDetailMessage(value);
                        if (state.isComplete()) completeExceptionState(state, callback);
                    }
                }
                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
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
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String typeStr, @NotNull String value, boolean hasChildren) {
                    state.setStackTrace(value);
                    if (state.isComplete()) completeExceptionState(state, callback);
                }
                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
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
        } catch (Throwable t) { logger.debug("getExceptionType failed: " + t.getMessage()); }
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
            } catch (Throwable t) { logger.debug("extractBaseMessageSafe presentation render error: " + t.getMessage()); }
            return rendered[0];
        } catch (Throwable t) {
            logger.warn("extractBaseMessageSafe overall error: " + t.getMessage());
            return "null";
        }
    }

    // -------------------------
    // PSI-based enclosing function extraction (reflective)
    // -------------------------
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

            // compute clipped window centered on the target line inside function
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
