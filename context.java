package org.samsung.aipp.aippintellij.debugAssist;

public class ContextItem {
    public enum Type { SNAPSHOT, STACK, EXCEPTION }

    private Object data;
    private boolean hasData;
    private Type type;

    public ContextItem(Object data, boolean hasData, Type type) {
        this.data = data;
        this.hasData = hasData;
        this.type = type;
    }

    public Object getData() { return data; }
    public void setData(Object data) { this.data = data; }

    public boolean hasData() { return hasData; }
    public void setHasData(boolean hasData) { this.hasData = hasData; }

    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
}
