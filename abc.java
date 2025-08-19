Refactor the file DebugDataCollector.java so it works robustly for Java (IntelliJ IDEA), Kotlin, and Python (PyCharm) in JetBrains IDEs.

Detect the file language (Java, Kotlin, Python) by extension and use language-specific PSI (Program Structure Interface) to extract the enclosing function for a given line in the source file.
When collecting variables in the current stack frame, gather all local variables, object fields (Java/Kotlin), and attributes (Python) as shown in the IDE’s Variables pane.
For exception collection, reliably detect exception objects by name (exception, e, throwable, exc_value, exc_type, exc_traceback) and/or type (containing “Exception” or “Throwable”), and extract their message and stack trace or traceback.
Variable names and children should match what is displayed in the IDE’s debug window for each language.
Use defensive coding practices so the plugin does not crash if a language is unsupported or a PSI class is missing.
Add clear comments explaining key logic and language-specific handling.
Ensure recursive collection of variable children (object fields/attributes), up to a safe depth limit.
Avoid hard dependencies on PyCharm/Kotlin plugins, use reflection where necessary for PSI extraction.
The resulting code must be robust, extensible for future languages, and should not throw errors in any JetBrains IDE.
