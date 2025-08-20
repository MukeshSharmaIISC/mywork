package org.samsung.aipp.aippintellij.debugAssist;

import com.intellij.openapi.project.Project;
import com.intellij.xdebugger.frame.XStackFrame;
import com.intellij.xdebugger.XDebugProcess;

/**
 * Factory class to provide the correct DebugDataCollector implementation
 * based on the current IDE (PyCharm, etc.) or file extension (.java, .kt, .py).
 */
public class DebugDataCollectorFactory {

    /**
     * Returns the appropriate DebugDataCollectorInterface instance for the current context.
     */
    public static DebugDataCollectorInterface getCollector(Project project, XStackFrame frame, XDebugProcess process) {
        // IDE detection: PyCharm
        if (isPyCharm()) {
            return DebugDataCollectorPython.getInstance();
        }

        // File extension detection
        if (frame != null && frame.getSourcePosition() != null) {
            String ext = frame.getSourcePosition().getFile().getExtension();
            if (ext != null) {
                switch (ext.toLowerCase()) {
                    case "py":
                        return DebugDataCollectorPython.getInstance();
                    case "kt":
                        return DebugDataCollectorKotlin.getInstance();
                    case "java":
                        return DebugDataCollectorJava.getInstance();
                }
            }
        }

        // Default: Java collector
        return DebugDataCollectorJava.getInstance();
    }

    private static boolean isPyCharm() {
        try {
            Class.forName("com.jetbrains.python.debugger.PyDebugValue");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
