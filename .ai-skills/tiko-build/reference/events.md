# tiko-build reference — imperative publish & process lifetime

> Read this when: publishing events imperatively, or writing a headless/daemon main.

## Imperative publish & keeping the process alive

**To publish events from inside a component, inject `EventBus`** — it is a built-in
dependency, resolved by the container like any bean. No plain-class workaround:

```java
@Component(scope = Scope.SINGLETON)
public class OrderApi {
    private final EventBus events;
    @Inject public OrderApi(EventBus events) { this.events = events; }
    public void place(Order o) { events.publish(new OrderPlaced(o.id())); }
}
```

`Container` itself is **not** injectable (injecting it is service location). If you
genuinely need the container — e.g. plain route handlers that aren't components — pass
it into a hand-constructed class after bootstrap, as `ThingRoutes` does in the
bootstrap pattern in [`SKILL.md`](../SKILL.md). Prefer injecting `EventBus` (or the
specific collaborators) over reaching for `Container`.

**Keep a headless process alive with one idiom: `Tiko.daemon(...).awaitShutdown()`.**
`daemon(...)` installs a JVM shutdown hook (graceful `@PreDestroy` on `Ctrl+C` /
`SIGTERM`); `awaitShutdown()` blocks `main` until then. Do **not** improvise
`Thread.join()` / `CountDownLatch`.

```java
public static void main(String[] args) {
    TikoDaemon daemon = Tiko.daemon(ConfigSources.classpath("application.yml"));
    // resolve beans / start consumers via daemon.container() ...
    daemon.awaitShutdown();   // canonical keep-alive
}
```

An app that runs its own non-daemon foreground server (e.g. Javalin `app.start()`)
does not need `awaitShutdown()` — that server thread already keeps the JVM up; use
`Tiko.create(...)` with try-with-resources or a shutdown hook, as in the bootstrap
pattern in [`SKILL.md`](../SKILL.md).
