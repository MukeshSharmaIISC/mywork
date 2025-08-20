package org.samsung.aipp.aippintellij.debugAssist;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.frame.*;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Java debug collector
 */
public class DebugDataCollectorJava implements DebugDataCollectorInterface {
    private static final Logger logger = Logger.getInstance(DebugDataCollectorJava.class);
    private static final DebugDataCollectorJava instance = new DebugDataCollectorJava();

    private final List<SnapshotItem> latestSnapshot = new ArrayList<>();
    private final List<StackItem> latestStack = new ArrayList<>();
    private ExceptionDetail latestException = null;

    private DebugDataCollectorJava() {}

    public static DebugDataCollectorJava getInstance() { return instance; }

    @Override
    public void collectStackItems(XDebugProcess debugProcess, Consumer<ContextItem> callback) {
        List<StackItem> stackItems = new ArrayList<>();
        try {
            XExecutionStack stack = debugProcess.getSession().getSuspendContext().getActiveExecutionStack();
            if (stack == null) {
                callback.accept(new ContextItem(stackItems, false, ContextItem.Type.STACK));
                return;
            }
            stack.computeStackFrames(0, new XExecutionStack.XStackFrameContainer() {
                @Override
                public void addStackFrames(@NotNull List<? extends XStackFrame> frames, boolean last) {
                    for (XStackFrame frame : frames) {
                        XSourcePosition pos = frame.getSourcePosition();
                        if (pos != null) {
                            String file = pos.getFile().getPath();
                            int line = pos.getLine() + 1;
                            String language = pos.getFile().getExtension();
                            String functionName = ""; // You can extract function text via PSI if needed
                            stackItems.add(new StackItem(file, line, functionName, language));
                        }
                    }
                    instance.latestStack.clear();
                    instance.latestStack.addAll(stackItems);
                    callback.accept(new ContextItem(stackItems, true, ContextItem.Type.STACK));
                }
                @Override public void errorOccurred(@NotNull String errorMessage) {
                    callback.accept(new ContextItem(stackItems, false, ContextItem.Type.STACK));
                }
            });
        } catch (Exception e) {
            callback.accept(new ContextItem(stackItems, false, ContextItem.Type.STACK));
        }
    }

    @Override
    public void collectSnapshot(XStackFrame currentStackFrame, Consumer<ContextItem> callback) {
        List<MutableSnapshotItem> snapshotItems = new ArrayList<>();
        AtomicInteger debuggerCalls = new AtomicInteger(0);

        currentStackFrame.computeChildren(new XCompositeNode() {
            @Override
            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                AtomicInteger pending = new AtomicInteger(children.size());
                if (children.size() == 0) complete();
                for (int i = 0; i < children.size(); i++) {
                    String varName = children.getName(i);
                    XValue childValue = children.getValue(i);
                    MutableSnapshotItem mutableItem = new MutableSnapshotItem(varName, "unknown", "unavailable", "Local");
                    snapshotItems.add(mutableItem);

                    debuggerCalls.incrementAndGet();
                    childValue.computePresentation(new XValueNode() {
                        @Override public void setPresentation(javax.swing.Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                            try {
                                if (presentation.getType() != null) mutableItem.type = presentation.getType();
                                mutableItem.value = presentation.toString();
                            } catch (Exception e) {
                                mutableItem.value = "Value not available";
                            }
                            if (pending.decrementAndGet() == 0) complete();
                        }
                        @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
                        @Override public void setPresentation(javax.swing.Icon icon, @NotNull String type, @NotNull String value, boolean hasChildren) {}
                    }, XValuePlace.TREE);
                }
            }
            private void complete() {
                List<SnapshotItem> result = new ArrayList<>();
                for (MutableSnapshotItem item : snapshotItems) result.add(item.toSnapshotItem());
                instance.latestSnapshot.clear();
                instance.latestSnapshot.addAll(result);
                callback.accept(new ContextItem(result, true, ContextItem.Type.SNAPSHOT));
            }
            @Override public void tooManyChildren(int remaining) {}
            @Override public void setAlreadySorted(boolean alreadySorted) {}
            @Override public void setErrorMessage(@NotNull String errorMessage) {}
            @Override public void setErrorMessage(@NotNull String s, XDebuggerTreeNodeHyperlink link) {}
            @Override public void setMessage(@NotNull String s, javax.swing.Icon icon, com.intellij.ui.SimpleTextAttributes attrs, XDebuggerTreeNodeHyperlink link) {}
        });
    }

    @Override
    public void collectException(XStackFrame frame, Consumer<ContextItem> callback) {
        frame.computeChildren(new XCompositeNode() {
            @Override
            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                for (int i = 0; i < children.size(); i++) {
                    String name = children.getName(i);
                    XValue value = children.getValue(i);

                    if (name != null && name.toLowerCase().contains("exception")) {
                        ExceptionDetail detail = new ExceptionDetail("Java Exception", "Exception", "No stacktrace extracted", "unknown", -1);
                        instance.latestException = detail;
                        callback.accept(new ContextItem(detail, true, ContextItem.Type.EXCEPTION));
                        return;
                    }
                }
                collectSnapshot(frame, callback);
            }
            @Override public void tooManyChildren(int remaining) {}
            @Override public void setAlreadySorted(boolean alreadySorted) {}
            @Override public void setErrorMessage(@NotNull String errorMessage) {}
            @Override public void setErrorMessage(@NotNull String s, XDebuggerTreeNodeHyperlink link) {}
            @Override public void setMessage(@NotNull String s, javax.swing.Icon icon, com.intellij.ui.SimpleTextAttributes attrs, XDebuggerTreeNodeHyperlink link) {}
        });
    }

    @Override
    public List<SnapshotItem> getSnapshot() {
        return new ArrayList<>(latestSnapshot);
    }

    @Override
    public List<StackItem> getCallStack() {
        return new ArrayList<>(latestStack);
    }

    @Override
    public ExceptionDetail getExceptionDetail() {
        return latestException;
    }

    @Override
    public void clearDebugData() {
        latestSnapshot.clear();
        latestStack.clear();
        latestException = null;
    }
}
