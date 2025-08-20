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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class DebugDataCollector {
    private static final Logger logger = Logger.getInstance(DebugDataCollector.class);

    private static final DebugDataCollector instance = new DebugDataCollector();

    private final List<SnapshotItem> latestSnapshot = new ArrayList<>();
    private final List<StackItem> latestStack = new ArrayList<>();
    private ExceptionDetail latestException = null;

    private DebugDataCollector() { }

    public static DebugDataCollector getInstance() { return instance; }
    public List<SnapshotItem> getSnapshot() { return new ArrayList<>(latestSnapshot); }
    public List<StackItem> getCallStack() { return new ArrayList<>(latestStack); }
    public ExceptionDetail getExceptionDetail() { return latestException; }
    public void clearDebugData() {
        latestSnapshot.clear();
        latestStack.clear();
        latestException = null;
    }

    // ----------------------------------------
    // Call Stack Collection (Java & Python)
    // ----------------------------------------
    public static void collectStackItems(XDebugProcess debugProcess, Consumer<ContextItem> callback) {
        List<StackItem> stackItems = new ArrayList<>();
        XExecutionStack stack = null;
        try {
            stack = debugProcess.getSession().getSuspendContext().getActiveExecutionStack();
        } catch (Throwable t) {
            logger.warn("[DebugDataCollector] Unable to obtain execution stack: " + t.getMessage());
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
                        String functionText = null;
                        try {
                            functionText = extractEnclosingFunctionSafe(debugProcess.getSession().getProject(), pos.getFile(), pos.getLine());
                        } catch (Throwable t) {
                            functionText = "";
                        }
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

    // ----------------------------------------
    // Snapshot Collection (Java & Python)
    // ----------------------------------------
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
                    if (limitReached.get()) { complete(); return; }
                    if (debuggerCalls.get() >= Constants.MAX_CALLS_TO_DEBUGGER) {
                        limitReached.set(true);
                        complete();
                        return;
                    }
                    String varName = children.getName(i);
                    XValue childValue = children.getValue(i);
                    MutableSnapshotItem mutableItem = new MutableSnapshotItem(varName, "unknown", "unavailable", "Local");
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
                                    @Override public void renderValue(@Nullable String value) { sb.append(value); }
                                    @Override public void renderStringValue(@Nullable String value) { sb.append(value); }
                                    @Override public void renderNumericValue(@Nullable String value) { sb.append(value); }
                                    @Override public void renderKeywordValue(@Nullable String value) { sb.append(value); }
                                    @Override public void renderValue(@Nullable String value, @NotNull TextAttributesKey key) { sb.append(value); }
                                    @Override public void renderStringValue(@Nullable String value, @Nullable String ref, int unused) { sb.append(value); }
                                    @Override public void renderComment(@NotNull String comment) { }
                                    @Override public void renderSpecialSymbol(@NotNull String symbol) { sb.append(symbol); }
                                    @Override public void renderError(@NotNull String error) { sb.append(error); }
                                });
                                mutableItem.value = sb.toString();
                                // PYTHON: If value is empty, try PyCharm reflection for PyDebugValue
                                if ((mutableItem.value == null || mutableItem.value.isEmpty()) && isPyCharmEnvironment()) {
                                    String pyRendered = tryReflectPyValueString(childValue);
                                    if (pyRendered != null) mutableItem.value = pyRendered;
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
                        @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                        @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {}
                    }, XValuePlace.TREE);
                }
            }

            @Override public void tooManyChildren(int remaining) { }
            @Override public void setAlreadySorted(boolean alreadySorted) { }
            @Override public void setErrorMessage(@NotNull String errorMessage) { }
            @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { }
            @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) { }

            private void complete() {
                List<SnapshotItem> result = new ArrayList<>();
                for (MutableSnapshotItem item : snapshotItems) result.add(item.toSnapshotItem());
                instance.latestSnapshot.clear();
                instance.latestSnapshot.addAll(result);
                callback.accept(new ContextItem(result, true, ContextItem.Type.SNAPSHOT));
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
                if (children.size() == 0) { onComplete.run(); return; }
                for (int i = 0; i < children.size(); i++) {
                    String childName = children.getName(i);
                    XValue childValue = children.getValue(i);
                    MutableSnapshotItem childItem = new MutableSnapshotItem(childName, "unknown", "unavailable", "Field");
                    parent.children.add(childItem);

                    childValue.computePresentation(new XValueNode() {
                        @Override
                        public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                            try {
                                if (presentation.getType() != null) childItem.type = presentation.getType();
                                StringBuilder sb = new StringBuilder();
                                presentation.renderValue(new XValuePresentation.XValueTextRenderer() {
                                    @Override public void renderValue(@Nullable String value) { sb.append(value); }
                                    @Override public void renderStringValue(@Nullable String value) { sb.append(value); }
                                    @Override public void renderNumericValue(@Nullable String value) { sb.append(value); }
                                    @Override public void renderKeywordValue(@Nullable String value) { sb.append(value); }
                                    @Override public void renderValue(@Nullable String value, @NotNull TextAttributesKey key) { sb.append(value); }
                                    @Override public void renderStringValue(@Nullable String value, @Nullable String ref, int unused) { sb.append(value); }
                                    @Override public void renderComment(@NotNull String comment) {}
                                    @Override public void renderSpecialSymbol(@NotNull String symbol) { sb.append(symbol); }
                                    @Override public void renderError(@NotNull String error) { sb.append(error); }
                                });
                                childItem.value = sb.toString();
                                // PYTHON: If value is empty, try PyCharm reflection for PyDebugValue
                                if ((childItem.value == null || childItem.value.isEmpty()) && isPyCharmEnvironment()) {
                                    String pyRendered = tryReflectPyValueString(childValue);
                                    if (pyRendered != null) childItem.value = pyRendered;
                                }
                            } catch (Exception e) {
                                logger.warn("Error computing child value for " + childItem.name + ": " + e.getMessage());
                                childItem.value = "Value not available";
                            }
                            if (hasChildren) {
                                collectChildren(childValue, childItem, currentDepth + 1, () -> {
                                    if (pending.decrementAndGet() == 0) onComplete.run();
                                });
                            } else {
                                if (pending.decrementAndGet() == 0) onComplete.run();
                            }
                        }
                        @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                        @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {}
                    }, XValuePlace.TREE);
                }
            }
            @Override public void tooManyChildren(int remaining) { onComplete.run(); }
            @Override public void setAlreadySorted(boolean alreadySorted) {}
            @Override public void setErrorMessage(@NotNull String errorMessage) { onComplete.run(); }
            @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { onComplete.run(); }
            @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
        });
    }

    // ----------------------------------------
    // Exception Collection (Java & Python)
    // ----------------------------------------
    public static void collectException(XStackFrame frame, Consumer<ContextItem> callback) {
        frame.computeChildren(new XCompositeNode() {
            @Override
            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                for (int i = 0; i < children.size(); i++) {
                    String name = children.getName(i);
                    XValue value = children.getValue(i);
                    // PYTHON: __exception__ tuple
                    if (isPyCharmEnvironment() && "__exception__".equals(name)) {
                        processPyCharmExceptionTuple(value, frame, callback);
                        return;
                    }
                    // JAVA: Heuristic for exception variable
                    if (isExceptionCandidateSafe(name, value)) {
                        processExceptionSafe(value, frame, callback);
                        return;
                    }
                }
                // Fallback: if no exception found, collect snapshot instead
                collectSnapshot(frame, callback);
            }
        });
    }

    // For PyCharm: __exception__ is a tuple (type, exception object, traceback object)
    private static void processPyCharmExceptionTuple(XValue exceptionTuple, XStackFrame frame, Consumer<ContextItem> callback) {
        exceptionTuple.computeChildren(new XCompositeNode() {
            @Override
            public void addChildren(@NotNull XValueChildrenList tupleChildren, boolean last) {
                String type = null, message = null, stackTrace = null;
                // tuple: [0]=type, [1]=exception object, [2]=traceback
                XValue typeVal = tupleChildren.size() > 0 ? tupleChildren.getValue(0) : null;
                if (typeVal != null) {
                    typeVal.computePresentation(new XValueNode() {
                        @Override
                        public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                            type = renderPresentationText(presentation);
                        }
                    }, XValuePlace.TREE);
                }
                XValue exObj = tupleChildren.size() > 1 ? tupleChildren.getValue(1) : null;
                if (exObj != null) {
                    exObj.computeChildren(new XCompositeNode() {
                        @Override
                        public void addChildren(@NotNull XValueChildrenList exChildren, boolean last) {
                            for (int j = 0; j < exChildren.size(); j++) {
                                if ("args".equals(exChildren.getName(j))) {
                                    XValue argsVal = exChildren.getValue(j);
                                    argsVal.computePresentation(new XValueNode() {
                                        @Override
                                        public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                                            message = renderPresentationText(presentation);
                                        }
                                    }, XValuePlace.TREE);
                                }
                            }
                        }
                    });
                }
                XValue tbObj = tupleChildren.size() > 2 ? tupleChildren.getValue(2) : null;
                if (tbObj != null) {
                    tbObj.computeChildren(new XCompositeNode() {
                        @Override
                        public void addChildren(@NotNull XValueChildrenList tbChildren, boolean last) {
                            StringBuilder sb = new StringBuilder();
                            for (int k = 0; k < tbChildren.size(); k++) {
                                XValue tbChild = tbChildren.getValue(k);
                                String tbName = tbChildren.getName(k);
                                tbChild.computePresentation(new XValueNode() {
                                    @Override
                                    public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                                        sb.append(tbName).append(": ").append(renderPresentationText(presentation)).append("\n");
                                    }
                                }, XValuePlace.TREE);
                            }
                            stackTrace = sb.toString();
                        }
                    });
                }
                ExceptionDetail detail = new ExceptionDetail(
                        message, type, stackTrace,
                        frame.getSourcePosition() != null ? frame.getSourcePosition().getFile().getPath() : "unknown",
                        frame.getSourcePosition() != null ? frame.getSourcePosition().getLine() : -1
                );
                instance.latestException = detail;
                callback.accept(new ContextItem(detail, true, ContextItem.Type.EXCEPTION));
            }
        });
    }

    private static boolean isExceptionCandidateSafe(String name, XValue value) {
        try {
            boolean nameSuggests = (name != null && name.toLowerCase().contains("exception"));
            String type = getExceptionType(value);
            boolean typeSuggests = type != null && type.toLowerCase().contains("exception");
            boolean descriptorSuggests = false;
            try {
                Method getDescriptorMethod = null;
                if (value.getClass().getName().contains("NodeDescriptorProvider")) {
                    Object desc = value.getClass().getMethod("getDescriptor").invoke(value);
                    descriptorSuggests = desc != null && desc.getClass().getName().contains("ValueDescriptorImpl");
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
                if (value.getClass().getName().contains("NodeDescriptorProvider")) {
                    descriptorObj = value.getClass().getMethod("getDescriptor").invoke(value);
                }
            } catch (Throwable ignored) {}
            String type = getExceptionType(value);
            ExceptionState state = new ExceptionState((descriptorObj instanceof com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl)
                    ? (com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl)descriptorObj : null, type, frame);

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
                        String base = (descriptorObj != null && descriptorObj instanceof com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl)
                                ? ((com.intellij.debugger.ui.impl.watch.ValueDescriptorImpl)descriptorObj).getValue().toString()
                                : "null";
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

    // ----------------------------------------
    // PyCharm Reflection Helpers (Python only)
    // ----------------------------------------
    private static boolean isPyCharmEnvironment() {
        try { Class.forName("com.jetbrains.python.debugger.PyDebugValue"); return true; }
        catch (Throwable t) { return false; }
    }

    @Nullable
    private static String tryReflectPyValueString(XValue xValue) {
        try {
            Object desc = null;
            try {
                if (xValue.getClass().getName().contains("PyDebugValue")) desc = xValue;
                else if (xValue.getClass().getName().contains("NodeDescriptorProvider")) {
                    Method getDescriptor = xValue.getClass().getMethod("getDescriptor");
                    desc = getDescriptor.invoke(xValue);
                }
            } catch (Throwable ignored) {}
            if (desc == null) return null;
            Class<?> pyCls = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
            Method getValue = pyCls.getMethod("getValue");
            Object val = getValue.invoke(desc);
            if (val != null) return val.toString();
            return null;
        } catch (Throwable t) {
            logger.debug("tryReflectPyValueString failed: " + t.getMessage());
            return null;
        }
    }

    // ----------------------------------------
    // Function Extraction (Java only)
    // ----------------------------------------
    @Nullable
    private static String extractEnclosingFunctionSafe(@NotNull Project project, @NotNull VirtualFile file, int line) {
        try {
            // Only run this block in environments with PSI available (IntelliJ Java)
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

    // -----------------
    // End of Main Class
    // -----------------
}
