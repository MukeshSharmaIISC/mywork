package org.samsung.aipp.aippintellij.debugAssist;

import org.dell.Constants;
import com.intellij.xdebugger.frame.XStackFrame;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public class ExceptionState {
    private final AtomicReference<State> state = new AtomicReference<>(State.INIT);
    private String message;
    private String detailMessage;
    private String stackTrace;
    private final Object descriptorOrValue;
    private final String type;
    private final XStackFrame frame;

    public enum State { INIT, MESSAGE_READY, STACK_READY, COMPLETE }

    public ExceptionState(Object descriptorOrValue, String type, XStackFrame frame) {
        this.descriptorOrValue = descriptorOrValue;
        this.type = type;
        this.frame = frame;
    }

    public boolean isComplete() { return state.get() == State.COMPLETE; }
    public void setMessage(String msg) { this.message = msg; updateState(); }
    public void setDetailMessage(String msg) { this.detailMessage = msg; updateState(); }
    public void setStackTrace(String trace) { this.stackTrace = clipStackTrace(trace); updateState(); }

    private void updateState() {
        if (message != null && stackTrace != null) state.set(State.COMPLETE);
        else if (message != null) state.set(State.MESSAGE_READY);
        else if (stackTrace != null) state.set(State.STACK_READY);
    }

    private String clipStackTrace(String stackTrace) {
        return Arrays.stream(stackTrace.split("\n"))
                .limit(Constants.MAX_STACKTRACE_LINES)
                .collect(java.util.stream.Collectors.joining("\n"));
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
