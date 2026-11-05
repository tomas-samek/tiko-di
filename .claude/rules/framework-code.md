---
paths:
  - "**/src/main/java/**"
---

# Framework and generated code

## Logging in Framework Code

- All framework output goes through `System.Logger` (`System.getLogger(...)`) — the
  JDK platform logging facade, so `Tiko.create()` works with zero logging-binding
  dependencies (default routing is JUL; users bridge to slf4j/log4j2 via a
  `LoggerFinder` on their classpath). Never `System.err.println` or
  `e.printStackTrace()` in framework or generated code, and never depend on a
  logging framework directly.
- Default namespace: `io.tiko.events`, or a per-subsystem name like
  `io.tiko.config` / `io.tiko.kafka`.
- Use the lazy-holder pattern to defer `LoggerFinder` resolution off the cold-start
  path; route every log call in the class through the holder (no inline
  `System.getLogger(...)` next to a declared holder):
  ```java
  private static final class LoggerHolder {
      static final System.Logger LOG = System.getLogger("io.tiko.events");
  }
  ```
- Annotation processor failures format their stack via
  `TikoAnnotationProcessor.formatStackTrace(Throwable)` (multi-line `\n  at …`
  with cause chain). Do not embed `Arrays.toString(e.getStackTrace())` in
  messager messages.
