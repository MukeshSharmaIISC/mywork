import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.lookup.PrioritizedLookupElement;
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

// Java
import com.intellij.debugger.DebuggerManagerEx;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.JavaCodeFragment;

// Kotlin
import org.jetbrains.kotlin.idea.core.KotlinCodeFragment;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtFile;

// Your custom classes
import org.samsung.aipp.aippintellij.chat.ChatApiCallService;
import org.samsung.aipp.aippintellij.chat.BreakpointCompletionPayload;
import org.samsung.aipp.aippintellij.debug.DebugDataCollector;
import org.samsung.aipp.aippintellij.debug.ExceptionDetail;

import java.util.List;

import static org.samsung.aipp.aippintellij.util.Constants.SERVER_TIMEOUT;

public class BreakpointCompletionContributor extends CompletionContributor {
    private static final Logger logger = Logger.getInstance(BreakpointCompletionContributor.class);

    public BreakpointCompletionContributor() {
        // Register for JavaCodeFragment (Java Breakpoint Conditions)
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inFile(PlatformPatterns.psiFile(JavaCodeFragment.class)),
            new CompletionProvider<>() {
                @Override
                protected void addCompletions(@NotNull CompletionParameters parameters, @NotNull ProcessingContext context, @NotNull CompletionResultSet resultSet) {
                    provideBreakpointCompletions(parameters, resultSet, LanguageType.JAVA);
                }
            }
        );

        // Register for KotlinCodeFragment (Kotlin Breakpoint Conditions)
        extend(
            CompletionType.BASIC,
            PlatformPatterns.psiElement().inFile(PlatformPatterns.psiFile(KotlinCodeFragment.class)),
            new CompletionProvider<>() {
                @Override
                protected void addCompletions(@NotNull CompletionParameters parameters, @NotNull ProcessingContext context, @NotNull CompletionResultSet resultSet) {
                    provideBreakpointCompletions(parameters, resultSet, LanguageType.KOTLIN);
                }
            }
        );
    }

    private enum LanguageType { JAVA, KOTLIN }

    private void provideBreakpointCompletions(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet resultSet, LanguageType language) {
        PsiElement element = parameters.getPosition();

        // Get the code fragment file depending on language
        PsiFile fragmentFile = ApplicationManager.getApplication().runReadAction((Computable<PsiFile>) element::getContainingFile);

        // Context element (the element in the source file where the fragment was created)
        PsiElement contextElement;
        PsiFile containingFile;
        Project project;
        Document mainDocument;
        int lineNumber;
        String currentLine;
        String enclosingFunction = "";
        String fileContext;
        String filePath;

        if (language == LanguageType.JAVA && fragmentFile instanceof JavaCodeFragment fragment) {
            contextElement = fragment.getContext();
            if (contextElement == null) return;
            containingFile = contextElement.getContainingFile();
            project = containingFile.getProject();
            mainDocument = PsiDocumentManager.getInstance(project).getDocument(containingFile);
            if (mainDocument == null) return;
            lineNumber = mainDocument.getLineNumber(contextElement.getTextOffset());
            currentLine = mainDocument.getText(new TextRange(mainDocument.getLineStartOffset(lineNumber), mainDocument.getLineEndOffset(lineNumber))).trim();

            PsiMethod enclosingMethod = PsiTreeUtil.getParentOfType(contextElement, PsiMethod.class);
            enclosingFunction = (enclosingMethod != null) ? enclosingMethod.getText() : "";
            fileContext = containingFile.getText();
            filePath = (containingFile.getVirtualFile() != null) ? containingFile.getVirtualFile().getPath() : "";
        } else if (language == LanguageType.KOTLIN && fragmentFile instanceof KotlinCodeFragment fragment) {
            contextElement = fragment.getContext();
            if (contextElement == null) return;
            containingFile = contextElement.getContainingFile();
            project = containingFile.getProject();
            mainDocument = PsiDocumentManager.getInstance(project).getDocument(containingFile);
            if (mainDocument == null) return;
            lineNumber = mainDocument.getLineNumber(contextElement.getTextOffset());
            currentLine = mainDocument.getText(new TextRange(mainDocument.getLineStartOffset(lineNumber), mainDocument.getLineEndOffset(lineNumber))).trim();

            // For Kotlin, get function
            KtNamedFunction enclosingKtFunction = PsiTreeUtil.getParentOfType(contextElement, KtNamedFunction.class);
            enclosingFunction = (enclosingKtFunction != null) ? enclosingKtFunction.getText() : "";
            fileContext = containingFile.getText();
            filePath = (containingFile.getVirtualFile() != null) ? containingFile.getVirtualFile().getPath() : "";
        } else {
            logger.info("[DEBUG] Not a supported CodeFragment, exiting");
            return;
        }

        boolean debugSession = isInDebugSession(containingFile.getProject());
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

        String langStr = (language == LanguageType.JAVA) ? "JAVA" : "KOTLIN";
        BreakpointCompletionPayload payload = new BreakpointCompletionPayload(
            "conditional_breakpoint",
            currentLine,
            enclosingFunction,
            fileContext,
            langStr,
            filePath,
            lineNumber + 1,
            debugSession,
            callstack,
            snapshot,
            exception
        );

        List<String> response = ChatApiCallService.fetchServerSuggestions(payload, SERVER_TIMEOUT);

        // Use a very high priority so suggestions are always at the TOP
        double suggestionPriority = Double.MAX_VALUE;

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
                        ", doc length=" + contexts.getDocument().getTextLength()
                    );
                    contexts.getDocument().replaceString(startOffset, tailOffset, suggestion);
                    contexts.getEditor().getCaretModel().moveToOffset(startOffset + suggestion.length());
                });
            resultSet.addElement(PrioritizedLookupElement.withPriority(builder, suggestionPriority));
        }
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
