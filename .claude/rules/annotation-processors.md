---
paths:
  - "tiko-processor/**"
  - "tiko-kafka-processor/**"
  - "tiko-processor-core/**"
---

# Annotation processors and generators

## Code Generation Strategy

All code is generated in the `io.tiko.generated` package:
- `TikoContainerImpl_<hash>` - Main container implementation
- `<ComponentName>Factory_<hash>` - Factory for each component, `<ComponentName>Proxy_<hash>` its proxy
- `EventRegistry_<hash>` - Event handler wiring

`<hash>` is the module's hash. Every module generates into this one package, so every generated class
name carries it (#726); build such names with `GeneratedNames`, never by hand.

## Generated Code Markings

Every top-level type emitted by an annotation processor carries
`@javax.annotation.processing.Generated(...)`. Use the shared helper
`GeneratorAnnotations.generatedBy(GeneratorClass.class)` (in
`tiko-processor-core`, package `io.tiko.processor.core`). This lets IDEs grey out generated sources and coverage
tools exclude them.

## Error Message Format

Compile-time errors should:
1. Show the problematic code location
2. Explain what's wrong
3. Suggest at least one fix

Example:
```
ERROR: UserService.java:15
EVENT-scoped bean 'RequestContext' must implement an interface for proxy generation

  @Inject
  public UserService(RequestContext context) {
                     ^^^^^^^^^^^^^^

When injecting EVENT-scoped beans into SINGLETON, proxying is required.

Suggested fixes:
1. Extract interface: interface IRequestContext { }
2. Make RequestContext implement the interface
```
