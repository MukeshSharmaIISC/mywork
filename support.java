package your.package; // <-- Change this to your real package

import com.intellij.codeInsight.completion.CompletionLocation;
import com.intellij.codeInsight.completion.CompletionWeigher;
import com.intellij.codeInsight.lookup.LookupElement;
import org.jetbrains.annotations.NotNull;

public class CodeISuggestionWeigher extends CompletionWeigher {
    @Override
    public Comparable weigh(@NotNull LookupElement element, @NotNull CompletionLocation location) {
        Boolean isOurSuggestion = element.getUserData(BreakpointCompletionProvider.CODE_I_SUGGESTION_KEY);
        // Lower value = higher priority
        return isOurSuggestion != null && isOurSuggestion ? 0 : 1;
    }
}
