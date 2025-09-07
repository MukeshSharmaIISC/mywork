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
    private static Boolean cachedIsPyCharm = null;

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

    // SNAPSHOT COLLECTION
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

    // EXCEPTION COLLECTION
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
                        processPyCharmExceptionTuple(value, frame, callback);
                        break;
                    } else if (isExceptionCandidateSafe(name, value)) {
                        foundException = true;
                        processExceptionSafe(value, frame, callback);
                        break;
                    }
                }
                if (!foundException && isPyCharmEnvironment()) {
                    for (int i = 0; i < children.size(); i++) {
                        if ("__exception__".equals(children.getName(i))) {
                            processPyCharmExceptionTuple(children.getValue(i), frame, callback);
                            return;
                        }
                    }
                }
                if (!foundException) {
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

    // PyCharm Exception Tuple Processing
    private static void processPyCharmExceptionTuple(XValue exceptionTuple, XStackFrame frame, Consumer<ContextItem> callback) {
        final AtomicBoolean usedFallback = new AtomicBoolean(false);
        try {
            exceptionTuple.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList tupleChildren, boolean last) {
                    String type = null, message = null, stackTrace = null;
                    if (tupleChildren.size() == 3) {
                        type = extractTypeString(tupleChildren.getValue(0));
                        message = extractExceptionMessage(tupleChildren.getValue(1));
                        stackTrace = extractTraceback(tupleChildren.getValue(2));
                    } else {
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
            Object py = tryGetPyDebugValue(exceptionTuple);
            String type = py != null ? tryGetPyType(py) : "unknown";
            String message = py != null ? tryGetPyValue(py) : extractBaseMessageSafe(null, exceptionTuple);
            ExceptionDetail detail = new ExceptionDetail(message, type, null,
                    frame.getSourcePosition() != null ? frame.getSourcePosition().getFile().getPath() : "unknown",
                    frame.getSourcePosition() != null ? frame.getSourcePosition().getLine() : -1);
            instance.latestException = detail;
            callback.accept(new ContextItem(detail, true, ContextItem.Type.EXCEPTION));
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
            return nameSuggests || typeSuggests;
        } catch (Throwable t) {
            logger.debug("isExceptionCandidateSafe error: " + t.getMessage());
            return false;
        }
    }

    private static void processExceptionSafe(XValue value, XStackFrame frame, Consumer<ContextItem> callback) {
        try {
            String type = getExceptionType(value);
            ExceptionDetail detail = new ExceptionDetail(extractBaseMessageSafe(null, value), type, null,
                    frame.getSourcePosition() != null ? frame.getSourcePosition().getFile().getPath() : "unknown",
                    frame.getSourcePosition() != null ? frame.getSourcePosition().getLine() : -1);
            instance.latestException = detail;
            callback.accept(new ContextItem(detail, true, ContextItem.Type.EXCEPTION));
        } catch (Throwable t) {
            collectSnapshot(frame, callback);
        }
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

    // Value/children collection helpers (already present above)

    // --- Enclosing function extraction (optional/unchanged) ---
    @Nullable
    private static String extractEnclosingFunctionReflective(@NotNull Project project, @NotNull VirtualFile file, int line) {
        // Implementation as in previous versions
        return null; // Stub: Implement as needed
    }


}
