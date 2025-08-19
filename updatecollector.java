// DebugDataCollector.java
package org.samsung.aipp.aippintellij.debugAssist; // adjust package to match your project

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
import org.dell.Constants;

import javax.swing.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.function.Consumer;

/**
 * DebugDataCollector - unified collector that works both under IntelliJ (Java debugger)
 * and PyCharm (Python debugger) using safe runtime checks and reflective fallbacks.
 *
 * This file intentionally avoids direct references to classes that may not be present
 * in all IDE distributions (e.g. com.jetbrains.python.debugger.PyDebugValue,
 * com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider, com.intellij.psi.PsiMethod, ...).
 *
 * Make sure other helper/model classes (SnapshotItem, MutableSnapshotItem, StackItem,
 * ExceptionDetail, ContextItem, Constants) are present in your project.
 */
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
            logger.debug("[DebugDataCollector] Running without PyDebugValue class (likely IntelliJ platform).");
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

    // ---------------- Stack collection ----------------

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
                    if (stackItems.size() >= Constants.MAX_CALLSTACK_ITEMS) {
                        break;
                    }
                    XSourcePosition pos = frame.getSourcePosition();
                    if (pos != null) {
                        String file = pos.getFile().getPath();
                        int line = pos.getLine() + 1;
                        String language = pos.getFile().getExtension();
                        String functionText = extractEnclosingFunctionSafe(debugProcess, pos);
                        stackItems.add(new StackItem(file, line, functionText != null ? functionText : "", language));
                    }
                }

                // apply size limit
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

    // Helper: a safe wrapper to get surrounding function text (tries PSI-reflective approach only if available)
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

    /**
     * Try to extract enclosing function text, but do it reflectively so that
     * missing PSI classes in PyCharm do not cause NoClassDefFoundError.
     *
     * If PSI is not available or not applicable for the file, returns null.
     */
    @Nullable
    private static String extractEnclosingFunctionReflective(@NotNull Project project, @NotNull VirtualFile file, int line) {
        try {
            // We try to use PSI only if classes exist.
            Class<?> psiManagerClass = Class.forName("com.intellij.psi.PsiManager");
            Class<?> psiFileClass = Class.forName("com.intellij.psi.PsiFile");
            Class<?> psiElementClass = Class.forName("com.intellij.psi.PsiElement");

            // Use FileDocumentManager to get document and offsets
            Document document = FileDocumentManager.getInstance().getDocument(file);
            if (document == null || line >= document.getLineCount()) {
                return null;
            }
            Object psiManager = psiManagerClass.getMethod("getInstance", com.intellij.openapi.project.Project.class).invoke(null, project);
            Object psiFile = psiManagerClass.getMethod("findFile", VirtualFile.class).invoke(psiManager, file);
            if (psiFile == null) return null;

            int offset = document.getLineStartOffset(line);
            Method findElementAt = psiFileClass.getMethod("findElementAt", int.class);
            Object elementAt = findElementAt.invoke(psiFile, offset);
            if (elementAt == null) return null;

            // Try to find Java method or similar (but do NOT reference PsiMethod class directly)
            // We'll search parent types named "PsiMethod" / "PsiFunction" reflectively.
            Class<?> psiTreeUtilClass = Class.forName("com.intellij.psi.util.PsiTreeUtil");
            // getParentOfType(element, PsiMethod.class, PsiLambdaExpression.class)
            // we try to load PsiMethod and PsiLambdaExpression if present
            Class<?> psiMethodClass = null;
            Class<?> psiLambdaClass = null;
            try {
                psiMethodClass = Class.forName("com.intellij.psi.PsiMethod");
            } catch (Throwable ignored) { }
            try {
                psiLambdaClass = Class.forName("com.intellij.psi.PsiLambdaExpression");
            } catch (Throwable ignored) { }

            Object functionElement = null;
            if (psiMethodClass != null) {
                // call PsiTreeUtil.getParentOfType(elementAt, psiMethodClass, psiLambdaClass)
                if (psiLambdaClass != null) {
                    functionElement = psiTreeUtilClass.getMethod("getParentOfType", psiElementClass, Class.class, Class.class)
                            .invoke(null, elementAt, psiMethodClass, psiLambdaClass);
                } else {
                    functionElement = psiTreeUtilClass.getMethod("getParentOfType", psiElementClass, Class.class)
                            .invoke(null, elementAt, psiMethodClass);
                }
            } else {
                // If PsiMethod not present, try to find other function-like parent by name (best-effort)
                // fallback: return null - nothing we can do safely
                return null;
            }

            if (functionElement == null) return null;

            // Get text
            Method getText = functionElement.getClass().getMethod("getText");
            String fullText = (String) getText.invoke(functionElement);
            if (fullText == null) return null;

            String[] lines = fullText.split("\n");
            int prefix = Constants.ENCLOSING_FUNCTION_PREFIX_LINES;
            int suffix = Constants.ENCLOSING_FUNCTION_SUFFIX_LINES;
            if (lines.length <= prefix + suffix) {
                return fullText;
            }

            Method getTextRange = functionElement.getClass().getMethod("getTextRange");
            Object textRange = getTextRange.invoke(functionElement);
            Method getStartOffset = textRange.getClass().getMethod("getStartOffset");
            int functionStartOffset = (int) getStartOffset.invoke(textRange);
            int functionStartLine = document.getLineNumber(functionStartOffset);
            int targetLineInFunction = line - functionStartLine;

            int startLine = Math.max(targetLineInFunction - prefix, 0);
            int endLine = Math.min(targetLineInFunction + suffix, lines.length - 1);

            StringBuilder clipped = new StringBuilder();
            for (int i = startLine; i <= endLine; i++) {
                clipped.append(lines[i]).append("\n");
            }
            return clipped.toString().trim();
        } catch (ClassNotFoundException cnfe) {
            // PSI classes not present (PyCharm runtime or minimal platform) — return null
            logger.debug("PSI not available in runtime: " + cnfe.getMessage());
            return null;
        } catch (Throwable t) {
            logger.warn("extractEnclosingFunctionReflective error: " + t.getMessage());
            return null;
        }
    }

    // ---------------- Children & snapshot collection ----------------

    private static void collectChildren(XValue value, MutableSnapshotItem parent, int currentDepth, Runnable onComplete) {
        if (currentDepth >= Constants.MAX_DEPTH_OF_NESTED_VARIABLES) {
            onComplete.run();
            return;
        }
        try {
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
                                            if (value != null) sb.append(value);
                                        }

                                        @Override
                                        public void renderStringValue(@Nullable String value) {
                                            if (value != null) sb.append(value);
                                        }

                                        @Override
                                        public void renderNumericValue(@Nullable String value) {
                                            if (value != null) sb.append(value);
                                        }

                                        @Override
                                        public void renderKeywordValue(@Nullable String value) {
                                            if (value != null) sb.append(value);
                                        }

                                        @Override
                                        public void renderValue(@Nullable String value, @NotNull TextAttributesKey key) {
                                            if (value != null) sb.append(value);
                                        }

                                        @Override
                                        public void renderStringValue(@Nullable String value, @Nullable String ref, int unused) {
                                            if (value != null) sb.append(value);
                                        }

                                        @Override
                                        public void renderComment(@NotNull String comment) {
                                            // ignore
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

                                    childItem.value = sb.toString();

                                    // Fallback: if presentation rendered nothing and we're in PyCharm, try reflection
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
                            public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) { }

                            @Override
                            public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) { }
                        }, XValuePlace.TREE);
                    }
                }

                @Override
                public void tooManyChildren(int remaining) { onComplete.run(); }

                @Override
                public void setAlreadySorted(boolean alreadySorted) { }

                @Override
                public void setErrorMessage(@NotNull String errorMessage) { onComplete.run(); }

                @Override
                public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { onComplete.run(); }

                @Override
                public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) { }
            });
        } catch (Throwable t) {
            logger.warn("collectChildren outer failure: " + t.getMessage());
            onComplete.run();
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
                                        public void renderValue(@Nullable String value) { if (value != null) sb.append(value); }
                                        @Override
                                        public void renderStringValue(@Nullable String value) { if (value != null) sb.append(value); }
                                        @Override
                                        public void renderNumericValue(@Nullable String value) { if (value != null) sb.append(value); }
                                        @Override
                                        public void renderKeywordValue(@Nullable String value) { if (value != null) sb.append(value); }
                                        @Override
                                        public void renderValue(@Nullable String value, @NotNull TextAttributesKey key) { if (value != null) sb.append(value); }
                                        @Override
                                        public void renderStringValue(@Nullable String value, @Nullable String ref, int unused) { if (value != null) sb.append(value); }
                                        @Override
                                        public void renderComment(@NotNull String comment) { }
                                        @Override
                                        public void renderSpecialSymbol(@NotNull String symbol) { sb.append(symbol); }
                                        @Override
                                        public void renderError(@NotNull String error) { sb.append(error); }
                                    });

                                    mutableItem.value = sb.toString();

                                    // PyCharm fallback reflection
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
        } catch (Throwable t) {
            logger.warn("collectSnapshot outer error: " + t.getMessage());
            // fallback: return currently collected items (if any)
            List<SnapshotItem> result = new ArrayList<>();
            for (MutableSnapshotItem item : snapshotItems) result.add(item.toSnapshotItem());
            instance.latestSnapshot.clear();
            instance.latestSnapshot.addAll(result);
            callback.accept(new ContextItem(result, false, ContextItem.Type.SNAPSHOT));
        }
    }

    // ---------------- Exception detection & collection ----------------

    private static class ExceptionState {
        private final AtomicReference<State> state = new AtomicReference<>(State.INIT);
        private String message;
        private String detailMessage;
        private String stackTrace;
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
        try {
            frame.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                    for (int i = 0; i < children.size(); i++) {
                        String name = children.getName(i);
                        XValue value = children.getValue(i);

                        if (isExceptionCandidateSafe(name, value)) {
                            processExceptionSafe(value, frame, callback);
                            return;
                        }
                    }
                    // fallback: nothing looked like exception -> collect snapshot
                    collectSnapshot(frame, callback);
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
            });
        } catch (Throwable t) {
            logger.warn("collectException outer error: " + t.getMessage());
            // fallback to snapshot
            try {
                collectSnapshot(frame, callback);
            } catch (Throwable ignored) { }
        }
    }

    // Heuristics-safe check for exception candidate:
    // - variable name containing 'exception' OR
    // - XValue's presentation type contains 'Exception' OR
    // - descriptor looks like PyDebugValue (PyCharm)
    private static boolean isExceptionCandidateSafe(String name, XValue value) {
        try {
            boolean nameSuggests = (name != null && name.toLowerCase().contains("exception"));

            // presentation-based type detection
            String type = getExceptionType(value);
            boolean typeSuggests = type != null && type.toLowerCase().contains("exception");

            // descriptor-based heuristic (reflectively check NodeDescriptorProvider descriptor types)
            boolean descriptorSuggests = false;
            try {
                // NodeDescriptorProvider may not exist; guard by reflection
                Method getDescriptorMethod = null;
                if (value instanceof com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) {
                    // if NodeDescriptorProvider class is present at compile/ runtime, handle
                    Object desc = ((com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) value).getDescriptor();
                    descriptorSuggests = isPyDebugValue(desc);
                } else {
                    // attempt reflective path if class is present at runtime
                    Class<?> nodeProviderClass = Class.forName("com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider");
                    if (nodeProviderClass.isInstance(value)) {
                        Method gd = nodeProviderClass.getMethod("getDescriptor");
                        Object desc = gd.invoke(value);
                        descriptorSuggests = isPyDebugValue(desc);
                    }
                }
            } catch (Throwable t) {
                // ignore: either NodeDescriptorProvider not present or reflection failed
            }

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
                // try to get descriptor via NodeDescriptorProvider if available
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
                // ignore
                descriptorObj = null;
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
                        } else if ("stackTrace".equals(fieldName) || fieldName.toLowerCase().contains("traceback") || "__traceback__".equals(fieldName)) {
                            processStackTrace(fieldValue, state, callback);
                        }
                    }

                    // set base message if none set yet
                    if (state.message == null) {
                        String base = extractBaseMessageSafe(descriptorObj, value);
                        state.setMessage(base);
                    }

                    if (state.isComplete()) {
                        completeExceptionState(state, callback);
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
            });
        } catch (Throwable t) {
            logger.warn("processExceptionSafe error: " + t.getMessage());
            // fallback: treat as snapshot
            collectSnapshot(frame, callback);
        }
    }

    private static void processDetailMessage(XValue fieldValue, ExceptionState state, Consumer<ContextItem> callback) {
        try {
            fieldValue.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    String message = renderPresentation(presentation);
                    if (!"Collecting data...".equals(message)) {
                        state.setDetailMessage(message);
                        if (state.isComplete()) {
                            completeExceptionState(state, callback);
                        }
                    }
                }

                @Override
                public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) { }

                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) { }
            }, XValuePlace.TREE);
        } catch (Throwable t) {
            logger.debug("processDetailMessage failed: " + t.getMessage());
        }
    }

    private static void processStackTrace(XValue fieldValue, ExceptionState state, Consumer<ContextItem> callback) {
        try {
            fieldValue.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    state.setStackTrace(renderPresentation(presentation));
                    if (state.isComplete()) {
                        completeExceptionState(state, callback);
                    }
                }

                @Override
                public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) { }

                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) { }
            }, XValuePlace.TREE);
        } catch (Throwable t) {
            logger.debug("processStackTrace failed: " + t.getMessage());
        }
    }

    private static String renderPresentation(XValuePresentation presentation) {
        StringBuilder sb = new StringBuilder();
        try {
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
        } catch (Throwable t) {
            logger.debug("renderPresentation failed: " + t.getMessage());
        }
        return sb.toString();
    }

    private static void completeExceptionState(ExceptionState state, Consumer<ContextItem> callback) {
        try {
            ExceptionDetail detail = state.buildExceptionDetail();
            instance.latestException = detail;
            callback.accept(new ContextItem(detail, true, ContextItem.Type.EXCEPTION));
        } catch (Throwable t) {
            logger.warn("completeExceptionState failed: " + t.getMessage());
        }
    }

    private static String getExceptionType(XValue value) {
        final String[] type = {"unknown"};
        try {
            value.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    String typeStr = presentation.getType();
                    if (typeStr != null && typeStr.toLowerCase().contains("exception")) {
                        type[0] = typeStr;
                    }
                }

                @Override
                public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) { }

                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) { }
            }, XValuePlace.TREE);
        } catch (Throwable t) {
            logger.debug("getExceptionType failed: " + t.getMessage());
        }
        return type[0];
    }

    // Extract base message from descriptor or from the XValue presentation fallback
    private static String extractBaseMessageSafe(Object descriptorObj, XValue xValue) {
        try {
            // If descriptorObj looks like ValueDescriptorImpl (IntelliJ Java), use reflection to call getValue() or calcValueName()
            if (descriptorObj != null) {
                try {
                    Class<?> vdiClass = Class.forName("com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl");
                    if (vdiClass.isInstance(descriptorObj)) {
                        try {
                            Method getValue = vdiClass.getMethod("getValue");
                            Object val = getValue.invoke(descriptorObj);
                            if (val != null) return val.toString();
                        } catch (NoSuchMethodException nsme) {
                            // fall back to toString
                        }
                    }
                } catch (Throwable ignored) { }
            }

            // PyCharm descriptor: try to read PyDebugValue.getValue() reflectively
            if (descriptorObj != null && isPyDebugValue(descriptorObj)) {
                try {
                    Class<?> pyCls = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
                    Method getValue = pyCls.getMethod("getValue");
                    Object val = getValue.invoke(descriptorObj);
                    if (val != null) return val.toString();
                } catch (Throwable ignored) { }
            }

            // Otherwise try to render presentation text
            final String[] rendered = {"null"};
            try {
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
            } catch (Throwable t) {
                logger.debug("extractBaseMessageSafe presentation render error: " + t.getMessage());
            }
            return rendered[0];
        } catch (Throwable t) {
            logger.warn("extractBaseMessageSafe overall error: " + t.getMessage());
            return "null";
        }
    }

    // ---------------- PyCharm reflective helpers ----------------

    // Detect whether PyCharm debug classes exist in runtime
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

    /**
     * Try to build a textual representation of XValue when the underlying descriptor is PyDebugValue
     * (PyCharm). This is a best-effort reflective attempt and will return null on any failure.
     */
    @Nullable
    private static String tryReflectPyValueString(XValue xValue) {
        try {
            // Prefer NodeDescriptorProvider -> getDescriptor() path if available
            Object desc = null;
            try {
                if (xValue instanceof com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) {
                    desc = ((com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) xValue).getDescriptor();
                } else {
                    // reflectively try NodeDescriptorProvider
                    Class<?> nodeProviderClass = Class.forName("com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider");
                    if (nodeProviderClass.isInstance(xValue)) {
                        Method gd = nodeProviderClass.getMethod("getDescriptor");
                        desc = gd.invoke(xValue);
                    }
                }
            } catch (Throwable ignored) { }

            // If desc is not present, try other heuristics: the XValue itself might be PyDebugValue
            if (desc == null) {
                // Check if xValue's class is PyDebugValue or wraps it
                Object maybe = xValue;
                // some py implementations wrap descriptor; try to inspect fields by reflection
                // but simplest: check class name
                String clsName = maybe.getClass().getName();
                if (clsName.contains("PyDebugValue") || clsName.contains("pydev")) {
                    desc = maybe;
                }
            }

            if (desc == null) return null;
            if (!isPyDebugValue(desc)) return null;

            Class<?> pyCls = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
            Method getName = pyCls.getMethod("getName");
            Method getValue = pyCls.getMethod("getValue");

            Object name = getName.invoke(desc);
            Object val = getValue.invoke(desc);

            String nameStr = name != null ? name.toString() : "unknown";
            String valStr = val != null ? val.toString() : "null";

            // normalize PyCharm special __exception__ to EXCEPTION for parity with IntelliJ handling
            if ("__exception__".equals(nameStr)) {
                return "EXCEPTION = " + valStr;
            }
            return nameStr + " = " + valStr;
        } catch (Throwable t) {
            // failure is okay; return null to let fallback handle it
            logger.debug("tryReflectPyValueString failed: " + t.getMessage());
            return null;
        }
    }

    // ---------------- Generic helpers to normalize debug values ----------------

    /**
     * Safe handler that inspects `valueObj` either as PyDebugValue (via reflection)
     * or as ValueDescriptorImpl (IntelliJ) and inserts into the provided map.
     *
     * This function avoids referencing IDE-only classes directly (where possible).
     */
    @SuppressWarnings("unchecked")
    public static void handleDebugValue(Object valueObj, Map<String, String> variables) {
        if (valueObj == null) return;
        try {
            // Try PyDebugValue via reflection first
            if (isPyDebugValue(valueObj)) {
                Class<?> pyCls = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
                Method getName = pyCls.getMethod("getName");
                Method getValue = pyCls.getMethod("getValue");
                String name = String.valueOf(getName.invoke(valueObj));
                String val = String.valueOf(getValue.invoke(valueObj));
                if ("__exception__".equals(name)) {
                    variables.put("EXCEPTION", val);
                } else {
                    variables.put(name, val);
                }
                return;
            }

            // Try descriptor path if the object is NodeDescriptorProvider (reflectively)
            try {
                Class<?> nodeProviderClass = Class.forName("com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider");
                if (nodeProviderClass.isInstance(valueObj)) {
                    Method gd = nodeProviderClass.getMethod("getDescriptor");
                    Object descriptor = gd.invoke(valueObj);
                    // descriptor might be ValueDescriptorImpl or PyDebugValue
                    handleDescriptorObject(descriptor, variables);
                    return;
                }
            } catch (Throwable ignored) { }

            // Try ValueDescriptorImpl direct (if present)
            try {
                Class<?> vdiClass = Class.forName("com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl");
                if (vdiClass.isInstance(valueObj)) {
                    Method getName = vdiClass.getMethod("getName");
                    Method calcValueName = vdiClass.getMethod("calcValueName");
                    String name = String.valueOf(getName.invoke(valueObj));
                    String val = String.valueOf(calcValueName.invoke(valueObj));
                    variables.put(name, val);
                    return;
                }
            } catch (Throwable ignored) { }

            // Fallback to toString representation
            variables.put("unknown", valueObj.toString());
        } catch (Throwable t) {
            logger.warn("handleDebugValue error: " + t.getMessage());
        }
    }

    private static void handleDescriptorObject(Object descriptor, Map<String, String> variables) {
        if (descriptor == null) return;
        try {
            // If PyDebugValue
            if (isPyDebugValue(descriptor)) {
                Class<?> pyCls = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
                Method getName = pyCls.getMethod("getName");
                Method getValue = pyCls.getMethod("getValue");
                String name = String.valueOf(getName.invoke(descriptor));
                String val = String.valueOf(getValue.invoke(descriptor));
                if ("__exception__".equals(name)) variables.put("EXCEPTION", val);
                else variables.put(name, val);
                return;
            }

            // If ValueDescriptorImpl
            try {
                Class<?> vdiClass = Class.forName("com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl");
                if (vdiClass.isInstance(descriptor)) {
                    Method getName = vdiClass.getMethod("getName");
                    Method calcValueName = vdiClass.getMethod("calcValueName");
                    String name = String.valueOf(getName.invoke(descriptor));
                    String val = String.valueOf(calcValueName.invoke(descriptor));
                    variables.put(name, val);
                    return;
                }
            } catch (Throwable ignored) { }

            // Last resort
            variables.put("unknown_descriptor", descriptor.toString());
        } catch (Throwable t) {
            logger.warn("handleDescriptorObject error: " + t.getMessage());
        }
    }

    /**
     * A more generalized handler for XValue-like objects - used where code obtains raw objects.
     */
    public static void handleDebugXValue(Object xValue, Map<String, String> variables) {
        try {
            if (xValue == null) return;

            // if NodeDescriptorProvider instance available, try to extract descriptor first (reflectively)
            try {
                Class<?> nodeProviderClass = Class.forName("com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider");
                if (nodeProviderClass.isInstance(xValue)) {
                    Method gd = nodeProviderClass.getMethod("getDescriptor");
                    Object descriptor = gd.invoke(xValue);
                    handleDescriptorObject(descriptor, variables);
                    return;
                }
            } catch (Throwable ignored) { }

            // If this object looks like PyDebugValue
            if (isPyDebugValue(xValue)) {
                Class<?> pyCls = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
                Method getName = pyCls.getMethod("getName");
                Method getValue = pyCls.getMethod("getValue");
                String name = String.valueOf(getName.invoke(xValue));
                String val = String.valueOf(getValue.invoke(xValue));
                if ("__exception__".equals(name)) variables.put("EXCEPTION", val);
                else variables.put(name, val);
                return;
            }

            // If ValueDescriptorImpl
            try {
                Class<?> vdiClass = Class.forName("com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl");
                if (vdiClass.isInstance(xValue)) {
                    Method getName = vdiClass.getMethod("getName");
                    Method calcValueName = vdiClass.getMethod("calcValueName");
                    String name = String.valueOf(getName.invoke(xValue));
                    String val = String.valueOf(calcValueName.invoke(xValue));
                    variables.put(name, val);
                    return;
                }
            } catch (Throwable ignored) { }

            // Fallback: use toString
            variables.put("unknown", String.valueOf(xValue));
        } catch (Throwable t) {
            logger.warn("handleDebugXValue error: " + t.getMessage());
        }
    }
}
