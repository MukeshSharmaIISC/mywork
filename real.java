import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.impl.DebuggerSession;
import org.samsung.aipp.aippintellij.chat.ChatApiCallService;
import java.util.List;
import static org.samsung.aipp.aippintellij.util.Constants.SERVER_TIMEOUT;

public class BreakpointCompletionProvider extends CompletionContributor {
    private static final Logger logger = Logger.getInstance(BreakpointCompletionProvider.class);

    public BreakpointCompletionProvider() {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement().inFile(PlatformPatterns.psiFile(JavaCodeFragment.class)), new CompletionProvider<>() {
            @Override
            protected void addCompletions(@NotNull CompletionParameters parameters, @NotNull ProcessingContext context, @NotNull CompletionResultSet resultSet) {
                PsiElement element = parameters.getPosition();
                PsiFile fragmentFile = ApplicationManager.getApplication().runReadAction((Computable<PsiFile>) element::getContainingFile);
                if (!(fragmentFile instanceof JavaCodeFragment fragment)) {
                    logger.info("[DEBUG] Not a JavaCodeFragment, exiting");
                    return;
                }
                PsiElement contextElement = fragment.getContext();
                if (contextElement == null) return;
                PsiFile containingFile = contextElement.getContainingFile();
                Project project = containingFile.getProject();
                Document mainDocument = PsiDocumentManager.getInstance(project).getDocument(containingFile);
                if (mainDocument == null) return;
                int lineNumber = mainDocument.getLineNumber(contextElement.getTextOffset());
                String currentLine = mainDocument.getText(new TextRange(mainDocument.getLineStartOffset(lineNumber), mainDocument.getLineEndOffset(lineNumber))).trim();
                PsiMethod enclosingMethod = PsiTreeUtil.getParentOfType(contextElement, PsiMethod.class);
                String enclosingFunction = (enclosingMethod != null) ? enclosingMethod.getText() : "", fileContext = containingFile.getText(), filePath = (containingFile.getVirtualFile() != null) ? containingFile.getVirtualFile().getPath() : "";
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
                BreakpointCompletionPayload payload = new BreakpointCompletionPayload("conditional_breakpoint", currentLine, enclosingFunction, fileContext, "JAVA", filePath, lineNumber + 1, debugSession, callstack, snapshot, exception);
                List<String> response = ChatApiCallService.fetchServerSuggestions(payload, SERVER_TIMEOUT);

                for (String suggestion : response) {
                    LookupElementBuilder builder = LookupElementBuilder.create(suggestion)
                            .withTailText(" (code.i suggestion)")
                            .withBoldness(true)
                            .withInsertHandler((contexts, item) -> {
                                int startOffset = contexts.getStartOffset();
                                int tailOffset = contexts.getTailOffset();
                                String originalText = contexts.getDocument().getText(new TextRange(startOffset, tailOffset));
                                logger.info("[DEBUG] In InsertHandler: suggestion=" + suggestion +
                                        ", startOffset=" + startOffset +
                                        ", tailOffset=" + tailOffset +
                                        ", originalTextToBeReplaced='" + originalText + "'" +
                                        ", doc length=" + contexts.getDocument().getTextLength());
                                contexts.getDocument().replaceString(startOffset, tailOffset, suggestion);
                                contexts.getEditor().getCaretModel().moveToOffset(startOffset + suggestion.length());
                            });
                    resultSet.addElement(PrioritizedLookupElement.withPriority(builder, 1000.0));
                }
            }
        });
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
