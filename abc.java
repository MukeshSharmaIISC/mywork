Refactor DebugDataCollector.java for Python (PyCharm) so that variable and exception extraction logic exactly matches how details are shown in the PyCharm debug window.

Snapshot Extraction Requirements:

Collect all variables displayed in the Variables pane, including primitives (e.g. number1 = (int) 5) and objects.
For lists, capture each indexed entry (e.g. 0 = (int) 10, 1 = (int) 20, ...), along with type and value.
For dictionaries, capture each key-value pair (e.g. name = (str) 'Debug Example').
For objects, extract all attributes shown, including special fields like __len__.
For each variable, record its name, type (as shown in parentheses), and value (as shown).
Maintain the nested structure for lists, dicts, and objects, recursively capturing children.
Ignore or specially mark "Protected Attributes" and "Special Variables" unless explicitly needed.
Exception Extraction Requirements:

Detect the special variable (e.g., __exception__) that is present when an exception occurs.
Treat this as a tuple containing:
Exception type (e.g., <class 'ZeroDivisionError'>)
Exception value (e.g., ZeroDivisionError('division by zero')), and its args attribute for message extraction.
Traceback object, with attributes like tb_frame, tb_lineno, tb_lasti, and recursively tb_next for chained tracebacks.
For each tuple element, extract all shown children and attributes as they appear in the debug window.
Present the exception type, message, and traceback details in the output.
General Requirements:

The code must robustly handle missing attributes, complex nested objects, and future Python types.
Include comments explaining how the extraction logic corresponds to the PyCharm debug window display.
Provide the complete, updated code for DebugDataCollector.java.
Use this prompt to ensure the refactored code matches the actual debug window output, making your plugin reliable and user-friendly!







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
