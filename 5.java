package org.samsung.aipp.aippintellij.debugAssist;

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
import org.samsung.aipp.aippintellij.chat.AIPPChatContentManager;
import org.samsung.aipp.aippintellij.chat.LowerPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import static org.samsung.aipp.aippintellij.util.Constants.TOOL_CHAT;
import org.samsung.aipp.aippintellij.debugAssist.GenerateInlayForException;

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
        getCollector().clearDebugData();

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
                System.out.println("Debug session started");
                enableExceptionBreakpoints(project);
                attachDebugBreakListener(debugProcess);
            }
        });
    }

    private void enableExceptionBreakpoints(Project project) {
        var breakpoints = XDebuggerManager.getInstance(project).getBreakpointManager().getAllBreakpoints();
        for (XBreakpoint<?> bp : breakpoints)
            if (bp.getType() instanceof JavaExceptionBreakpointType) bp.setEnabled(true);
    }

    private void attachDebugBreakListener(@NotNull XDebugProcess debugProcess) {
        debugProcess.getSession().addSessionListener(new XDebugSessionListener() {
            @Override
            public void sessionPaused() {
                ApplicationManager.getApplication().runReadAction(() -> {
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
                        int currentLine = activeEditor.getCaretModel().getLogicalPosition().line + 1;
                        if (currentLine != lastExceptionLine) {
                            exceptionVisited = false;
                            lastExceptionLine = currentLine;
                        }
                        getDebugInfoFor("exception");
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

    // Helper method to get the correct collector for the current session
    private static DebugDataCollectorInterface getCollector() {
        Object[] debugInfo = isPaused(ProjectManager.getInstance().getDefaultProject());
        XDebugSession session = (XDebugSession)debugInfo[0];
        XStackFrame frame = (XStackFrame)debugInfo[1];
        if (session != null && frame != null)
            return DebugDataCollectorFactory.getCollector(ProjectManager.getInstance().getDefaultProject(), frame, session.getDebugProcess());
        else
            return DebugDataCollectorJava.getInstance(); // Default fallback
    }

    private static void appendSnapshotItem(StringBuilder builder, SnapshotItem item, int depth) {
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

    private static String prettyPrintSnapshot(List<SnapshotItem> snapshotItems) {
        StringBuilder builder = new StringBuilder();
        for (SnapshotItem item : snapshotItems) {
            appendSnapshotItem(builder, item, 0);
            builder.append("\n");
        }
        return builder.toString().trim();
    }

    private static String prettyPrintCallstack(List<StackItem> stackItems) {
        StringBuilder builder = new StringBuilder();
        builder.append("Call Stack:\n");
        for (StackItem item : stackItems) {
            builder.append("  at ").append(item.getFunction())
                    .append(" (").append(item.getFilePath()).append(":").append(item.getLine()).append(")\n");
        }
        return builder.toString().trim();
    }

    private static String prettyPrintException(ExceptionDetail ex) {
        StringBuilder builder = new StringBuilder();
        builder.append("Exception:\n");
        builder.append("  Type: ").append(ex.getType()).append("\n");
        builder.append("  Message: ").append(ex.getMessage()).append("\n");
        builder.append("  File: ").append(ex.getFilePath()).append("\n");
        builder.append("  Line: ").append(ex.getLineNumber()).append("\n");
        builder.append("  StackTrace:\n");
        builder.append(ex.getStackTrace()).append("\n");
        return builder.toString().trim();
    }

    public static String getDebugInfoFor(String command) {
        Object[] debugInfo = isPaused(ProjectManager.getInstance().getDefaultProject());
        XDebugSession session = (XDebugSession)debugInfo[0];
        XStackFrame frame = (XStackFrame)debugInfo[1];
        boolean paused = (boolean)debugInfo[2];
        DebugDataCollectorInterface collector = getCollector();

        if (session == null || !paused) {
            System.out.println("No active debug session or not paused");
            return null;
        }
        if (frame == null) {
            System.out.println("No current stack frame");
            return null;
        }

        switch (command) {
            case "snapshot":
                try {
                    collector.collectSnapshot(frame, snapshot -> {});
                    Thread.sleep(100);
                } catch (Exception e) {
                    System.out.println("Error collecting snapshot: " + e.getMessage());
                }
                List<SnapshotItem> snapshotItems = collector.getSnapshot();
                if (snapshotItems == null || snapshotItems.isEmpty()) {
                    System.out.println("Snapshot is null or empty");
                    return null;
                }
                return prettyPrintSnapshot(snapshotItems);

            case "callstack":
                try {
                    collector.collectStackItems(session.getDebugProcess(), stack -> {});
                    Thread.sleep(100);
                } catch (Exception e) {
                    System.out.println("Error collecting callstack: " + e.getMessage());
                }
                List<StackItem> stackItems = collector.getCallStack();
                if (stackItems == null || stackItems.isEmpty()) {
                    System.out.println("Call stack is null or empty");
                    return null;
                }
                return prettyPrintCallstack(stackItems);

            case "exception":
                try {
                    DebugAttacher debugAttacher = sessionMap.get(XDebuggerManager.getInstance(project).getCurrentSession());
                    collector.collectException(frame, exception -> {
                        if (exception.hasData()) {
                            Editor activeEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                            int offset = activeEditor.getDocument().getLineStartOffset(((ExceptionDetail) exception.getData()).getLineNumber());
                            ApplicationManager.getApplication().invokeLater(() -> {
                                WriteCommandAction.runWriteCommandAction(project, () -> {
                                    ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_CHAT);
                                    if (toolWindow != null) toolWindow.show();
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
                    });
                    Thread.sleep(100);
                } catch (Exception e) {
                    System.out.println("Error collecting exception: " + e.getMessage());
                }

                ExceptionDetail ex = collector.getExceptionDetail();
                if (ex == null || (ex.getMessage() == null && ex.getStackTrace() == null)) {
                    return null;
                }
                return prettyPrintException(ex);

            default:
                System.out.println("Unknown debug command: " + command);
                return null;
        }
    }
}
