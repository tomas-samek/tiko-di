---
paths:
  - "tiko-config/**"
---

# YAML configuration loading

## YAML Loading

`Yaml` instances must be constructed with an explicit `SafeConstructor`, even
though SnakeYAML 2.x's default constructor is already safe today. Spelling out
the safe-load intent makes it a code-level invariant rather than a
version-pinning one.
