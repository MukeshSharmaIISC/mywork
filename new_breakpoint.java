package org.dell..debugAssist;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.lookup.PrioritizedLookupElement;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.psi.PsiDocumentManager;

import org.jetbrains.annotations.NotNull;
import org.dell..chat.ChatApiCallService;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.Locale;

import static org.dell..util.Constants.SERVER_TIMEOUT;

public class BreakpointCompletionProvider extends CompletionContributor {

    private static final Logger logger = Logger.getInstance(BreakpointCompletionProvider.class);

    public BreakpointCompletionProvider() {

        extend(CompletionType.BASIC,
                PlatformPatterns.psiElement().inFile(PlatformPatterns.psiFile(PsiCodeFragment.class)),
                new CompletionProvider<CompletionParameters>() {
                    @Override
                    protected void addCompletions(@NotNull CompletionParameters parameters,
                                                  @NotNull ProcessingContext context,
                                                  @NotNull CompletionResultSet resultSet) {

                        PsiElement element = parameters.getPosition();

                        PsiFile fragmentFile = ApplicationManager.getApplication().runReadAction((Computable<PsiFile>) element::getContainingFile);

                        if (!(fragmentFile instanceof PsiCodeFragment fragment)) {
                            logger.info("[DEBUG] Not a PsiCodeFragment, exiting");
                            return;
                        }

                        PsiElement contextElement = fragment.getContext();
                        if (contextElement == null) return;

                        PsiFile containingFile = contextElement.getContainingFile();
                        if (containingFile == null) return;

                        Project project = containingFile.getProject();
                        if (project == null) return;

                        Document mainDocument = PsiDocumentManager.getInstance(project).getDocument(containingFile);
                        if (mainDocument == null) return;

                        // Only show suggestions inside Breakpoint Condition UI
                        try {
                            if (!isBreakpointConditionEditor(parameters, fragment, contextElement)) {
                                logger.info("[DEBUG] Not a breakpoint-condition editor — skipping completion provider.");
                                return;
                            }
                        } catch (Throwable t) {
                            logger.warn("[DEBUG] Error during breakpoint editor detection: " + t.getMessage());
                            // continue defensively (if detection failed, we skip to avoid showing suggestions elsewhere)
                            return;
                        }

                        int lineNumber = mainDocument.getLineNumber(contextElement.getTextOffset());

                        String currentLine = mainDocument.getText(new TextRange(
                                mainDocument.getLineStartOffset(lineNumber),
                                mainDocument.getLineEndOffset(lineNumber))).trim();

                        // Extract enclosing function/method block for Java and Python (safe: no compile-time Python dependency)
                        PsiElement enclosingElement = null;

                        // Try Java PsiMethod
                        try {
                            PsiMethod enclosingMethod = PsiTreeUtil.getParentOfType(contextElement, PsiMethod.class);
                            if (enclosingMethod != null) {
                                enclosingElement = enclosingMethod;
                            }
                        } catch (Throwable ignore) {
                            // ignore and try python
                        }

                        // Try Python via reflection if not found
                        if (enclosingElement == null) {
                            try {
                                Class<?> pyFuncClass = Class.forName("com.jetbrains.python.psi.PyFunction");
                                @SuppressWarnings("unchecked")
                                Class<? extends PsiElement> pyClazz = (Class<? extends PsiElement>) pyFuncClass;
                                PsiElement pyEnclosing = PsiTreeUtil.getParentOfType(contextElement, pyClazz);
                                if (pyEnclosing != null) enclosingElement = pyEnclosing;
                            } catch (ClassNotFoundException cnfe) {
                                // Python plugin not present — fine
                            } catch (Throwable t) {
                                logger.warn("[DEBUG] error locating python function class: " + t.getMessage());
                            }
                        }

                        String enclosingFunctionText = "";
                        if (enclosingElement != null) {
                            try {
                                TextRange funcRange = enclosingElement.getTextRange();
                                if (funcRange != null) {
                                    int startOffset = funcRange.getStartOffset();
                                    int endOffset = funcRange.getEndOffset();
                                    int docLen = mainDocument.getTextLength();
                                    startOffset = Math.max(0, Math.min(startOffset, docLen));
                                    endOffset = Math.max(0, Math.min(endOffset, docLen));
                                    enclosingFunctionText = mainDocument.getText(new TextRange(startOffset, endOffset));
                                } else {
                                    enclosingFunctionText = enclosingElement.getText();
                                }
                            } catch (Throwable t) {
                                logger.warn("[DEBUG] Could not extract enclosing function text: " + t.getMessage());
                                enclosingFunctionText = "";
                            }
                        }

                        String fileContext = containingFile.getText();
                        String filePath = (containingFile.getVirtualFile() != null) ? containingFile.getVirtualFile().getPath() : "";

                        boolean debugSession = isInDebugSession(project);

                        String callstack = "", snapshot = "", exception = "";

                        if (debugSession) try {
                            DebugDataCollector collector = DebugDataCollector.getInstance();
                            snapshot = collector.getSnapshot() != null ? collector.getSnapshot().toString() : "";
                            callstack = collector.getCallStack() != null ? collector.getCallStack().toString() : "";
                            ExceptionDetail ex = collector.getExceptionDetail();
                            exception = (ex != null) ? ex.toString() : "";
                        } catch (Throwable t) {
                            logger.info("[DEBUG] Could not collect debug session data: " + t.getMessage());
                        }

                        String languageId = fragment.getLanguage() != null ? fragment.getLanguage().getID() : "UNKNOWN";

                        BreakpointCompletionPayload payload = new BreakpointCompletionPayload(
                                "conditional_breakpoint",
                                currentLine,
                                enclosingFunctionText,
                                fileContext,
                                languageId,
                                filePath,
                                lineNumber + 1,
                                debugSession,
                                callstack,
                                snapshot,
                                exception
                        );

                        List<String> response = ChatApiCallService.fetchServerSuggestions(payload, SERVER_TIMEOUT);

                        if (response == null || response.isEmpty()) return;

                        for (String suggestion : response) {
                            LookupElementBuilder builder = LookupElementBuilder.create(suggestion)
                                    .withTailText(" (assist.i suggestion)")
                                    .withBoldness(true)
                                    .withInsertHandler((insertionContext, item) -> {
                                        try {
                                            int startOffset = insertionContext.getStartOffset();
                                            int tailOffset = insertionContext.getTailOffset();
                                            String originalText = insertionContext.getDocument().getText(new TextRange(startOffset, tailOffset));
                                            logger.info("[DEBUG] In InsertHandler: suggestion=" + suggestion +
                                                    ", startOffset=" + startOffset +
                                                    ", tailOffset=" + tailOffset +
                                                    ", originalTextToBeReplaced='" + originalText + "'" +
                                                    ", doc length=" + insertionContext.getDocument().getTextLength());
                                            insertionContext.getDocument().replaceString(startOffset, tailOffset, suggestion);
                                            Editor ed = insertionContext.getEditor();
                                            if (ed != null) ed.getCaretModel().moveToOffset(startOffset + suggestion.length());
                                        } catch (Throwable t) {
                                            logger.warn("[DEBUG] Error in insert handler: " + t.getMessage());
                                        }
                                    });

                            resultSet.addElement(PrioritizedLookupElement.withPriority(builder, 1000.0));
                        }

                    }
                });

    }

    // Helper: detect breakpoint condition editor to avoid showing suggestions elsewhere
    private static boolean isBreakpointConditionEditor(@NotNull CompletionParameters parameters, @NotNull PsiFile fragment, @NotNull PsiElement contextElement) {
        // 1) Must be a code fragment
        if (!(fragment instanceof PsiCodeFragment)) return false;

        final Editor editor = parameters.getEditor();
        if (editor != null) {
            Component comp = editor.getComponent();
            Window w = SwingUtilities.getWindowAncestor(comp);
            if (w instanceof Dialog || w instanceof JFrame) {
                String title = null;
                if (w instanceof Dialog) title = ((Dialog) w).getTitle();
                else if (w instanceof JFrame) title = ((JFrame) w).getTitle();

                if (title != null) {
                    String lower = title.toLowerCase(Locale.ROOT);
                    if (lower.contains("breakpoint") || lower.contains("condition")) {
                        return true;
                    }
                }
            }

            Component parent = comp;
            while (parent != null) {
                if (parent instanceof JDialog) {
                    String t = ((JDialog) parent).getTitle();
                    if (t != null) {
                        String lower = t.toLowerCase(Locale.ROOT);
                        if (lower.contains("breakpoint") || lower.contains("condition")) return true;
                    }
                }
                parent = parent.getParent();
            }
        }

        // Fallback heuristic: fragment is ephemeral (no virtual file) but context belongs to a real file
        VirtualFile vf = fragment.getVirtualFile();
        if (vf == null) {
            PsiFile containingFile = contextElement.getContainingFile();
            if (containingFile != null && containingFile.getVirtualFile() != null) {
                return true;
            }
        }

        return false;
    }

    private static boolean isInDebugSession(Project project) {
        try {
            DebuggerSession session = DebuggerManagerEx.getInstanceEx(project).getContext().getDebuggerSession();
            return session != null && session.isPaused();
        } catch (Throwable t) {
            return false;
        }
    }

}
