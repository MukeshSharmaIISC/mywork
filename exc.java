package org.samsung.aipp.aippintellij.debugAssist;

public class ExceptionDetail {
    private String message;
    private String type;
    private String stackTrace;
    private String filePath;
    private int lineNumber;

    public ExceptionDetail(String message, String type, String stackTrace, String filePath, int lineNumber) {
        this.message = message;
        this.type = type;
        this.stackTrace = stackTrace;
        this.filePath = filePath;
        this.lineNumber = lineNumber;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getStackTrace() { return stackTrace; }
    public void setStackTrace(String stackTrace) { this.stackTrace = stackTrace; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public int getLineNumber() { return lineNumber; }
    public void setLineNumber(int lineNumber) { this.lineNumber = lineNumber; }
}
