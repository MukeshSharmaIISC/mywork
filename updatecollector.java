import com.google.gson.Gson;
// removed: import com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider;
// removed: import com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XSourcePosition;
import com.intellij.xdebugger.frame.*;
import com.intellij.xdebugger.frame.presentation.XValuePresentation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.dell.Constants;

import javax.swing.*;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.function.Consumer;

public class DebugDataCollector {

    private static final Logger logger = Logger.getInstance(DebugDataCollector.class);

    private static final DebugDataCollector instance = new DebugDataCollector();

    private final List<SnapshotItem> latestSnapshot = new ArrayList<>();

    private final List<StackItem> latestStack = new ArrayList<>();

    private ExceptionDetail latestException = null;

    private DebugDataCollector() {
        if (isPyCharmEnvironment()) {
            logger.debug("[DebugDataCollector] PyCharm debugger environment detected (PyDebugValue present).");
        } else {
            logger.debug("[DebugDataCollector] IntelliJ debugger environment detected (no PyDebugValue class).");
        }
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

    public static void collectStackItems(XDebugProcess debugProcess, Consumer<ContextItem> callback) {
        List<StackItem> stackItems = new ArrayList<>();
        XExecutionStack stack = debugProcess.getSession().getSuspendContext().getActiveExecutionStack();
        if (stack == null) {
            callback.accept(new ContextItem(stackItems, false, ContextItem.Type.STACK));
            return;
        }
        stack.computeStackFrames(0, new XExecutionStack.XStackFrameContainer() {
            @Override
            public void addStackFrames(@NotNull List<? extends XStackFrame> frames, boolean last) {
                for (XStackFrame frame : frames) {
                    if (stackItems.size() >= Constants.MAX_CALLSTACK_ITEMS) {
                        break;
                    }
                    XSourcePosition pos = frame.getSourcePosition();
                    if (pos != null) {
                        String file = pos.getFile().getPath();
                        int line = pos.getLine() + 1;
                        String language = pos.getFile().getExtension(),
                               functionText = extractEnclosingFunction(debugProcess.getSession().getProject(), pos.getFile(), pos.getLine());
                        stackItems.add(new StackItem(file, line, functionText != null ? functionText : "", language));
                    }
                }

                Gson gson = new Gson();
                while (!stackItems.isEmpty() &&
                        gson.toJson(stackItems).length() > Constants.MAX_CALLSTACK_JSON_SIZE_BYTES) {
                    stackItems.remove(stackItems.size() - 1);
                }
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

    private static void collectChildren(XValue value, MutableSnapshotItem parent, int currentDepth, Runnable onComplete) {
        if (currentDepth >= Constants.MAX_DEPTH_OF_NESTED_VARIABLES) {
            onComplete.run();
            return;
        }
        value.computeChildren(new XCompositeNode() {
            @Override
            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                AtomicInteger pending = new AtomicInteger(children.size());
                if (children.size() == 0) {
                    onComplete.run();
                    return;
                }
                for (int i = 0; i < children.size(); i++) {
                    String childName = children.getName(i);
                    XValue childValue = children.getValue(i);
                    MutableSnapshotItem childItem = new MutableSnapshotItem(childName, "unknown", "unavailable", "Field");
                    parent.children.add(childItem);

                    childValue.computePresentation(new XValueNode() {
                        @Override
                        public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                            try {
                                if (presentation.getType() != null) {
                                    childItem.type = presentation.getType();
                                }
                                StringBuilder sb = new StringBuilder();
                                presentation.renderValue(new XValuePresentation.XValueTextRenderer() {
                                    @Override
                                    public void renderValue(@Nullable String value) {
                                        sb.append(value);
                                    }
                                    @Override
                                    public void renderStringValue(@Nullable String value) {
                                        sb.append(value);
                                    }
                                    @Override
                                    public void renderNumericValue(@Nullable String value) {
                                        sb.append(value);
                                    }
                                    @Override
                                    public void renderKeywordValue(@Nullable String value) {
                                        sb.append(value);
                                    }
                                    @Override
                                    public void renderValue(@Nullable String value, @NotNull TextAttributesKey key) {
                                        sb.append(value);
                                    }
                                    @Override
                                    public void renderStringValue(@Nullable String value, @Nullable String ref, int unused) {
                                        sb.append(value);
                                    }
                                    @Override
                                    public void renderComment(@NotNull String comment) {}
                                    @Override
                                    public void renderSpecialSymbol(@NotNull String symbol) { sb.append(symbol); }
                                    @Override
                                    public void renderError(@NotNull String error) { sb.append(error); }
                                });

                                childItem.value = sb.toString();

                                if ((childItem.value == null || childItem.value.isEmpty()) && isPyCharmEnvironment()) {
                                    String pyRendered = tryReflectPyValueString(childValue);
                                    if (pyRendered != null) {
                                        childItem.value = pyRendered;
                                        logger.debug("[DebugDataCollector] Collected child via PyCharm reflection: " + childItem.name);
                                    }
                                }

                                if (hasChildren) {
                                    collectChildren(childValue, childItem, currentDepth + 1, () -> {
                                        if (pending.decrementAndGet() == 0) onComplete.run();
                                    });
                                } else {
                                    if (pending.decrementAndGet() == 0) onComplete.run();
                                }
                            } catch (Exception e) {
                                logger.warn("Error computing child value for " + childItem.name + ": " + e.getMessage());
                                childItem.value = "Value not available";
                                if (pending.decrementAndGet() == 0) onComplete.run();
                            }
                        }

                        @Override
                        public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}

                        @Override
                        public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {}
                    }, XValuePlace.TREE);
                }
            }

            @Override
            public void tooManyChildren(int remaining) { onComplete.run(); }
            @Override
            public void setAlreadySorted(boolean alreadySorted) {}
            @Override
            public void setErrorMessage(@NotNull String errorMessage) { onComplete.run(); }
            @Override
            public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { onComplete.run(); }
            @Override
            public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
        });
    }

    public static void collectSnapshot(XStackFrame currentStackFrame, Consumer<ContextItem> callback) {
        List<MutableSnapshotItem> snapshotItems = new ArrayList<>();
        AtomicInteger debuggerCalls = new AtomicInteger(0);
        AtomicBoolean limitReached = new AtomicBoolean(false);

        currentStackFrame.computeChildren(new XCompositeNode() {
            @Override
            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                AtomicInteger pending = new AtomicInteger(children.size());
                if (children.size() == 0) complete();
                for (int i = 0; i < children.size(); i++) {
                    if (limitReached.get()) {
                        complete();
                        return;
                    }

                    if (debuggerCalls.get() >= Constants.MAX_CALLS_TO_DEBUGGER) {
                        limitReached.set(true);
                        complete();
                        return;
                    }

                    String varName = children.getName(i);
                    XValue childValue = children.getValue(i);
                    String type = "unknown";
                    MutableSnapshotItem mutableItem = new MutableSnapshotItem(varName, type, "unavailable", "Local");
                    snapshotItems.add(mutableItem);

                    if (new Gson().toJson(snapshotItems).length() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES) {
                        logger.debug("Reached max snapshot size: " + Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES);
                        limitReached.set(true);
                        complete();
                        return;
                    }

                    debuggerCalls.incrementAndGet();
                    childValue.computePresentation(new XValueNode() {
                        @Override
                        public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                            try {
                                if (presentation.getType() != null) mutableItem.type = presentation.getType();
                                StringBuilder sb = new StringBuilder();
                                presentation.renderValue(new XValuePresentation.XValueTextRenderer() {
                                    @Override
                                    public void renderValue(@Nullable String value) { sb.append(value); }
                                    @Override
                                    public void renderStringValue(@Nullable String value) { sb.append(value); }
                                    @Override
                                    public void renderNumericValue(@Nullable String value) { sb.append(value); }
                                    @Override
                                    public void renderKeywordValue(@Nullable String value) { sb.append(value); }
                                    @Override
                                    public void renderValue(@Nullable String value, @NotNull TextAttributesKey key) { sb.append(value); }
                                    @Override
                                    public void renderStringValue(@Nullable String value, @Nullable String ref, int unused) { sb.append(value); }
                                    @Override
                                    public void renderComment(@NotNull String comment) { }
                                    @Override
                                    public void renderSpecialSymbol(@NotNull String symbol) { sb.append(symbol); }
                                    @Override
                                    public void renderError(@NotNull String error) { sb.append(error); }
                                });

                                mutableItem.value = sb.toString();

                                if ((mutableItem.value == null || mutableItem.value.isEmpty()) && isPyCharmEnvironment()) {
                                    String pyRendered = tryReflectPyValueString(childValue);
                                    if (pyRendered != null) {
                                        mutableItem.value = pyRendered;
                                        logger.debug("[DebugDataCollector] Collected var via PyCharm reflection: " + mutableItem.name);
                                    }
                                }
                            } catch (Exception e) {
                                logger.warn("Error computing value for " + mutableItem.name + ": " + e.getMessage());
                                mutableItem.value = "Value not available";
                                if (e.getMessage() != null && e.getMessage().contains("not yet calculated")) {
                                    mutableItem.value = "Calculating...";
                                }
                            } finally {
                                if (hasChildren) {
                                    collectChildren(childValue, mutableItem, 0, () -> {
                                        if (pending.decrementAndGet() == 0) complete();
                                    });
                                } else {
                                    if (pending.decrementAndGet() == 0) complete();
                                }
                            }
                        }

                        @Override
                        public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) { }

                        @Override
                        public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) { }
                    }, XValuePlace.TREE);
                }
            }

            @Override
            public void tooManyChildren(int remaining) { }

            @Override
            public void setAlreadySorted(boolean alreadySorted) { }

            @Override
            public void setErrorMessage(@NotNull String errorMessage) { }

            @Override
            public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { }

            @Override
            public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) { }

            private void complete() {
                List<SnapshotItem> result = new ArrayList<>();
                for (MutableSnapshotItem item : snapshotItems) result.add(item.toSnapshotItem());
                instance.latestSnapshot.clear();
                instance.latestSnapshot.addAll(result);
                callback.accept(new ContextItem(result, true, ContextItem.Type.SNAPSHOT));
            }
        });
    }

    private static class ExceptionState {
        private final AtomicReference<State> state = new AtomicReference<>(State.INIT);

        private String message;
        private String detailMessage;
        private String stackTrace;

        // descriptorOrValue can be a ValueDescriptorImpl (IntelliJ) or PyDebugValue or other object
        private final Object descriptorOrValue;
        private final String type;
        private final XStackFrame frame;

        enum State { INIT, MESSAGE_READY, STACK_READY, COMPLETE }

        ExceptionState(Object descriptorOrValue, String type, XStackFrame frame) {
            this.descriptorOrValue = descriptorOrValue;
            this.type = type;
            this.frame = frame;
        }

        public boolean isComplete() {
            return state.get() == State.COMPLETE;
        }

        public void setMessage(String msg) {
            this.message = msg;
            updateState();
        }

        public void setDetailMessage(String msg) {
            this.detailMessage = msg;
            updateState();
        }

        public void setStackTrace(String trace) {
            this.stackTrace = clipStackTrace(trace);
            updateState();
        }

        private void updateState() {
            if (message != null && stackTrace != null) {
                state.set(State.COMPLETE);
            } else if (message != null) {
                state.set(State.MESSAGE_READY);
            } else if (stackTrace != null) {
                state.set(State.STACK_READY);
            }
        }

        private String clipStackTrace(String stackTrace) {
            return Arrays.stream(stackTrace.split("\n"))
                .limit(Constants.MAX_STACKTRACE_LINES)
                .collect(Collectors.joining("\n"));
        }

        public ExceptionDetail buildExceptionDetail() {
            String combinedMessage = message;
            if (detailMessage != null && !detailMessage.isEmpty()) {
                combinedMessage = message + " | detailMessage: " + detailMessage;
            }
            String filePath = frame.getSourcePosition() != null ?
                frame.getSourcePosition().getFile().getPath() : "unknown";
            int lineNumber = frame.getSourcePosition() != null ?
                frame.getSourcePosition().getLine() : -1;
            return new ExceptionDetail(combinedMessage, type, stackTrace, filePath, lineNumber);
        }
    }

    public static void collectException(XStackFrame frame, Consumer<ContextItem> callback) {
        frame.computeChildren(new XCompositeNode() {
            @Override
            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                for (int i = 0; i < children.size(); i++) {
                    String name = children.getName(i);
                    XValue value = children.getValue(i);

                    if (isExceptionCandidate(name, value)) {
                        processException(value, frame, callback);
                        return;
                    }
                }
                // Fallback to snapshot if no exception found
                collectSnapshot(frame, callback);
            }

            private boolean isExceptionCandidate(String name, XValue value) {
                boolean nameSuggests = (name != null && name.toLowerCase().contains("exception"));

                boolean pyDescriptor = false;
                Object desc = null;
                if (isNodeDescriptorProvider(value)) {
                    desc = getDescriptorFromNodeDescriptorProvider(value);
                    pyDescriptor = isPyDebugValue(desc);
                }

                String type = getExceptionType(value);
                boolean typeSuggests = type != null && type.toLowerCase().contains("exception");

                return nameSuggests || pyDescriptor || typeSuggests;
            }

            private void processException(XValue value, XStackFrame frame, Consumer<ContextItem> callback) {
                Object descriptorObj = null;
                if (isNodeDescriptorProvider(value)) {
                    descriptorObj = getDescriptorFromNodeDescriptorProvider(value);
                }

                String type = getExceptionType(value);
                ExceptionState state = new ExceptionState(descriptorObj != null ? descriptorObj : value, type, frame);

                value.computeChildren(new XCompositeNode() {
                    @Override
                    public void addChildren(@NotNull XValueChildrenList exChildren, boolean last) {
                        for (int j = 0; j < exChildren.size(); j++) {
                            String fieldName = exChildren.getName(j);
                            XValue fieldValue = exChildren.getValue(j);

                            if ("detailMessage".equals(fieldName) || "message".equalsIgnoreCase(fieldName) || "args".equals(fieldName)) {
                                processDetailMessage(fieldValue, state, callback);
                            } else if ("stackTrace".equals(fieldName)
                                    || fieldName.toLowerCase().contains("traceback")
                                    || "__traceback__".equals(fieldName)) {
                                processStackTrace(fieldValue, state, callback);
                            }
                        }

                        // Set base message if not already set
                        if (state.message == null) {
                            String base = extractBaseMessage(descriptorObj, value);
                            state.setMessage(base);
                        }

                        if (state.isComplete()) {
                            complete(state, callback);
                        }
                    }

                    private void processDetailMessage(XValue fieldValue, ExceptionState state,
                            Consumer<ContextItem> callback) {
                        fieldValue.computePresentation(new XValueNode() {
                            @Override
                            public void setPresentation(@Nullable Icon icon,
                                    @NotNull XValuePresentation presentation, boolean hasChildren) {
                                String message = renderPresentation(presentation);
                                if (!"Collecting data...".equals(message)) {
                                    state.setDetailMessage(message);
                                    if (state.isComplete()) {
                                        complete(state, callback);
                                    }
                                }
                            }

                            @Override
                            public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) { }

                            @Override
                            public void setPresentation(@Nullable Icon icon, @NotNull String type,
                                    @NotNull String value, boolean hasChildren) { }
                        }, XValuePlace.TREE);
                    }

                    private void processStackTrace(XValue fieldValue, ExceptionState state,
                            Consumer<ContextItem> callback) {
                        fieldValue.computePresentation(new XValueNode() {
                            @Override
                            public void setPresentation(@Nullable Icon icon,
                                    @NotNull XValuePresentation presentation, boolean hasChildren) {
                                state.setStackTrace(renderPresentation(presentation));
                                if (state.isComplete()) {
                                    complete(state, callback);
                                }
                            }

                            @Override
                            public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) { }

                            @Override
                            public void setPresentation(@Nullable Icon icon, @NotNull String type,
                                    @NotNull String value, boolean hasChildren) { }
                        }, XValuePlace.TREE);
                    }

                    private String renderPresentation(XValuePresentation presentation) {
                        StringBuilder sb = new StringBuilder();
                        presentation.renderValue(new XValuePresentation.XValueTextRenderer() {
                            @Override
                            public void renderValue(@NotNull String value) {
                                sb.append(value);
                            }

                            @Override
                            public void renderStringValue(@NotNull String value) {
                                sb.append(value);
                            }

                            @Override
                            public void renderNumericValue(@NotNull String value) {
                                sb.append(value);
                            }

                            @Override
                            public void renderKeywordValue(@NotNull String value) {
                                sb.append(value);
                            }

                            @Override
                            public void renderValue(@NotNull String value, @NotNull TextAttributesKey key) {
                                sb.append(value);
                            }

                            @Override
                            public void renderStringValue(@NotNull String value, @Nullable String ref, int unused) {
                                sb.append(value);
                            }

                            @Override
                            public void renderComment(@NotNull String comment) { }

                            @Override
                            public void renderSpecialSymbol(@NotNull String symbol) {
                                sb.append(symbol);
                            }

                            @Override
                            public void renderError(@NotNull String error) {
                                sb.append(error);
                            }
                        });
                        return sb.toString();
                    }

                    private void complete(ExceptionState state, Consumer<ContextItem> callback) {
                        ExceptionDetail detail = state.buildExceptionDetail();
                        instance.latestException = detail;
                        callback.accept(new ContextItem(detail, true, ContextItem.Type.EXCEPTION));
                    }

                    @Override
                    public void tooManyChildren(int remaining) { }

                    @Override
                    public void setAlreadySorted(boolean alreadySorted) { }

                    @Override
                    public void setErrorMessage(@NotNull String errorMessage) { }

                    @Override
                    public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { }

                    @Override
                    public void setMessage(@NotNull String s, @Nullable Icon icon,
                            @NotNull com.intellij.ui.SimpleTextAttributes attrs,
                            @Nullable XDebuggerTreeNodeHyperlink link) { }
                });
            }

            private String getExceptionType(XValue value) {
                final String[] type = {"unknown"};
                value.computePresentation(new XValueNode() {
                    @Override
                    public void setPresentation(@Nullable Icon icon,
                            @NotNull XValuePresentation presentation, boolean hasChildren) {
                        String typeStr = presentation.getType();
                        if (typeStr != null && typeStr.toLowerCase().contains("exception")) {
                            type[0] = typeStr;
                        }
                    }

                    @Override
                    public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) { }

                    @Override
                    public void setPresentation(@Nullable Icon icon, @NotNull String type,
                            @NotNull String value, boolean hasChildren) { }
                }, XValuePlace.TREE);
                return type[0];
            }

            @Override
            public void tooManyChildren(int remaining) { }

            @Override
            public void setAlreadySorted(boolean alreadySorted) { }

            @Override
            public void setErrorMessage(@NotNull String errorMessage) { }

            @Override
            public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { }

            @Override
            public void setMessage(@NotNull String s, @Nullable Icon icon,
                    @NotNull com.intellij.ui.SimpleTextAttributes attrs,
                    @Nullable XDebuggerTreeNodeHyperlink link) { }
        });
    }

    @Nullable
    private static String extractEnclosingFunction(@NotNull Project project, @NotNull VirtualFile file, int line) {
        return com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction((com.intellij.openapi.util.Computable<String>) () -> {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (psiFile == null) {
                logger.warn("PSI file not found for: " + file.getPath());
                return null;
            }
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document == null || line >= document.getLineCount()) {
                logger.warn("Invalid document or line number for file: " + file.getPath());
                return null;
            }
            int offset = document.getLineStartOffset(line);
            PsiElement elementAt = psiFile.findElementAt(offset);
            if (elementAt == null) {
                logger.warn("No PSI element at line: " + line + " in file: " + file.getPath());
                return null;
            }
            PsiElement functionElement = PsiTreeUtil.getParentOfType(elementAt, PsiMethod.class, PsiLambdaExpression.class);
            if (functionElement == null) {
                return null;
            }

            String fullText = functionElement.getText();
            String[] lines = fullText.split("\n");

            if (lines.length <= Constants.ENCLOSING_FUNCTION_PREFIX_LINES + Constants.ENCLOSING_FUNCTION_SUFFIX_LINES) {
                return fullText;
            }

            int functionStartLine = document.getLineNumber(functionElement.getTextRange().getStartOffset());
            int targetLineInFunction = line - functionStartLine;

            int startLine = Math.max(targetLineInFunction - Constants.ENCLOSING_FUNCTION_PREFIX_LINES, 0);
            int endLine = Math.min(targetLineInFunction + Constants.ENCLOSING_FUNCTION_SUFFIX_LINES, lines.length - 1);

            StringBuilder clipped = new StringBuilder();
            for (int i = startLine; i <= endLine; i++) {
                clipped.append(lines[i]).append("\n");
            }
            return clipped.toString().trim();
        });
    }

    // ======= Helpers for PyCharm compatibility (reflection) =======

    private static boolean isPyCharmEnvironment() {
        try {
            Class.forName("com.jetbrains.python.debugger.PyDebugValue");
            return true;
        } catch (Throwable t) {
            return false;
        }
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

    private static boolean isNodeDescriptorProvider(Object o) {
        if (o == null) return false;
        try {
            Class<?> cls = Class.forName("com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider");
            return cls.isInstance(o);
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    private static Object getDescriptorFromNodeDescriptorProvider(Object nodeDescriptorProvider) {
        if (nodeDescriptorProvider == null) return null;
        try {
            Method m = nodeDescriptorProvider.getClass().getMethod("getDescriptor");
            return m.invoke(nodeDescriptorProvider);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isValueDescriptorImpl(Object o) {
        if (o == null) return false;
        try {
            Class<?> cls = Class.forName("com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl");
            return cls.isInstance(o);
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    private static String valueDescriptorGetName(Object valueDescriptor) {
        if (valueDescriptor == null) return null;
        try {
            Method m = valueDescriptor.getClass().getMethod("getName");
            Object res = m.invoke(valueDescriptor);
            return res != null ? String.valueOf(res) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static String valueDescriptorCalcValueName(Object valueDescriptor) {
        if (valueDescriptor == null) return null;
        try {
            Method m = valueDescriptor.getClass().getMethod("calcValueName");
            Object res = m.invoke(valueDescriptor);
            return res != null ? String.valueOf(res) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Object valueDescriptorGetValue(Object valueDescriptor) {
        if (valueDescriptor == null) return null;
        try {
            Method m = valueDescriptor.getClass().getMethod("getValue");
            return m.invoke(valueDescriptor);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static String tryReflectPyValueString(XValue xValue) {
        try {
            // If the XValue is a NodeDescriptorProvider, extract descriptor first (safe)
            Object descriptor = null;
            if (isNodeDescriptorProvider(xValue)) {
                descriptor = getDescriptorFromNodeDescriptorProvider(xValue);
            } else {
                descriptor = xValue;
            }

            if (!isPyDebugValue(descriptor)) return null;
            Class<?> pyCls = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
            Object name = pyCls.getMethod("getName").invoke(descriptor);
            Object val = pyCls.getMethod("getValue").invoke(descriptor);

            // Special-case map __exception__ to EXCEPTION for parity with IntelliJ
            String nameStr = String.valueOf(name);
            String valStr = String.valueOf(val);
            if ("__exception__".equals(nameStr)) {
                String s = "EXCEPTION = " + valStr;
                logger.debug("[DebugDataCollector] PyCharm reflection (exception) value built: " + s);
                return s;
            }

            String s = String.valueOf(name) + " = " + String.valueOf(val);
            logger.debug("[DebugDataCollector] PyCharm reflection value built: " + s);
            return s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extractBaseMessage(Object descriptorObj, XValue xValue) {
        // Try ValueDescriptorImpl (IntelliJ)
        if (isValueDescriptorImpl(descriptorObj)) {
            try {
                Object val = valueDescriptorGetValue(descriptorObj);
                return val != null ? val.toString() : "null";
            } catch (Throwable t) {
                // ignore and continue to next attempts
            }
        }
        // PyCharm debugger (reflection)
        if (isPyDebugValue(descriptorObj)) {
            try {
                Class<?> pyCls = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
                Object name = pyCls.getMethod("getName").invoke(descriptorObj);
                Object val = pyCls.getMethod("getValue").invoke(descriptorObj);

                // If PyCharm exposes a special __exception__, normalize it
                if ("__exception__".equals(String.valueOf(name))) {
                    logger.debug("[DebugDataCollector] Exception message from PyCharm reflection (normalized).");
                    return String.valueOf(val);
                }

                logger.debug("[DebugDataCollector] Exception message from PyCharm reflection.");
                return val != null ? val.toString() : "null";
            } catch (Throwable t) {
                // ignore and fallback to presentation
            }
        }

        // Fallback: render the XValue's presentation text
        final String[] rendered = { "null" };
        xValue.computePresentation(new XValueNode() {
            @Override
            public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                StringBuilder sb = new StringBuilder();
                presentation.renderValue(new XValuePresentation.XValueTextRenderer() {
                    @Override
                    public void renderValue(@NotNull String value) { sb.append(value); }
                    @Override
                    public void renderStringValue(@NotNull String value) { sb.append(value); }
                    @Override
                    public void renderNumericValue(@NotNull String value) { sb.append(value); }
                    @Override
                    public void renderKeywordValue(@NotNull String value) { sb.append(value); }
                    @Override
                    public void renderValue(@NotNull String value, @NotNull TextAttributesKey key) { sb.append(value); }
                    @Override
                    public void renderStringValue(@NotNull String value, @Nullable String ref, int unused) { sb.append(value); }
                    @Override
                    public void renderComment(@NotNull String comment) { }
                    @Override
                    public void renderSpecialSymbol(@NotNull String symbol) { sb.append(symbol); }
                    @Override
                    public void renderError(@NotNull String error) { sb.append(error); }
                });
                rendered[0] = sb.toString();
            }

            @Override
            public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) { }

            @Override
            public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) { }
        }, XValuePlace.TREE);
        return rendered[0];
    }

    // ======= Utility to normalize IntelliJ and PyCharm values (requested) =======

    @SuppressWarnings("unchecked")
    public static void handleDebugValue(Object valueObj, Map<String, String> variables) {
        try {
            // PyCharm runtime (reflection)
            try {
                Class<?> pyDebugClass = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
                if (pyDebugClass.isInstance(valueObj)) {
                    Method getName = pyDebugClass.getMethod("getName");
                    Method getValue = pyDebugClass.getMethod("getValue");
                    String name = String.valueOf(getName.invoke(valueObj));
                    String value = String.valueOf(getValue.invoke(valueObj));

                    if ("__exception__".equals(name)) {
                        variables.put("EXCEPTION", value);
                        logger.debug("Detected PyCharm Exception: " + value);
                    } else {
                        variables.put(name, value);
                        logger.debug("Detected PyCharm variable: " + name + " = " + value);
                    }
                    return;
                }
            } catch (ClassNotFoundException ignored) {
                // PyDebugValue class not present => not PyCharm runtime
            }

            // IntelliJ ValueDescriptorImpl (via reflection check)
            if (isValueDescriptorImpl(valueObj)) {
                String name = valueDescriptorGetName(valueObj);
                String value = valueDescriptorCalcValueName(valueObj);
                if (name != null) {
                    variables.put(name, value);
                    logger.debug("Detected IntelliJ variable: " + name + " = " + value);
                }
            }
        } catch (Exception e) {
            logger.warn("handleDebugValue error: " + e.getMessage(), e);
        }
    }

    public static void handleDebugXValue(Object xValue, Map<String, String> variables) {
        try {
            if (xValue == null) {
                return;
            }

            // If this is a NodeDescriptorProvider, prefer the descriptor (works for many IDEs) - via reflection
            Object valObj = xValue;
            if (isNodeDescriptorProvider(xValue)) {
                Object desc = getDescriptorFromNodeDescriptorProvider(xValue);
                if (desc != null) valObj = desc;
            }

            // PyCharm descriptor (reflection)
            if (isPyDebugValue(valObj)) {
                try {
                    Class<?> pyDebugClass = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
                    Method getName = pyDebugClass.getMethod("getName");
                    Method getValue = pyDebugClass.getMethod("getValue");
                    String name = String.valueOf(getName.invoke(valObj));
                    String value = String.valueOf(getValue.invoke(valObj));
                    if ("__exception__".equals(name)) {
                        variables.put("EXCEPTION", value);
                        logger.debug("Detected PyCharm Exception (via handleDebugXValue): " + value);
                    } else {
                        variables.put(name, value);
                        logger.debug("Detected PyCharm variable (via handleDebugXValue): " + name + " = " + value);
                    }
                    return;
                } catch (Throwable t) {
                    // reflection failed, fall through to other handlers
                }
            }

            // IntelliJ ValueDescriptorImpl fallback (reflection-based)
            if (isValueDescriptorImpl(valObj)) {
                try {
                    String name = valueDescriptorGetName(valObj);
                    String value = valueDescriptorCalcValueName(valObj);
                    if (name != null && name.toLowerCase().contains("exception")) {
                        variables.put("EXCEPTION", value);
                        logger.debug("Detected IntelliJ Exception (via handleDebugXValue): " + value);
                    } else if (name != null) {
                        variables.put(name, value);
                        logger.debug("Detected IntelliJ variable (via handleDebugXValue): " + name + " = " + value);
                    }
                    return;
                } catch (Throwable t) {
                    // ignore and fallback
                }
            }

            // Last resort: stringify the object
            variables.put("unknown", String.valueOf(xValue));
        } catch (Exception e) {
            logger.warn("handleDebugXValue error: " + e.getMessage(), e);
        }
    }
}
