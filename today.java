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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * DebugDataCollector — single implementation that works across IntelliJ IDEA (Java)
 * and PyCharm (Python) without compile-time deps on python debugger classes.
 *
 * Key points:
 * 1) Detects PyCharm at runtime via presence of Py* classes (reflection only).
 * 2) Uses only XDebugger generic APIs for traversal, with robust async handling.
 * 3) Exception handling for PyCharm walks __exception__ tuple and tb_next chain.
 * 4) All python-specific calls are guarded so they never crash IntelliJ-only IDEs.
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

    public static DebugDataCollector getInstance() { return instance; }

    public List<SnapshotItem> getSnapshot() { return new ArrayList<>(latestSnapshot); }

    public List<StackItem> getCallStack() { return new ArrayList<>(latestStack); }

    public ExceptionDetail getExceptionDetail() { return latestException; }

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
            if (debugProcess.getSession() != null && debugProcess.getSession().getSuspendContext() != null) {
                stack = debugProcess.getSession().getSuspendContext().getActiveExecutionStack();
            }
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
            Class<?> psiMethodClass;
            try { psiMethodClass = Class.forName("com.intellij.psi.PsiMethod"); } catch (Throwable ignored) { return null; }

            Object functionElement = psiTreeUtilClass.getMethod("getParentOfType", psiElementClass, Class.class)
                    .invoke(null, elementAt, psiMethodClass);
            if (functionElement == null) return null;

            String fullText = (String) functionElement.getClass().getMethod("getText").invoke(functionElement);
            if (fullText == null) return null;

            String[] lines = fullText.split("\n");
            int prefix = Constants.ENCLOSING_FUNCTION_PREFIX_LINES, suffix = Constants.ENCLOSING_FUNCTION_SUFFIX_LINES;
            if (lines.length <= prefix + suffix) return fullText;

            Object textRange = functionElement.getClass().getMethod("getTextRange").invoke(functionElement);
            int functionStartOffset = (int) textRange.getClass().getMethod("getStartOffset").invoke(textRange);
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

    // ---------------- Snapshot Collection ----------------

    public static void collectSnapshot(XStackFrame currentStackFrame, Consumer<ContextItem> callback) {
        List<MutableSnapshotItem> snapshotItems = new ArrayList<>();
        AtomicBoolean limitReached = new AtomicBoolean(false);

        try {
            currentStackFrame.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                    AtomicInteger pending = new AtomicInteger(children.size());
                    if (children.size() == 0) complete();
                    for (int i = 0; i < children.size(); i++) {
                        if (limitReached.get() || new Gson().toJson(snapshotItems).length() > Constants.MAX_SNAPSHOT_JSON_SIZE_BYTES)
                        { limitReached.set(true); complete(); return; }

                        String varName = children.getName(i);
                        XValue childValue = children.getValue(i);
                        MutableSnapshotItem mutableItem = new MutableSnapshotItem(varName, "unknown", "unavailable", "Local");
                        snapshotItems.add(mutableItem);

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
                                    mutableItem.value = e.getMessage() != null && e.getMessage().contains("not yet calculated")
                                            ? "Calculating..." : "Value not available";
                                } finally {
                                    if (hasChildren) {
                                        collectChildren(childValue, mutableItem, 0, () -> { if (pending.decrementAndGet() == 0) complete(); });
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
                @Override public void setErrorMessage(@NotNull String errorMessage) { complete(); }
                @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { complete(); }
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
                                } catch (Exception e) {
                                    logger.warn("Error computing child value for " + childItem.name + ": " + e.getMessage());
                                    childItem.value = "Value not available";
                                } finally {
                                    if (hasChildren) {
                                        collectChildren(childValue, childItem, currentDepth + 1, () -> { if (pending.decrementAndGet() == 0) onComplete.run(); });
                                    } else {
                                        if (pending.decrementAndGet() == 0) onComplete.run();
                                    }
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

    // ---------------- Exception Detection & Collection ----------------

    public static void collectException(XStackFrame frame, Consumer<ContextItem> callback) {
        try {
            frame.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                    // In PyCharm, __exception__ is the ground truth.
                    for (int i = 0; i < children.size(); i++) {
                        String name = children.getName(i);
                        XValue value = children.getValue(i);
                        if (isPyCharmEnvironment() && "__exception__".equals(name)) {
                            processPyCharmExceptionTuple(value, frame, callback);
                            return;
                        }
                    }
                    // Otherwise, use generic heuristics (covers Java and others)
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
            });
        } catch (Throwable t) {
            logger.warn("collectException outer error: " + t.getMessage());
            try { collectSnapshot(frame, callback); } catch (Throwable ignored) {}
        }
    }

    // PyCharm: __exception__ is a tuple (type, exception object, traceback object)
    private static void processPyCharmExceptionTuple(XValue exceptionTuple, XStackFrame frame, Consumer<ContextItem> callback) {
        try {
            exceptionTuple.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList tupleChildren, boolean last) {
                    Map<Integer, XValue> parts = new ConcurrentHashMap<>();
                    for (int i = 0; i < tupleChildren.size(); i++) parts.put(i, tupleChildren.getValue(i));

                    // Evaluate type and message concurrently via presentation; traceback is walked explicitly.
                    CountDownLatch latch = new CountDownLatch(2);
                    final String[] type = {"unknown"};
                    final String[] message = {""};

                    // [0] type
                    XValue typeVal = parts.get(0);
                    if (typeVal != null) {
                        try {
                            typeVal.computePresentation(new XValueNode() {
                                @Override public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                                    String t = presentation.getType();
                                    if (t == null || t.isEmpty()) t = renderPresentationText(presentation);
                                    type[0] = (t == null || t.isEmpty()) ? "unknown" : t;
                                    latch.countDown();
                                }
                                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                                @Override public void setPresentation(@Nullable Icon icon, @NotNull String t, @NotNull String v, boolean hasChildren) { type[0] = t; latch.countDown(); }
                            }, XValuePlace.TREE);
                        } catch (Throwable ignored) { latch.countDown(); }
                    } else latch.countDown();

                    // [1] exception object -> message via args
                    XValue exObj = parts.get(1);
                    if (exObj != null) {
                        try {
                            exObj.computeChildren(new XCompositeNode() {
                                @Override public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                                    // find 'args'
                                    AtomicInteger pending = new AtomicInteger(children.size());
                                    if (children.size() == 0) { latch.countDown(); return; }
                                    for (int i = 0; i < children.size(); i++) {
                                        String nm = children.getName(i);
                                        XValue val = children.getValue(i);
                                        if ("args".equals(nm)) {
                                            val.computePresentation(new XValueNode() {
                                                @Override public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation p, boolean hasChildren) {
                                                    String rendered = renderPresentationText(p);
                                                    if (rendered != null) message[0] = rendered;
                                                    latch.countDown();
                                                }
                                                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator f) {}
                                                @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) { message[0] = value; latch.countDown(); }
                                            }, XValuePlace.TREE);
                                            // break: we only need args
                                        }
                                        if (pending.decrementAndGet() == 0) { /* if no args found */ if (latch.getCount() > 0) latch.countDown(); }
                                    }
                                }
                            });
                        } catch (Throwable ignored) { latch.countDown(); }
                    } else latch.countDown();

                    // Walk traceback chain
                    final String[] stackTrace = {""};
                    XValue tb = parts.get(2);
                    if (tb != null) stackTrace[0] = walkTraceback(tb, new StringBuilder());

                    // Finish after type & message are ready (traceback already computed synchronously via callbacks)
                    try { latch.await(1500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}

                    ExceptionDetail detail = new ExceptionDetail(
                            message[0],
                            type[0],
                            stackTrace[0],
                            frame.getSourcePosition() != null ? frame.getSourcePosition().getFile().getPath() : "unknown",
                            frame.getSourcePosition() != null ? frame.getSourcePosition().getLine() : -1
                    );
                    instance.latestException = detail;
                    callback.accept(new ContextItem(detail, true, ContextItem.Type.EXCEPTION));
                }
            });
        } catch (Throwable t) {
            logger.warn("processPyCharmExceptionTuple failed: " + t.getMessage());
            collectSnapshot(frame, callback);
        }
    }

    private static String walkTraceback(XValue tbValue, StringBuilder sb) {
        try {
            tbValue.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                    XValue frameVal = null;
                    XValue nextVal = null;
                    for (int i = 0; i < children.size(); i++) {
                        String nm = children.getName(i);
                        if ("tb_frame".equals(nm)) frameVal = children.getValue(i);
                        else if ("tb_next".equals(nm)) nextVal = children.getValue(i);
                    }
                    if (frameVal != null) sb.append(extractFrameInfo(frameVal)).append("\n");
                    if (nextVal != null) sb.append(walkTraceback(nextVal, new StringBuilder()));
                }
            });
        } catch (Throwable t) {
            logger.debug("walkTraceback failed: " + t.getMessage());
        }
        return sb.toString();
    }

    private static String extractFrameInfo(XValue frameVal) {
        final StringBuilder info = new StringBuilder();
        try {
            frameVal.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList frameChildren, boolean last) {
                    String file = null, line = null, codeObj = null;
                    for (int i = 0; i < frameChildren.size(); i++) {
                        String name = frameChildren.getName(i);
                        XValue v = frameChildren.getValue(i);
                        if ("f_lineno".equals(name)) line = safePresentation(v);
                        else if ("f_code".equals(name)) codeObj = safePresentation(v);
                        else if ("f_globals".equals(name) && file == null) file = safePresentation(v); // best effort
                    }
                    info.append(codeObj != null ? codeObj : "<code>")
                        .append(" : ")
                        .append(line != null ? line : "?");
                }
            });
        } catch (Throwable t) {
            logger.debug("extractFrameInfo failed: " + t.getMessage());
        }
        return info.toString();
    }

    // Generic (Java or others) exception path
    private static boolean isExceptionCandidateSafe(String name, XValue value) {
        try {
            boolean nameSuggests = (name != null && name.toLowerCase().contains("exception"));
            String type = getExceptionType(value);
            boolean typeSuggests = type != null && type.toLowerCase().contains("exception");
            boolean descriptorSuggests = false;
            try {
                Method getDescriptorMethod;
                Class<?> nodeProviderClass = Class.forName("com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider");
                if (nodeProviderClass.isInstance(value)) {
                    getDescriptorMethod = nodeProviderClass.getMethod("getDescriptor");
                    Object desc = getDescriptorMethod.invoke(value);
                    descriptorSuggests = isPyDebugValue(desc);
                }
            } catch (Throwable ignored) {}
            return nameSuggests || typeSuggests || descriptorSuggests;
        } catch (Throwable t) {
            logger.debug("isExceptionCandidateSafe error: " + t.getMessage());
            return false;
        }
    }

    private static void processExceptionSafe(XValue value, XStackFrame frame, Consumer<ContextItem> callback) {
        try {
            final ExceptionState state = new ExceptionState(value, getExceptionType(value), frame);
            value.computeChildren(new XCompositeNode() {
                @Override
                public void addChildren(@NotNull XValueChildrenList exChildren, boolean last) {
                    AtomicInteger pending = new AtomicInteger(exChildren.size());
                    for (int j = 0; j < exChildren.size(); j++) {
                        String fieldName = exChildren.getName(j);
                        XValue fieldValue = exChildren.getValue(j);
                        if ("detailMessage".equals(fieldName) || "message".equalsIgnoreCase(fieldName) || "args".equals(fieldName)) {
                            processDetailMessage(fieldValue, state, callback, pending);
                        } else if ("stackTrace".equals(fieldName) || fieldName.toLowerCase().contains("traceback") || "__traceback__".equals(fieldName)) {
                            processStackTrace(fieldValue, state, callback, pending);
                        } else if (pending.decrementAndGet() == 0) {
                            if (state.isComplete()) completeExceptionState(state, callback); else collectSnapshot(frame, callback);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            logger.warn("processExceptionSafe error: " + t.getMessage());
            collectSnapshot(frame, callback);
        }
    }

    private static void processDetailMessage(XValue fieldValue, ExceptionState state, Consumer<ContextItem> callback, AtomicInteger pending) {
        try {
            fieldValue.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    String message = renderPresentationText(presentation);
                    if (!"Collecting data...".equals(message)) state.setDetailMessage(message);
                    if (pending.decrementAndGet() == 0) completeExceptionState(state, callback);
                }
                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {
                    state.setDetailMessage(value);
                    if (pending.decrementAndGet() == 0) completeExceptionState(state, callback);
                }
            }, XValuePlace.TREE);
        } catch (Throwable t) { logger.debug("processDetailMessage failed: " + t.getMessage()); if (pending.decrementAndGet() == 0) completeExceptionState(state, callback); }
    }

    private static void processStackTrace(XValue fieldValue, ExceptionState state, Consumer<ContextItem> callback, AtomicInteger pending) {
        try {
            fieldValue.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    state.setStackTrace(renderPresentationText(presentation));
                    if (pending.decrementAndGet() == 0) completeExceptionState(state, callback);
                }
                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {
                    state.setStackTrace(value);
                    if (pending.decrementAndGet() == 0) completeExceptionState(state, callback);
                }
            }, XValuePlace.TREE);
        } catch (Throwable t) { logger.debug("processStackTrace failed: " + t.getMessage()); if (pending.decrementAndGet() == 0) completeExceptionState(state, callback); }
    }

    private static String getExceptionType(XValue value) {
        final String[] type = {"unknown"};
        try {
            value.computePresentation(new XValueNode() {
                @Override
                public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                    String typeStr = presentation.getType();
                    if (typeStr != null && !typeStr.isEmpty()) type[0] = typeStr;
                }
                @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                @Override public void setPresentation(@Nullable Icon icon, @NotNull String t, @NotNull String v, boolean hasChildren) { type[0] = t; }
            }, XValuePlace.TREE);
        } catch (Throwable t) { logger.debug("getExceptionType failed: " + t.getMessage()); }
        return type[0];
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
                Class<?> nodeProviderClass = Class.forName("com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider");
                if (nodeProviderClass.isInstance(xValue)) {
                    Method gd = nodeProviderClass.getMethod("getDescriptor");
                    desc = gd.invoke(xValue);
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






import com.intellij.debugger.ui.breakpoints.JavaExceptionBreakpointType;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.xdebugger.*;
import com.intellij.xdebugger.breakpoints.XBreakpoint;
import com.intellij.xdebugger.frame.*;
import com.intellij.openapi.components.ServiceManager;
import org.jetbrains.annotations.NotNull;
import org.dell.AIPPChatContentManager;
import orgdell.LowerPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import static org.dell.TOOL_CHAT;
import org.dell.GenerateInlayForException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DebugAttacher implements StartupActivity {
    private static final Logger logger = Logger.getInstance(DebugAttacher.class);
    private static final Map<XDebugSession, DebugAttacher> sessionMap = new HashMap<>();
    private XDebugProcess currentDebugProcess;
    private static Project project;
    public boolean exceptionVisited = false;
    private int lastExceptionLine = -1;

    @Override
    public void runActivity(@NotNull Project project) {
        System.out.println("Activity Started");
        DebugAttacher.project = project;
        attachDebugStartListener(project);
    }

    public void sessionStopped() {
        System.out.println("[DEBUG] Debug session stopped - cleaning up");
        DebugDataCollector.getInstance().clearDebugData();

        if (currentDebugProcess != null) {
            ProjectManager.getInstance().getDefaultProject().getMessageBus()
                    .syncPublisher(XDebuggerManager.TOPIC)
                    .processStopped(currentDebugProcess);

            exceptionVisited = false;
            lastExceptionLine = -1;

            ApplicationManager.getApplication().invokeLater(() -> {
                Editor activeEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                if (activeEditor != null) {
                    GenerateInlayForException.Companion.clearInlays(activeEditor);
                }
            });
        } else {
            logger.warn("Debug session stopped with no active debug process");
        }
        currentDebugProcess = null;
    }

    private void attachDebugStartListener(Project project) {
        project.getMessageBus().connect().subscribe(XDebuggerManager.TOPIC, new XDebuggerManagerListener() {
            @Override
            public void processStarted(@NotNull XDebugProcess debugProcess) {
                currentDebugProcess = debugProcess;
                sessionMap.put(debugProcess.getSession(), DebugAttacher.this);

                System.out.println("Debug session started for process: " + debugProcess.getClass().getName());

                // Detect language backend
                if (isJavaProcess(debugProcess)) {
                    enableExceptionBreakpoints(project);
                }
                attachDebugBreakListener(debugProcess);
            }
        });
    }

    private boolean isJavaProcess(XDebugProcess process) {
        return process.getClass().getName().toLowerCase().contains("java");
    }

    private boolean isPythonProcess(XDebugProcess process) {
        return process.getClass().getName().toLowerCase().contains("python");
    }

    private void enableExceptionBreakpoints(Project project) {
        var breakpoints = XDebuggerManager.getInstance(project).getBreakpointManager().getAllBreakpoints();
        for (XBreakpoint<?> bp : breakpoints) {
            if (bp.getType() instanceof JavaExceptionBreakpointType) {
                bp.setEnabled(true);
            }
        }
    }

    private void attachDebugBreakListener(@NotNull XDebugProcess debugProcess) {
        debugProcess.getSession().addSessionListener(new XDebugSessionListener() {
            @Override
            public void sessionPaused() {
                ApplicationManager.getApplication().runReadAction(() -> {
                    if (isJavaProcess(debugProcess)) {
                        handleJavaPaused();
                    } else if (isPythonProcess(debugProcess)) {
                        handlePythonPaused();
                    } else {
                        System.out.println("Unknown debug process type, skipping special handling.");
                    }
                });
            }

            @Override
            public void sessionStopped() {
                sessionMap.remove(currentDebugProcess.getSession());
                DebugAttacher.this.sessionStopped();
            }
        });
    }

    private void handleJavaPaused() {
        XBreakpoint<?>[] breakpoints = XDebuggerManager.getInstance(project)
                .getBreakpointManager()
                .getAllBreakpoints();

        boolean hasExceptionBreakpoint = false;
        for (XBreakpoint<?> bp : breakpoints) {
            if (bp.getType() instanceof JavaExceptionBreakpointType && bp.isEnabled()) {
                hasExceptionBreakpoint = true;
                break;
            }
        }

        if (hasExceptionBreakpoint) {
            Editor activeEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
            if (activeEditor != null) {
                int currentLine = activeEditor.getCaretModel().getLogicalPosition().line + 1;
                if (currentLine != lastExceptionLine) {
                    exceptionVisited = false;
                    lastExceptionLine = currentLine;
                }
                getDebugInfoFor("exception");
            }
        }
    }

    private void handlePythonPaused() {
        // Python debugger does not use JavaExceptionBreakpointType
        // but we can still attempt to collect snapshot, callstack, and exception
        getDebugInfoFor("exception");
    }

    public static Object[] isPaused(Project project) {
        try {
            XDebugSession session = XDebuggerManager.getInstance(project).getCurrentSession();
            if (session != null) {
                return new Object[]{session, session.getCurrentStackFrame(), session.isPaused()};
            }
            for (Project openProject : ProjectManager.getInstance().getOpenProjects()) {
                if (openProject.equals(project)) continue;
                session = XDebuggerManager.getInstance(openProject).getCurrentSession();
                if (session != null) {
                    return new Object[]{session, session.getCurrentStackFrame(), session.isPaused()};
                }
            }
            return new Object[]{null, null, false};
        } catch (Exception e) {
            return new Object[]{null, null, false};
        }
    }

    public static Map<String, Boolean> getDefaultDebugItems() {
        Map<String, Boolean> defaultItems = new HashMap<>();
        defaultItems.put("callstack", false);
        defaultItems.put("snapshot", false);
        defaultItems.put("exception", false);
        return defaultItems;
    }

    private static void appendSnapshotItem(StringBuilder builder, SnapshotItem item, int depth) {
        if (item.getName().equals("value") || item.getName().equals("coder") ||
            item.getName().equals("hash") || item.getName().equals("hashIsZero")) {
            return;
        }

        String indent = "  ".repeat(depth);
        builder.append(indent).append(item.getName()).append(": ").append(item.getValue());
        if (!item.getChildren().isEmpty()) {
            builder.append("\n").append(indent).append("Children:");
            for (SnapshotItem child : item.getChildren()) {
                builder.append("\n");
                appendSnapshotItem(builder, child, depth + 1);
            }
        }
    }

    public static String getDebugInfoFor(String command) {
        Object[] debugInfo = isPaused(ProjectManager.getInstance().getDefaultProject());
        XDebugSession session = (XDebugSession) debugInfo[0];
        XStackFrame frame = (XStackFrame) debugInfo[1];
        boolean paused = (boolean) debugInfo[2];

        if (session == null || !paused) {
            System.out.println("No active debug session or not paused");
            return null;
        }
        if (frame == null) {
            System.out.println("No current stack frame");
            return null;
        }

        try {
            if ("snapshot".equals(command)) {
                DebugDataCollector.collectSnapshot(frame, snapshot -> {});
                Thread.sleep(100);
                List<SnapshotItem> snapshotItems = DebugDataCollector.getInstance().getSnapshot();
                if (snapshotItems == null || snapshotItems.isEmpty()) return null;
                StringBuilder snapshotBuilder = new StringBuilder();
                for (SnapshotItem item : snapshotItems) {
                    appendSnapshotItem(snapshotBuilder, item, 0);
                }
                return snapshotBuilder.toString().trim();

            } else if ("callstack".equals(command)) {
                DebugDataCollector.collectStackItems(session.getDebugProcess(), stack -> {});
                Thread.sleep(100);
                List<StackItem> stackItems = DebugDataCollector.getInstance().getCallStack();
                if (stackItems == null || stackItems.isEmpty()) return null;
                StringBuilder stackBuilder = new StringBuilder();
                for (StackItem item : stackItems) {
                    stackBuilder.append(item.getFunction())
                            .append(" at ").append(item.getFilePath())
                            .append(":").append(item.getLine()).append("\n");
                }
                return stackBuilder.toString().trim();

            } else if ("exception".equals(command)) {
                DebugAttacher debugAttacher = sessionMap.get(session);
                DebugDataCollector.collectException(frame, exception -> {
                    if (exception.hasData()) {
                        Editor activeEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                        if (activeEditor != null) {
                            int offset = activeEditor.getDocument().getLineStartOffset(
                                    ((ExceptionDetail) exception.getData()).getLineNumber()
                            );
                            ApplicationManager.getApplication().invokeLater(() -> {
                                WriteCommandAction.runWriteCommandAction(project, () -> {
                                    ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_CHAT);
                                    if (toolWindow != null) {
                                        toolWindow.show();
                                    }
                                    LowerPanel lowerPanel = ServiceManager.getService(project, AIPPChatContentManager.class).getLowerPanel();
                                    GenerateInlayForException.Companion.generateInlay(
                                            "<<<<<< Ask Code.i to explain Exception >>>>>",
                                            "exception",
                                            activeEditor,
                                            offset,
                                            lowerPanel,
                                            debugAttacher
                                    );
                                });
                            });
                        }
                    }
                });

                ExceptionDetail ex = DebugDataCollector.getInstance().getExceptionDetail();
                if (ex == null || (ex.getMessage() == null && ex.getStackTrace() == null)) {
                    return null;
                }
                return (ex.getMessage() + "\n" + ex.getStackTrace()).trim();
            }
        } catch (Exception e) {
            System.out.println("Error collecting debug info: " + e.getMessage());
        }
        return null;
    }
}
