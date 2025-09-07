private static String extractTypeString(XValue value) { 

    final String[] outType = {"unknown"}; 

    try { 

        value.computePresentation(new XValueNode() { 

            @Override 

            public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) { 

                if (presentation.getType() != null) outType[0] = presentation.getType(); 

            } 

            // rename the parameter so it doesn't shadow outType 

            @Override 

            public void setPresentation(@Nullable Icon icon, @NotNull String typeStr, @NotNull String value, boolean hasChildren) { 

                if (typeStr != null && typeStr.toLowerCase().contains("exception")) outType[0] = typeStr; 

            } 

            @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {} 

        }, XValuePlace.TREE); 

    } catch (Throwable ignored) {} 

    return outType[0]; 

} 

  

private static String getExceptionType(XValue value) { 

    final String[] outType = {"unknown"}; 

    try { 

        value.computePresentation(new XValueNode() { 

            @Override 

            public void setPresentation(@Nullable Icon icon, @NotNull XValuePresentation presentation, boolean hasChildren) { 

                String typeStr = presentation.getType(); 

                if (typeStr != null && typeStr.toLowerCase().contains("exception")) outType[0] = typeStr; 

            } 

            // rename param to avoid shadowing 

            @Override 

            public void setPresentation(@Nullable Icon icon, @NotNull String typeStr, @NotNull String value, boolean hasChildren) { 

                if (typeStr != null && typeStr.toLowerCase().contains("exception")) outType[0] = typeStr; 

            } 

            @Override public void setFullValueEvaluator(@NotNull XFullValueEvaluator fullValueEvaluator) {} 

        }, XValuePlace.TREE); 

    } catch (Throwable t) { logger.debug("getExceptionType failed: " + t.getMessage()); } 

    return outType[0]; 

} 

 
