
/**
 * Unified handler for both IntelliJ (Java) and PyCharm (Python) debug values.
 * Uses reflection to avoid hard dependency on PyCharm classes.
 */
private static Object handleDebugValue(Object valueObj) {
    if (valueObj instanceof ValueDescriptorImpl) {
        ValueDescriptorImpl vd = (ValueDescriptorImpl) valueObj;
        try {
            return vd.getName() + " = " + vd.calcValueName();
        } catch (Exception e) {
            return vd.getName() + " = <error>";
        }
    } else {
        try {
            // Try resolving PyCharm's PyDebugValue via reflection
            Class<?> pyValClass = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
            if (pyValClass.isInstance(valueObj)) {
                Object name = pyValClass.getMethod("getName").invoke(valueObj);
                Object val = pyValClass.getMethod("getValue").invoke(valueObj);
                return String.format("%s = %s", name, val);
            }
        } catch (ClassNotFoundException cnfe) {
            // PyCharm class not available (running in IntelliJ), ignore
        } catch (Exception e) {
            // Reflection failure, ignore gracefully
        }

        // Fallback for other object types
        return String.valueOf(valueObj);
    }
}










import com.google.gson.Gson; 

import com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider; 

import com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl; 

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
import com.jetbrains.python.debugger.PyDebugValue; 

import com.intellij.xdebugger.frame.presentation.XValuePresentation; 

import org.jetbrains.annotations.NotNull; 

import org.jetbrains.annotations.Nullable; 

import org.dell.Constants; 


  

import javax.swing.*; 

import java.util.ArrayList; 

import java.util.Arrays; 

import java.util.List; 

import java.util.concurrent.atomic.AtomicBoolean; 

import java.util.concurrent.atomic.AtomicInteger; 

import java.util.concurrent.atomic.AtomicReference; 

import java.util.stream.Collectors; 

import java.util.function.Consumer; 

  

public 
/**
 * === PyCharm Support Added ===
 * Handle PyDebugValue objects in addition to IntelliJ's ValueDescriptorImpl.
 */
private static Object handleDebugValue(Object valueObj) {
    if (valueObj instanceof ValueDescriptorImpl) {
        ValueDescriptorImpl vd = (ValueDescriptorImpl) valueObj;
        return vd.getName() + " = " + vd.calcValueName();
    } else if (valueObj instanceof PyDebugValue) {
        PyDebugValue pyVal = (PyDebugValue) valueObj;
        return pyVal.getName() + " = " + pyVal.getValue();
    } else {
        return String.valueOf(valueObj);
    }
}
class DebugDataCollectorDebugDataCollector { 

    private static final Logger logger = Logger.getInstance(DebugDataCollector.class); 

    private static final DebugDataCollector instance = new DebugDataCollector(); 

    private final List<SnapshotItem> latestSnapshot = new ArrayList<>(); 

    private final List<StackItem> latestStack = new ArrayList<>(); 

    private ExceptionDetail latestException = null; 

    private DebugDataCollector() { 

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

                        String language = pos.getFile().getExtension(), functionText = extractEnclosingFunction(debugProcess.getSession().getProject(), pos.getFile(), pos.getLine()); 

                        stackItems.add(new StackItem(file, line, functionText != null ? functionText : "", language)); 

                    } 

                } 

  

                // Apply size limit 

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

  

                    // Check limits before processing each variable 

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

  

                    // Check size limit 

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

                                    public void renderComment(@NotNull String comment) { 

                                    } 

  

                                    @Override 

                                    public void renderSpecialSymbol(@NotNull String symbol) { 

                                        sb.append(symbol); 

                                    } 

  

                                    @Override 

                                    public void renderError(@NotNull String error) { 

                                        sb.append(error); 

                                    } 

                                }); 

                                mutableItem.value = sb.toString(); 

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

                        public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) { 

                        } 

  

                        @Override 

                        public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) { 

                        } 

                    }, XValuePlace.TREE); 

                } 

            } 

  

            @Override 

            public void tooManyChildren(int remaining) { 

            } 

  

            @Override 

            public 

            void setAlreadySorted(boolean alreadySorted) { 

            } 

  

            @Override 

            public void setErrorMessage(@NotNull String errorMessage) { 

            } 

  

            @Override 

            public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { 

            } 

  

            @Override 

            public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) { 

            } 

  

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

        private final ValueDescriptorImpl descriptor; 

        private final String type; 

        private final XStackFrame frame; 

         

        enum State { INIT, MESSAGE_READY, STACK_READY, COMPLETE } 

         

        ExceptionState(ValueDescriptorImpl descriptor, String type, XStackFrame frame) { 

            this.descriptor = descriptor; 

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

                return name != null && name.toLowerCase().contains("exception") &&  

                    value instanceof NodeDescriptorProvider; 

            } 

  

            private void processException(XValue value, XStackFrame frame, Consumer<ContextItem> callback) { 

                ValueDescriptorImpl descriptor = (ValueDescriptorImpl)((NodeDescriptorProvider)value).getDescriptor(); 

                String type = getExceptionType(value); 

                ExceptionState state = new ExceptionState(descriptor, type, frame); 

                 

                value.computeChildren(new XCompositeNode() { 

                    @Override 

                    public void addChildren(@NotNull XValueChildrenList exChildren, boolean last) { 

                        for (int j = 0; j < exChildren.size(); j++) { 

                            String fieldName = exChildren.getName(j); 

                            XValue fieldValue = exChildren.getValue(j); 

                             

                            if ("detailMessage".equals(fieldName)) { 

                                processDetailMessage(fieldValue, state, callback); 

                            } else if ("stackTrace".equals(fieldName)) { 

                                processStackTrace(fieldValue, state, callback); 

                            } 

                        } 

                         

                        // Set base message if not already set 

                        if (state.message == null) { 

                            state.setMessage(descriptor.getValue() != null ?  

                                descriptor.getValue().toString() : "null"); 

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

                            public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) { 

                            } 

  

                            @Override 

                            public void setPresentation(@Nullable Icon icon, @NotNull String type,  

                                    @NotNull String value, boolean hasChildren) { 

                            } 

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

                            public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) { 

                            } 

  

                            @Override 

                            public void setPresentation(@Nullable Icon icon, @NotNull String type,  

                                    @NotNull String value, boolean hasChildren) { 

                            } 

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

                            public void renderComment(@NotNull String comment) { 

                            } 

  

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

                    public void tooManyChildren(int remaining) { 

                    } 

  

                    @Override 

                    public void setAlreadySorted(boolean alreadySorted) { 

                    } 

  

                    @Override 

                    public void setErrorMessage(@NotNull String errorMessage) { 

                    } 

  

                    @Override 

                    public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { 

                    } 

  

                    @Override 

                    public void setMessage(@NotNull String s, @Nullable Icon icon,  

                            @NotNull com.intellij.ui.SimpleTextAttributes attrs,  

                            @Nullable XDebuggerTreeNodeHyperlink link) { 

                    } 

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

                    public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) { 

                    } 

  

                    @Override 

                    public void setPresentation(@Nullable Icon icon, @NotNull String type,  

                            @NotNull String value, boolean hasChildren) { 

                    } 

                }, XValuePlace.TREE); 

                return type[0]; 

            } 

  

            @Override 

            public void tooManyChildren(int remaining) { 

            } 

  

            @Override 

            public void setAlreadySorted(boolean alreadySorted) { 

            } 

  

            @Override 

            public void setErrorMessage(@NotNull String errorMessage) { 

            } 

  

            @Override 

            public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { 

            } 

  

            @Override 

            public void setMessage(@NotNull String s, @Nullable Icon icon,  

                    @NotNull com.intellij.ui.SimpleTextAttributes attrs,  

                    @Nullable XDebuggerTreeNodeHyperlink link) { 

            } 

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

  

            // Get full function text 

            String fullText = functionElement.getText(); 

            String[] lines = fullText.split("\n"); 

  

            // If function is small enough, return full text 

            if (lines.length <= Constants.ENCLOSING_FUNCTION_PREFIX_LINES + Constants.ENCLOSING_FUNCTION_SUFFIX_LINES) { 

                return fullText; 

            } 

  

            // Find the line in the function where our target line appears 

            int functionStartLine = document.getLineNumber(functionElement.getTextRange().getStartOffset()); 

            int targetLineInFunction = line - functionStartLine; 

  

            // Calculate clip range 

            int startLine = Math.max(targetLineInFunction - Constants.ENCLOSING_FUNCTION_PREFIX_LINES, 0); 

            int endLine = Math.min(targetLineInFunction + Constants.ENCLOSING_FUNCTION_SUFFIX_LINES, lines.length - 1); 

  

            // Return clipped portion 

            StringBuilder clipped = new StringBuilder(); 

            for (int i = startLine; i <= endLine; i++) { 

                clipped.append(lines[i]).append("\n"); 

            } 

            return clipped.toString().trim(); 

        }); 

    } 

} 

 
