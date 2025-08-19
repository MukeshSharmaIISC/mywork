package org.samsung.aipp.aippintellij.debugAssist;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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
import org.dell.LowerPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import static org.dell.TOOL_CHAT;
import org.dell.GenerateInlayForException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
                System.out.println("Debug session started");
                enableExceptionBreakpoints(project);
                attachDebugBreakListener(debugProcess);
            }
        });
    }

    private void enableExceptionBreakpoints(Project project) {
        try {
            Class<?> javaExType = Class.forName("com.intellij.debugger.ui.breakpoints.JavaExceptionBreakpointType");
            var breakpoints = XDebuggerManager.getInstance(project).getBreakpointManager().getAllBreakpoints();
            for (XBreakpoint<?> bp : breakpoints) {
                if (javaExType.isInstance(bp.getType())) {
                    bp.setEnabled(true);
                }
            }
            System.out.println("Java exception breakpoints enabled");
        } catch (ClassNotFoundException e) {
            System.out.println("JavaExceptionBreakpointType not available (PyCharm). Skipping...");
        } catch (Throwable t) {
            System.out.println("Error enabling exception breakpoints: " + t.getMessage());
        }
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
                        try {
                            Class<?> javaExType = Class.forName("com.intellij.debugger.ui.breakpoints.JavaExceptionBreakpointType");
                            if (javaExType.isInstance(bp.getType()) && bp.isEnabled()) {
                                hasExceptionBreakpoint = true;
                                break;
                            }
                        } catch (ClassNotFoundException e) {
                            // PyCharm → ignore
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

    /**
     * Synchronously collect data from DebugDataCollector with a small timeout.
     * This avoids relying on Thread.sleep for async callbacks.
     */
    private static <T> T collectSync(java.util.function.Consumer<java.util.function.Consumer<T>> collector) {
        final CountDownLatch latch = new CountDownLatch(1);
        final Object[] result = new Object[1];
        collector.accept(data -> {
            result[0] = data;
            latch.countDown();
        });
        try {
            latch.await(300, TimeUnit.MILLISECONDS); // Small timeout for debugger response
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return (T) result[0];
    }

    /**
     * Pretty-prints a list of SnapshotItem as JSON.
     */
    private static String prettyPrintSnapshot(List<SnapshotItem> snapshotItems) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(snapshotItems);
    }

    /**
     * Pretty-prints a list of StackItem as JSON.
     */
    private static String prettyPrintStack(List<StackItem> stackItems) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(stackItems);
    }

    /**
     * Pretty-prints ExceptionDetail as JSON.
     */
    private static String prettyPrintException(ExceptionDetail ex) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        return gson.toJson(ex);
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

        switch (command) {
            case "snapshot": {
                ContextItem contextItem = collectSync(cb -> DebugDataCollector.collectSnapshot(frame, cb));
                List<SnapshotItem> snapshotItems = (contextItem != null && contextItem.hasData())
                        ? (List<SnapshotItem>) contextItem.getData()
                        : DebugDataCollector.getInstance().getSnapshot();
                if (snapshotItems == null || snapshotItems.isEmpty()) {
                    System.out.println("Snapshot is null or empty");
                    return null;
                }
                // Pretty-print snapshot as JSON
                String prettyJson = prettyPrintSnapshot(snapshotItems);
                System.out.println("SNAPSHOT:\n" + prettyJson);
                return prettyJson;
            }

            case "callstack": {
                ContextItem contextItem = collectSync(cb -> DebugDataCollector.collectStackItems(session.getDebugProcess(), cb));
                List<StackItem> stackItems = (contextItem != null && contextItem.hasData())
                        ? (List<StackItem>) contextItem.getData()
                        : DebugDataCollector.getInstance().getCallStack();
                if (stackItems == null || stackItems.isEmpty()) {
                    System.out.println("Call stack is null or empty");
                    return null;
                }
                // Pretty-print stack as JSON
                String prettyJson = prettyPrintStack(stackItems);
                System.out.println("CALLSTACK:\n" + prettyJson);
                return prettyJson;
            }

            case "exception": {
                ContextItem contextItem = collectSync(cb -> DebugDataCollector.collectException(frame, cb));
                ExceptionDetail ex = (contextItem != null && contextItem.hasData())
                        ? (ExceptionDetail) contextItem.getData()
                        : DebugDataCollector.getInstance().getExceptionDetail();
                if (ex == null || (ex.getMessage() == null && ex.getStackTrace() == null)) {
                    return null;
                }
                // UI Inlay logic (unchanged, still async, but not needed for info collection)
                DebugAttacher debugAttacher = sessionMap.get(XDebuggerManager.getInstance(project).getCurrentSession());
                ApplicationManager.getApplication().invokeLater(() -> {
                    Editor activeEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                    if (activeEditor != null && ex.getLineNumber() >= 0) {
                        int offset = activeEditor.getDocument().getLineStartOffset(ex.getLineNumber());
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
                    }
                });
                // Pretty-print exception as JSON
                String prettyJson = prettyPrintException(ex);
                System.out.println("EXCEPTION:\n" + prettyJson);
                return prettyJson;
            }

            default:
                System.out.println("Unknown debug command: " + command);
                return null;
        }
    }
}



package org.samsung.aipp.aippintellij.debugAssist;

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
import org.dell.LowerPanel;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import static org.dell.TOOL_CHAT;
import org.dell.GenerateInlayForException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
                System.out.println("Debug session started");
                enableExceptionBreakpoints(project);
                attachDebugBreakListener(debugProcess);
            }
        });
    }

    private void enableExceptionBreakpoints(Project project) {
        try {
            Class<?> javaExType = Class.forName("com.intellij.debugger.ui.breakpoints.JavaExceptionBreakpointType");
            var breakpoints = XDebuggerManager.getInstance(project).getBreakpointManager().getAllBreakpoints();
            for (XBreakpoint<?> bp : breakpoints) {
                if (javaExType.isInstance(bp.getType())) {
                    bp.setEnabled(true);
                }
            }
            System.out.println("Java exception breakpoints enabled");
        } catch (ClassNotFoundException e) {
            System.out.println("JavaExceptionBreakpointType not available (PyCharm). Skipping...");
        } catch (Throwable t) {
            System.out.println("Error enabling exception breakpoints: " + t.getMessage());
        }
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
                        try {
                            Class<?> javaExType = Class.forName("com.intellij.debugger.ui.breakpoints.JavaExceptionBreakpointType");
                            if (javaExType.isInstance(bp.getType()) && bp.isEnabled()) {
                                hasExceptionBreakpoint = true;
                                break;
                            }
                        } catch (ClassNotFoundException e) {
                            // PyCharm → ignore
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

    /**
     * Synchronously collect data from DebugDataCollector with a small timeout.
     * This avoids relying on Thread.sleep for async callbacks.
     */
    private static <T> T collectSync(java.util.function.Consumer<java.util.function.Consumer<T>> collector) {
        final CountDownLatch latch = new CountDownLatch(1);
        final Object[] result = new Object[1];
        collector.accept(data -> {
            result[0] = data;
            latch.countDown();
        });
        try {
            latch.await(300, TimeUnit.MILLISECONDS); // Small timeout for debugger response
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return (T) result[0];
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

        switch (command) {
            case "snapshot": {
                ContextItem contextItem = collectSync(cb -> DebugDataCollector.collectSnapshot(frame, cb));
                List<SnapshotItem> snapshotItems = (contextItem != null && contextItem.hasData())
                        ? (List<SnapshotItem>) contextItem.getData()
                        : DebugDataCollector.getInstance().getSnapshot();
                if (snapshotItems == null || snapshotItems.isEmpty()) {
                    System.out.println("Snapshot is null or empty");
                    return null;
                }
                StringBuilder snapshotBuilder = new StringBuilder();
                for (SnapshotItem item : snapshotItems) {
                    appendSnapshotItem(snapshotBuilder, item, 0);
                }
                return snapshotBuilder.toString().trim();
            }

            case "callstack": {
                ContextItem contextItem = collectSync(cb -> DebugDataCollector.collectStackItems(session.getDebugProcess(), cb));
                List<StackItem> stackItems = (contextItem != null && contextItem.hasData())
                        ? (List<StackItem>) contextItem.getData()
                        : DebugDataCollector.getInstance().getCallStack();
                if (stackItems == null || stackItems.isEmpty()) {
                    System.out.println("Call stack is null or empty");
                    return null;
                }
                StringBuilder stackBuilder = new StringBuilder();
                for (StackItem item : stackItems) {
                    stackBuilder.append(item.getFunction())
                            .append(" at ").append(item.getFilePath()).append(":").append(item.getLine()).append("\n");
                }
                return stackBuilder.toString().trim();
            }

            case "exception": {
                ContextItem contextItem = collectSync(cb -> DebugDataCollector.collectException(frame, cb));
                ExceptionDetail ex = (contextItem != null && contextItem.hasData())
                        ? (ExceptionDetail) contextItem.getData()
                        : DebugDataCollector.getInstance().getExceptionDetail();
                if (ex == null || (ex.getMessage() == null && ex.getStackTrace() == null)) {
                    return null;
                }
                // UI Inlay logic (unchanged, still async, but not needed for info collection)
                DebugAttacher debugAttacher = sessionMap.get(XDebuggerManager.getInstance(project).getCurrentSession());
                ApplicationManager.getApplication().invokeLater(() -> {
                    Editor activeEditor = FileEditorManager.getInstance(project).getSelectedTextEditor();
                    if (activeEditor != null && ex.getLineNumber() >= 0) {
                        int offset = activeEditor.getDocument().getLineStartOffset(ex.getLineNumber());
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
                    }
                });
                return (ex.getMessage() + "\n" + ex.getStackTrace()).trim();
            }

            default:
                System.out.println("Unknown debug command: " + command);
                return null;
        }
    }
}
