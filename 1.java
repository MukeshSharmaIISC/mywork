package org.samsung.aipp.aippintellij.debugAssist;

import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.frame.XStackFrame;
import java.util.List;
import java.util.function.Consumer;

public interface DebugDataCollectorInterface {
    void collectStackItems(XDebugProcess debugProcess, Consumer<ContextItem> callback);
    void collectSnapshot(XStackFrame currentStackFrame, Consumer<ContextItem> callback);
    void collectException(XStackFrame frame, Consumer<ContextItem> callback);

    List<SnapshotItem> getSnapshot();
    List<StackItem> getCallStack();
    ExceptionDetail getExceptionDetail();
    void clearDebugData();
}
