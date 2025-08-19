@SuppressWarnings("unchecked")
private void handleDebugValue(Object valueObj, Map<String, String> variables) {
    try {
        Class<?> pyDebugClass = null;
        try {
            pyDebugClass = Class.forName("com.jetbrains.python.debugger.PyDebugValue");
        } catch (ClassNotFoundException ignored) {
            // Not PyCharm, continue
        }

        if (pyDebugClass != null && pyDebugClass.isInstance(valueObj)) {
            Method getName = pyDebugClass.getMethod("getName");
            Method getValue = pyDebugClass.getMethod("getValue");
            String name = String.valueOf(getName.invoke(valueObj));
            String value = String.valueOf(getValue.invoke(valueObj));

            // Special handling for PyCharm exceptions
            if ("__exception__".equals(name)) {
                variables.put("EXCEPTION", value);
                System.out.println("Detected PyCharm Exception: " + value);
            } else {
                variables.put(name, value);
                System.out.println("Detected PyCharm variable: " + name + " = " + value);
            }
            return;
        }

        // IntelliJ fallback
        if (valueObj instanceof ValueDescriptorImpl) {
            ValueDescriptorImpl v = (ValueDescriptorImpl) valueObj;
            String name = v.getName();
            String value = v.calcValueName();
            variables.put(name, value);
            System.out.println("Detected IntelliJ variable: " + name + " = " + value);
        }
    } catch (Exception e) {
        e.printStackTrace();
    }
}
