package org.dell;

public class ExceptionDetail {
    private final String message;
    private final String stackTrace;
    private final int lineNumber;

    public ExceptionDetail(String message, String stackTrace, int lineNumber) {
        this.message = message;
        this.stackTrace = stackTrace;
        this.lineNumber = lineNumber;
    }

    public String getMessage() { return message; }
    public String getStackTrace() { return stackTrace; }
    public int getLineNumber() { return lineNumber; }

    public boolean hasData() {
        return message != null || stackTrace != null;
    }
}
