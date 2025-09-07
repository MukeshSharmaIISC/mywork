private static void collectValueAndChildren(XValue value, MutableSnapshotItem parent, int currentDepth, Runnable onComplete) {
    if (currentDepth >= Constants.MAX_DEPTH_OF_NESTED_VARIABLES) { onComplete.run(); return; }
    try {
        // capture outer XValue to avoid inner-method-parameter shadowing
        final XValue xValueLocal = value;

        value.computePresentation(new XValueNode() {
            @Override
            public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) {
                try {
                    if (presentation.getType() != null) parent.type = presentation.getType();
                    String rendered = renderPresentationText(presentation);
                    if ((parent.value == null || parent.value.isEmpty() || "unavailable".equals(parent.value))
                            && rendered != null && !rendered.isEmpty()) {
                        parent.value = rendered;
                    }
                } catch (Exception e) {
                    parent.value = "Value not available";
                } finally {
                    if (hasChildren) {
                        xValueLocal.computeChildren(new XCompositeNode() {
                            @Override
                            public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                                AtomicInteger pending = new AtomicInteger(children.size());
                                if (children.size() == 0) { onComplete.run(); return; }
                                for (int i = 0; i < children.size(); i++) {
                                    String childName = children.getName(i);
                                    XValue childValue = children.getValue(i);
                                    MutableSnapshotItem childItem = new MutableSnapshotItem(childName, "unknown", "unavailable", "Field");
                                    parent.children.add(childItem);
                                    collectValueAndChildren(childValue, childItem, currentDepth + 1, () -> {
                                        if (pending.decrementAndGet() == 0) onComplete.run();
                                    });
                                }
                            }
                            @Override public void tooManyChildren(int remaining) { onComplete.run(); }
                            @Override public void setAlreadySorted(boolean alreadySorted) {}
                            @Override public void setErrorMessage(@NotNull String errorMessage) { onComplete.run(); }
                            @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { onComplete.run(); }
                            @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
                        });
                    } else {
                        onComplete.run();
                    }
                }
            }

            // IMPORTANT: rename string params so they don't shadow outer variables
            @Override
            public void setPresentation(@Nullable Icon icon, @NotNull String typeStr, @NotNull String valueStr, boolean hasChildren) {
                try {
                    if (typeStr != null && !typeStr.isEmpty()) parent.type = typeStr;
                    if (valueStr != null && !valueStr.isEmpty()) parent.value = valueStr;
                } catch (Throwable t) {
                    parent.value = "Value not available";
                }

                if (hasChildren) {
                    xValueLocal.computeChildren(new XCompositeNode() {
                        @Override
                        public void addChildren(@NotNull XValueChildrenList children, boolean last) {
                            AtomicInteger pending = new AtomicInteger(children.size());
                            if (children.size() == 0) { onComplete.run(); return; }
                            for (int i = 0; i < children.size(); i++) {
                                String childName = children.getName(i);
                                XValue childValue = children.getValue(i);
                                MutableSnapshotItem childItem = new MutableSnapshotItem(childName, "unknown", "unavailable", "Field");
                                parent.children.add(childItem);
                                collectValueAndChildren(childValue, childItem, currentDepth + 1, () -> {
                                    if (pending.decrementAndGet() == 0) onComplete.run();
                                });
                            }
                        }
                        @Override public void tooManyChildren(int remaining) { onComplete.run(); }
                        @Override public void setAlreadySorted(boolean alreadySorted) {}
                        @Override public void setErrorMessage(@NotNull String errorMessage) { onComplete.run(); }
                        @Override public void setErrorMessage(@NotNull String s, @Nullable XDebuggerTreeNodeHyperlink link) { onComplete.run(); }
                        @Override public void setMessage(@NotNull String s, @Nullable Icon icon, @NotNull com.intellij.ui.SimpleTextAttributes attrs, @Nullable XDebuggerTreeNodeHyperlink link) {}
                    });
                } else {
                    onComplete.run();
                }
            }

            @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {}
        }, XValuePlace.TREE);
    } catch (Throwable t) {
        logger.warn("collectValueAndChildren failed: " + t.getMessage());
        parent.value = "Value not available";
        onComplete.run();
    }
}
