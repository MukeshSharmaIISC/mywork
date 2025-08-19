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
import org.dell.Constants;

import javax.swing.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Optimized DebugDataCollector: works for IntelliJ (Java) and PyCharm (Python).
 * - For PyCharm, uses robust detection of __exception__ and enhanced snapshot logic for collections.
 */
public class DebugDataCollector {

    private static final Logger logger = Logger.getInstance(DebugDataCollector.class);

    private static final DebugDataCollector instance = new DebugDataCollector();

    private final List<SnapshotItem> latestSnapshot = new ArrayList<>();
    private final List<StackItem> latestStack = new ArrayList<>();
    private ExceptionDetail latestException = null;

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

    // ---------------- Stack Collection ----------------

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
            @Override public void errorOccurred(@NotNull String errorMessage) { callback.accept(new ContextItem(stackItems, false, ContextItem.Type.STACK)); }
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

    // ---------------- Children & Snapshot Collection ----------------

    private static void collectChildren(XValue value, MutableSnapshotItem parent, int currentDepth, Runnable onComplete) {
        if (currentDepth >= Constants.MAX_DEPTH_OF_NESTED_VARIABLES) { onComplete.run(); return; }
        try {
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
                                    childItem.value = renderPresentationText(presentation);

                                    if ((childItem.value == null || childItem.value.isEmpty()) && isPyCharmEnvironment()) {
                                        String pyRendered = tryReflectPyValueString(childValue);
                                        if (pyRendered != null) childItem.value = pyRendered;
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

                        childValue.computePresentation(new XValueNode() {
                            @Override
                            public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                                try {
                                    if (presentation.getType() != null) mutableItem.type = presentation.getType();
                                    mutableItem.value = renderPresentationText(presentation);

                                    if ((mutableItem.value == null || mutableItem.value.isEmpty()) && isPyCharmEnvironment()) {
                                        String pyRendered = tryReflectPyValueString(childValue);
                                        if (pyRendered != null) mutableItem.value = pyRendered;
                                    }
                                } catch (Exception e) {
                                    logger.warn("Error computing value for " + mutableItem.name + ": " + e.getMessage());
                                    mutableItem.value = "Value not available";
                                    if (e.getMessage() != null && e.getMessage().contains("not yet calculated"))
                                        mutableItem.value = "Calculating...";
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
                @Override public void tooManyChildren(int remaining) {}
                @Override public void setAlreadySorted(boolean alreadySorted) {}
                @Override public void setErrorMessage(@NotNull String errorMessage) {}
                @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) {}
                @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}

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
            List<SnapshotItem> result = new ArrayList<>();
            for (MutableSnapshotItem item : snapshotItems) result.add(item.toSnapshotItem());
            instance.latestSnapshot.clear();
            instance.latestSnapshot.addAll(result);
            callback.accept(new ContextItem(result, false, ContextItem.Type.SNAPSHOT));
        }
    }

    // ---------------- Exception Detection & Collection ----------------

    public static void collectException(XStackFrame frame, Consumer<ContextItem> callback) {
        try {
            frame.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                    for (int i = 0; i < children.size(); i++) {
                        String name = children.getName(i);
                        XValue value = children.getValue(i);
                        // For PyCharm, always check for __exception__
                        if (isPyCharmEnvironment() && "__exception__".equals(name)) {
                            processPyCharmExceptionTuple(value, frame, callback);
                            return;
                        } else if (isExceptionCandidateSafe(name, value)) {
                            processExceptionSafe(value, frame, callback);
                            return;
                        }
                    }
                    // fallback: nothing looked like exception -> collect snapshot
                    collectSnapshot(frame, callback);
                }
                @Override public void tooManyChildren(int remaining) {}
                @Override public void setAlreadySorted(boolean alreadySorted) {}
                @Override public void setErrorMessage(@NotNull String errorMessage) {}
                @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) {}
                @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
            });
        } catch (Throwable t) {
            logger.warn("collectException outer error: " + t.getMessage());
            try { collectSnapshot(frame, callback); } catch (Throwable ignored) {}
        }
    }

    // For PyCharm: __exception__ is a tuple (type, exception object, traceback object)
    private static void processPyCharmExceptionTuple(XValue exceptionTuple, XStackFrame frame, Consumer<ContextItem> callback) {
        exceptionTuple.computeChildren(new XCompositeNode() {
            @Override
            public void addChildren(@NotNull XValueChildrenList tupleChildren, boolean last) {
                String type = null, message = null, stackTrace = null;
                // tuple: [0]=type, [1]=exception object, [2]=traceback
                for (int i = 0; i < tupleChildren.size(); i++) {
                    String childName = tupleChildren.getName(i);
                    XValue childValue = tupleChildren.getValue(i);
                    if (i == 0) type = extractTypeString(childValue);
                    else if (i == 1) message = extractExceptionMessage(childValue);
                    else if (i == 2) stackTrace = extractTraceback(childValue);
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

    // Heuristics-safe check for exception candidate
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
            } catch (Throwable t) { descriptorObj = null; }

            String type = getExceptionType(value);
            ExceptionState state = new ExceptionState(descriptorObj != null ? descriptorObj : value, type, frame);

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
                        String base = extractBaseMessageSafe(descriptorObj, value);
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

    // ---------------- PyCharm Reflective Helpers ----------------

    private static boolean isPyCharmEnvironment() {
        try { Class.forName("com.jetbrains.python.debugger.PyDebugValue"); return true; }
        catch (Throwable t) { return false; }
    }

    private static boolean isPyDebugValue(Object o) {
        if (o == null) return false;
        try { Class<?> cls = Class.forName("com.jetbrains.python.debugger.PyDebugValue"); return cls.isInstance(o); }
        catch (Throwable t) { return false; }
    }

    @Nullable
    private static String tryReflectPyValueString(XValue xValue) {
        try {
            Object desc = null;
            try {
                if (xValue instanceof com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider)
                    desc = ((com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider) xValue).getDescriptor();
                else {
                    Class<?> nodeProviderClass = Class.forName("com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider");
                    if (nodeProviderClass.isInstance(xValue)) {
                        Method gd = nodeProviderClass.getMethod("getDescriptor");
                        desc = gd.invoke(xValue);
                    }
                }
            } catch (Throwable ignored) {}
            if (desc == null) {
                Object maybe = xValue;
                String clsName = maybe.getClass().getName();
                if (clsName.contains("PyDebugValue") || clsName.contains("pydev")) desc = maybe;
            }
            if (desc == null || !isPyDebugValue(desc)) return null;

            Class<?> pyCls = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
            Method getName = pyCls.getMethod("getName");
            Method getValue = pyCls.getMethod("getValue");
            Object name = getName.invoke(desc);
            Object val = getValue.invoke(desc);

            String nameStr = name != null ? name.toString() : "unknown";
            String valStr = val != null ? val.toString() : "null";

            if ("__exception__".equals(nameStr)) return "EXCEPTION = " + valStr;
            return nameStr + " = " + valStr;
        } catch (Throwable t) {
            logger.debug("tryReflectPyValueString failed: " + t.getMessage());
            return null;
        }
    }
}
