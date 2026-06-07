---
name: tiko-cookbook-extension
description: Use when adding a new integration recipe to the tiko-build cookbook. Procedural skill — gates the addition against the three-bucket framing, enforces "ask, don't fabricate," provides the canonical recipe template, lands the recipe in the right two files.
---

# tiko-cookbook-extension

This file is the **operational distillation** of
[`docs/cookbook-extension.md`](../../docs/cookbook-extension.md). The long
doc is the source of truth for prose; this file is the shape an agent
reads to act. When this skill teaches the cookbook to grow, both files
need to grow together — update both, or the future-you reading the agent
side won't know why the human side disagrees.

## The rule — ask, don't fabricate

> When something is unclear, **stop and ask the user**. Do not guess.

A wrong recipe locks an opinionated bad default into the project's
apparent conventions. An honest *"I don't know which X you want here"*
beats an invented X every time.

This rule is load-bearing for the whole skill. The rest of this file is
mechanics; this is the spirit. If you take one thing away, it's this:
**the cookbook grows by user-confirmed recipes, never by agent-imagined
ones.**

Common decision points you must ask about, not guess:

- **Which library version** the recipe should target — never silently
  pick "latest."
- **Which `@Produces` signature** the integration needs — many libraries
  have a builder with required + optional knobs; ask which the user
  wants exposed.
- **Where the recipe sits** in the cookbook ordering — if it overlaps
  an existing recipe (another cache, another HTTP layer), ask whether
  it replaces, augments, or sits alongside.
- **Whether it belongs in the Plug-in bucket at all** vs. **Open
  design questions** — if there's any tiko-native primitive involvement
  (event topology, scope semantics), it's not a plain cookbook entry;
  surface the ambiguity rather than ramming it in.

If you don't have a confident answer for any of the above, stop coding
and write the question to the user.

## When to use this skill

- The user wants to integrate tiko with a library or framework the
  [tiko-build cookbook](../tiko-build/SKILL.md) and
  [`docs/orchestrator-model.md`](../../docs/orchestrator-model.md) don't
  cover.
- A contributor wants to add a new recipe to the cookbook.
- You (the agent) notice the cookbook is silent on a question the user
  is asking and want to propose adding it.

Not for: editing existing recipes (just edit them), inventing recipes
the user didn't ask for, generic refactoring of the cookbook.

## Step 1 — gate: does this even belong in the cookbook?

Check the proposed addition against
[`docs/orchestrator-vocabulary.md`](../../docs/orchestrator-vocabulary.md)'s
three buckets:

| Bucket | What to do |
|---|---|
| **Core** (container, scopes, event bus, compile-time wiring, lifecycle) | **Not a cookbook entry.** Surface to the user — this is a framework-feature discussion, not an integration recipe. File an issue if it's a real gap. |
| **Plug in** (HTTP, DB, cache, templates, scheduling, retry, SDK clients, security) | **Proceed to step 2.** This is what the cookbook is for. |
| **Open design questions** (extends the event model itself — new async modes, scheduling-as-event, retry-as-loop) | **Not a cookbook entry.** Surface to the user — open an issue against tiko-di; the cookbook can't make this decision. |

If you're not sure which bucket — that's a step-1 ask. Don't pick.

## Step 2 — Plug in: gather the inputs before writing a single line

Before any code, get answers from the user:

1. **Library name + Maven coordinate.** Exact `groupId:artifactId` and
   version. Never assume; libraries get renamed, forked, and
   abandoned.
2. **Construction shape.** Does the library expose a builder, a static
   factory, or just a constructor? If a builder with many knobs — ask
   which knobs the recipe should surface as `@Configuration` fields vs.
   leave at defaults.
3. **Lifecycle.** Does the library's value object hold resources? If
   `AutoCloseable`, tiko closes it automatically — no `@PreDestroy`
   needed. If not, ask whether the recipe should add one and what
   `close()`-equivalent it should call.
4. **Singleton vs per-request.** Most plug-in libraries are
   `SINGLETON`. If the user has a use case for `EVENT`-scoped (e.g.
   per-request DB connection wrappers), ask explicitly — the proxy
   generation has rules they should know about.
5. **Overlap with existing recipes.** Search the cookbook table in
   `tiko-build/SKILL.md`. If the new library competes with an existing
   recipe (e.g. user wants Vert.x and the cookbook has Javalin), ask:
   replace? augment with a *choose-one* note? sit alongside?
6. **Out-of-scope concerns.** Distributed tracing? Connection pooling
   knobs? Async semantics? Ask which the user cares about for this
   recipe.

If any of those answers comes back *"I don't know, pick a sensible
default"* — that's still an ask: confirm the default you'd pick before
writing the recipe with it.

## Step 3 — the canonical recipe template

Every cookbook entry follows the same five-element shape. Mirror it.
The three worked examples below are from the canonical cookbook — read
them before writing yours.

### The shape

| Element | What it is |
|---|---|
| **Need** | One sentence: when an agent would reach for this recipe. |
| **`@Produces` factory** | The shortest factory class that produces the library's value. Construction goes here. |
| **Lifecycle note** | One sentence: AutoCloseable? `@PreDestroy`? Lifecycle event hook? |
| **Why-this-instead-of-bundling** | One sentence in the orchestrator-model voice. Not "tiko's equivalent of X." Name the tiko-native primitive. |
| **Reference link** | Either a worked example in `tiko-examples/` if one exists, or the upstream library doc. |

### Worked example 1 — HikariCP `DataSource` (canonical, see `docs/orchestrator-model.md` §3.1)

```java
@Component(scope = Scope.SINGLETON)
public class DataSourceFactory {
    private final AppConfig config;

    @Inject
    public DataSourceFactory(AppConfig config) { this.config = config; }

    @Produces(scope = Scope.SINGLETON)
    public DataSource dataSource() {
        var hc = new HikariConfig();
        hc.setJdbcUrl(config.db().url());
        hc.setUsername(config.db().user());
        hc.setPassword(config.db().password());
        hc.setMaximumPoolSize(config.db().poolSize());
        return new HikariDataSource(hc);
    }
}
```

`HikariDataSource` is `AutoCloseable` → no `@PreDestroy` needed. Reference:
[`DataSourceFactory.java`](../../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/DataSourceFactory.java).

### Worked example 2 — Javalin HTTP server (canonical, see `docs/orchestrator-model.md` §3.5)

```java
@Component(scope = Scope.SINGLETON)
public class JavalinFactory {
    private Javalin app;

    @Produces(scope = Scope.SINGLETON)
    public Javalin javalin() {
        this.app = Javalin.create();
        return app;
    }

    @PreDestroy
    public void shutdown() { if (app != null) app.stop(); }
}
```

Javalin isn't AutoCloseable in current versions → explicit `@PreDestroy`.
Routes register in `Main`. Reference:
[`JavalinFactory.java`](../../tiko-examples/15_quickstart/src/main/java/io/tiko/examples/quickstart/JavalinFactory.java).

### Worked example 3 — Caffeine cache (canonical, see `docs/orchestrator-model.md` §3.4)

```java
@Component(scope = Scope.SINGLETON)
public class UserCacheFactory {
    @Produces(scope = Scope.SINGLETON, name = "users")
    public Cache<UUID, User> userCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .build();
    }
}
```

No lifecycle hook — Caffeine holds no external resources. The
`name = "users"` qualifier disambiguates if the project later adds a
second cache. Reference: orchestrator-model §3.4 (no example app yet —
this is fine for a recipe).

## Step 4 — anti-pattern check

Before you commit the recipe, gate it against these signals. If any
fires, stop and reconsider:

- **Are you wrapping the library?** If your recipe imports the library's
  types only inside the `@Produces` factory and the consumer never sees
  them — you've built a wrapper. That's exactly what the orchestrator
  model rejects. The recipe must expose the library's types directly to
  consumers.
- **Are you hiding lifecycle?** A recipe with a `@PostConstruct` that
  does multi-step work behind the scenes hides what the library is
  doing. Prefer construction-in-factory + minimal explicit lifecycle.
- **Are you inventing an annotation?** No `@TikoFoo`. Ever. The model is
  cookbook-by-`@Produces`, not framework-by-new-annotations. If the
  integration *needs* a new annotation, it's an Open design question,
  not a cookbook entry — surface to the user.
- **Does the recipe contradict an existing one without explaining why?**
  Cookbook entries are *additive*. If yours replaces an existing one,
  the existing one needs a deprecation note — and that's a user
  conversation, not a unilateral agent decision.

## Step 5 — where the recipe lands

Two files, both updated together:

1. **[`docs/orchestrator-model.md`](../../docs/orchestrator-model.md)** —
   the long-form prose entry. Goes under §3 (Plug-in cookbook). Numbered
   section (§3.N). Full code snippet + lifecycle note + reference link
   + the one-sentence "why-this-instead-of-bundling."
2. **[`.ai-skills/tiko-build/SKILL.md`](../tiko-build/SKILL.md)** —
   the operational distillation. Add a row to the **Cookbook table** and
   (if the library replaces a Spring reflex) a row to the **Anti-pattern
   redirect table**. Cross-link the §3.N anchor in `orchestrator-model.md`.

Don't update one without the other. The two files drift if you do.

The archetype's local copy of `tiko-build/SKILL.md`
(`.ai-skills/tiko-build/SKILL.md`) updates automatically on the next
release because it's copied from the repo's
`tiko-archetype/src/main/resources/archetype-resources/.ai-skills/tiko-build/SKILL.md`
at build time — but a recipe addition lands in the **repo's** SKILL.md
*and* the archetype's copy in the same PR.

## Quality criteria — minimal beats comprehensive

A useful single-`@Produces` snippet that lands beats a comprehensive
"this is everything about Library X" entry that never gets written.
Default to minimal:

- **One `@Produces`.** The smallest factory that gets the user past
  *"how do I plug this in?"*
- **One lifecycle line.** Just the answer to *"what happens at
  shutdown?"*
- **One sentence on why.** Why this is plug-in, not bundled.

If a recipe deserves expansion (advanced configuration, error handling,
multi-tenancy), spin it out as a follow-up PR. Ship the minimum first.

## What this skill does NOT do

- Write the recipe for the user. The skill teaches the procedure; the
  user supplies the integration target and the inputs.
- Replace user judgment on overlapping recipes. When two recipes
  compete, the user picks; the skill surfaces the choice.
- Open a PR automatically. The agent writes the files, the user reviews
  and merges — same as any other change.

## Reading list

- [`docs/orchestrator-model.md`](../../docs/orchestrator-model.md) — the
  canonical cookbook. The shape every new entry mirrors.
- [`docs/orchestrator-vocabulary.md`](../../docs/orchestrator-vocabulary.md) —
  the three-bucket gate from step 1.
- [`tiko-build/SKILL.md`](../tiko-build/SKILL.md) — the operational
  distillation. Where the table row lands.
- [`docs/mcp-design.md`](../../docs/mcp-design.md) — sibling principle
  (per-partes serving) for the other agent-facing surface.
