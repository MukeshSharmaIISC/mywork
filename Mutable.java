package org.samsung.aipp.aippintellij.debugAssist;

import java.util.ArrayList;
import java.util.List;

public class MutableSnapshotItem {
    public String name;
    public String type;
    public String value;
    public String kind;
    public List<MutableSnapshotItem> children = new ArrayList<>();

    public MutableSnapshotItem(String name, String type, String value, String kind) {
        this.name = name;
        this.type = type;
        this.value = value;
        this.kind = kind;
    }

    // Converts to an immutable SnapshotItem
    public SnapshotItem toSnapshotItem() {
        List<SnapshotItem> childItems = new ArrayList<>();
        for (MutableSnapshotItem c : children) childItems.add(c.toSnapshotItem());
        return new SnapshotItem(name, type, value, childItems);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public List<MutableSnapshotItem> getChildren() { return children; }
    public void setChildren(List<MutableSnapshotItem> children) { this.children = children; }
}
