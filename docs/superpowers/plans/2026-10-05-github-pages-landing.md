# GitHub Pages Landing Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish a single static landing page for Tiko at `https://tomas-samek.github.io/tiko-di/`, with build-time tests that keep its claims accurate.

**Architecture:** A hand-written `site/` folder (one HTML page, one stylesheet, one SVG favicon; no JavaScript, no build step) is deployed by a GitHub Actions workflow using the official Pages actions. Two JUnit drift tests read repo-root files via `Path.of("..", …)`, following the archetype doc-sync pattern (#408). They locate page content through `<!-- sync:NAME -->` comment markers and fail the build if the page's compile-error demo, BOM version or JDK list drifts from reality.

**Tech Stack:** HTML5, CSS (custom properties, `prefers-color-scheme`), SVG; GitHub Actions (`actions/configure-pages`, `actions/upload-pages-artifact`, `actions/deploy-pages`); JUnit 5, AssertJ, Google compile-testing.

**Spec:** `docs/superpowers/specs/2026-10-05-github-pages-landing-design.md`

## Global Constraints

- Site URL: `https://tomas-samek.github.io/tiko-di/`. It's a project site served under `/tiko-di/`, so every same-site link and asset path is relative (no leading `/`).
- Pitch, used verbatim: `Tiko orchestrates, it doesn't bundle — direct access, compile-time safe, nothing wrapped.`
- Definition line: `A compile-time orchestrator for Java`
- Support line: `Every Java LTS from 21, plus every GA since the latest LTS · today 21 · 25 · 26 · 27`
- JDK support policy: every LTS from 21 onward (retired only deliberately), plus every GA release since the latest LTS. The page, the README Java badge and the `build` job matrix in `.github/workflows/maven.yml` show exactly the same set.
- Banned vocabulary in page copy: "gap", "missing", "not yet supported", "limitation", "tiko's equivalent of", or any phrasing that concedes a migration frame. Other frameworks are named only in the cold-start section.
- No JavaScript, no web fonts, no analytics; the only third-party request is the shields.io Maven Central badge image.
- Light tokens: bg `#f5f8fb`, grid `#e3eaf1`, text `#13263a`, secondary `#3f5568`, accent `#0e7490`, error `#dc2626`. Dark tokens: bg `#0c1722`, grid `#162636`, text `#e3ecf4`, secondary `#a9bccd`, accent `#22b8cf`, error `#f87171`.
- Fonts: headings and pitch `Georgia, 'Times New Roman', serif` (pitch italic); UI and body `system-ui, sans-serif`; code `ui-monospace, Consolas, monospace`.
- Java test rules (CLAUDE.md): JUnit 5 + AssertJ only, camelCase method names, `var` for locals with an obvious type, parameterize distinct cases, run `mvn -pl '!tiko-bom' spotless:apply` before committing (on JDK 21/25/26; Spotless is skipped on 27).
- Commit messages: single-line conventional `type(scope): subject`, no body.

## Review Focus

1. **Absolute paths under the project-site base path.** A `href="/…"` or `src="/…"` resolves to `tomas-samek.github.io/…` and 404s. Expected: every same-site reference is relative. Pinned by `pageUsesNoRootRelativeUrls` in Task 3.
2. **Markers deleted or duplicated during an edit.** A drift test that finds no marker must fail, not pass vacuously. Expected: `SitePage.section` throws when a marker isn't found exactly once, and the JDK check requires at least two occurrences (hero + install). Pinned in Tasks 2 and 3.
3. **Phone width (375 px).** Long code lines (the install XML, the generated factory) must scroll inside their block, not widen the page. Pinned by the `scrollWidth` check in Task 4 Step 2.
4. **Dark-mode readability of highlighted code.** Annotation, keyword, string and comment colours must stay readable on the dark code background. Pinned by the contrast check in Task 4 Step 3.
5. **`<` in code samples.** Unescaped `<` in the install XML would be parsed as tags and vanish from the rendered page. Expected: the install snippet renders `<dependencyManagement>` literally. Pinned by the rendered-text check in Task 4 Step 2.

---

## File Structure

| File | Responsibility |
|---|---|
| `site/index.html` (create) | The whole page: content, pre-highlighted code, sync markers |
| `site/style.css` (create) | Colour tokens, dark mode, grid backgrounds, layout, responsive rules |
| `site/favicon.svg` (create) | Tab icon derived from the wiring diagram |
| `tiko-processor/src/test/java/io/tiko/processor/SitePage.java` (create) | Test helper: extract a marker block as plain text |
| `tiko-processor/src/test/java/io/tiko/processor/SiteCompileErrorDemoTest.java` (create) | Compiles the page's sample; asserts the shown error is the real one |
| `tiko-archetype/src/test/java/io/tiko/archetype/SitePage.java` (create) | Same helper for the archetype module (modules share no test-jar, so the ~30-line helper is duplicated on purpose) |
| `tiko-archetype/src/test/java/io/tiko/archetype/SiteInSyncTest.java` (create) | BOM version, JDK list and URL-shape checks |
| `.github/workflows/pages.yml` (create) | Deploy `site/` to GitHub Pages |
| `.github/workflows/maven.yml` (modify, build matrix comment) | Names the JDK policy next to the matrix |
| `README.md` (modify, status line) | "Website" link |
| `.ai-skills/tiko-release/SKILL.md` (modify, Step 6) | Bump `site/index.html` on release |

---

### Task 0: Rebase onto main

PR #466 (README badge lists 27) has merged. `SiteInSyncTest` compares that badge with the CI matrix, so the branch needs it.

- [ ] **Step 1: Rebase**

```bash
git checkout feat/github-pages-landing
git fetch origin
git rebase origin/main
```

- [ ] **Step 2: Confirm the badge**

Run: `grep -o "img.shields.io/badge/Java-[^)]*" README.md`
Expected: `img.shields.io/badge/Java-21%20%7C%2025%20%7C%2026%20%7C%2027-blue.svg`

---

### Task 1: The page

**Files:**
- Create: `site/index.html`, `site/style.css`, `site/favicon.svg`

**Interfaces:**
- Produces, for Tasks 2–3: these marker pairs in `site/index.html`, each wrapping exactly the text the tests read:
  - `<!-- sync:order-service -->` … `<!-- /sync:order-service -->`: the complete `OrderService.java` (once)
  - `<!-- sync:payment-gateway -->` … `<!-- /sync:payment-gateway -->`: the complete `PaymentGateway.java` (once)
  - `<!-- sync:compile-error -->` … `<!-- /sync:compile-error -->`: the error output (once)
  - `<!-- sync:bom-version -->0.5.0<!-- /sync:bom-version -->`: inside the install snippet (once)
  - `<!-- sync:jdks -->21 · 25 · 26 · 27<!-- /sync:jdks -->`: hero and install section (twice)

- [ ] **Step 1: Create `site/favicon.svg`**

```svg
<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
  <g stroke="#0e7490" stroke-width="2.5" fill="none">
    <line x1="7" y1="8" x2="16" y2="16"/>
    <line x1="7" y1="24" x2="16" y2="16"/>
    <line x1="16" y1="16" x2="25" y2="9"/>
    <line x1="16" y1="16" x2="25" y2="23"/>
  </g>
  <circle cx="16" cy="16" r="5" fill="#0e7490"/>
  <circle cx="7" cy="8" r="3.5" fill="#fff" stroke="#0e7490" stroke-width="2"/>
  <circle cx="7" cy="24" r="3.5" fill="#fff" stroke="#0e7490" stroke-width="2"/>
  <circle cx="25" cy="9" r="3.5" fill="#fff" stroke="#0e7490" stroke-width="2"/>
  <circle cx="25" cy="23" r="3.5" fill="#fff" stroke="#0e7490" stroke-width="2"/>
</svg>
```

- [ ] **Step 2: Create `site/style.css`**

```css
:root {
  --bg: #f5f8fb;
  --grid: #e3eaf1;
  --surface: #ffffff;
  --code-bg: #f5f8fb;
  --border: #dbe5ee;
  --text: #13263a;
  --text-2: #3f5568;
  --text-3: #5a7085;
  --accent: #0e7490;
  --accent-text: #ffffff;
  --error: #dc2626;
  --tok-annotation: #0e7490;
  --tok-keyword: #7c3aed;
  --tok-string: #b45309;
  --tok-comment: #5a7085;
  --serif: Georgia, 'Times New Roman', serif;
  --sans: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
  --mono: ui-monospace, Consolas, 'SF Mono', Menlo, monospace;
}

@media (prefers-color-scheme: dark) {
  :root {
    --bg: #0c1722;
    --grid: #162636;
    --surface: #0f1d2a;
    --code-bg: #0a141e;
    --border: #22384b;
    --text: #e3ecf4;
    --text-2: #a9bccd;
    --text-3: #8ea4b8;
    --accent: #22b8cf;
    --accent-text: #0c1722;
    --error: #f87171;
    --tok-annotation: #22b8cf;
    --tok-keyword: #c4b5fd;
    --tok-string: #fbbf24;
    --tok-comment: #8ea4b8;
  }
}

*, *::before, *::after { box-sizing: border-box; }

html { -webkit-text-size-adjust: 100%; }

body {
  margin: 0;
  background: var(--bg);
  color: var(--text);
  font: 16px/1.6 var(--sans);
}

a { color: var(--accent); }

.wrap { max-width: 1040px; margin: 0 auto; padding: 0 16px; }

.grid-bg {
  background-color: var(--bg);
  background-image:
    linear-gradient(var(--grid) 1px, transparent 1px),
    linear-gradient(90deg, var(--grid) 1px, transparent 1px);
  background-size: 16px 16px;
}

.plain-bg { background: var(--surface); }

/* Header */
.site-header { display: flex; justify-content: space-between; align-items: center; padding: 20px 0; font-size: 14px; }
.brand { font-weight: 700; letter-spacing: .08em; color: var(--text); text-decoration: none; }
.site-nav a { color: var(--text-3); text-decoration: none; margin-left: 20px; }
.site-nav a:hover { color: var(--accent); }

/* Hero */
.hero { padding: 24px 0 64px; }
.hero-inner { display: flex; gap: 40px; align-items: center; }
.hero-copy { flex: 1; min-width: 0; }
h1 { font: 400 clamp(2rem, 5vw, 3.2rem)/1.12 var(--serif); margin: 0; }
.pitch { font: italic 1.2rem/1.5 var(--serif); color: var(--text-2); margin: 16px 0 8px; }
.support { font-size: 14px; color: var(--text-3); margin: 0 0 24px; }
.actions { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
.button { display: inline-block; padding: 10px 18px; border-radius: 4px; font-weight: 600; font-size: 15px; text-decoration: none; }
.button.primary { background: var(--accent); color: var(--accent-text); }
.button.secondary { border: 1px solid var(--border); background: var(--surface); color: var(--text); font-weight: 400; }
.badge { display: block; height: 20px; }
.hero-graph { flex: 0 0 auto; width: 240px; max-width: 40%; height: auto; }
.graph-edge { stroke: var(--accent); }
.graph-edge.broken { stroke: var(--error); stroke-dasharray: 3 3; }
.graph-node { fill: var(--surface); stroke: var(--accent); }
.graph-node.center { fill: var(--accent); }
.graph-node.broken { stroke: var(--error); }
.graph-label { fill: var(--text); font: 7px var(--mono); }
.graph-label.center { fill: var(--accent-text); }
.graph-label.broken { fill: var(--error); }

/* Sections */
section { padding: 64px 0; }
.label { font: 600 12px var(--sans); letter-spacing: .12em; text-transform: uppercase; color: var(--accent); margin: 0 0 8px; }
h2 { font: 400 1.9rem/1.2 var(--serif); margin: 0 0 12px; }
h3 { font: 600 1.05rem/1.3 var(--sans); margin: 0 0 8px; }
.lede { color: var(--text-2); max-width: 680px; margin: 0 0 28px; }
.note { color: var(--text-3); font-size: 14px; max-width: 680px; }

/* Bucket cards */
.cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px; }
.card { background: var(--surface); border: 1px solid var(--border); border-radius: 6px; padding: 22px; }
.card p { margin: 0; color: var(--text-2); font-size: 15px; }

/* Code */
.panels { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; align-items: start; }
.panel { min-width: 0; }
.panel-title { font: 13px var(--mono); color: var(--text-3); margin: 0 0 6px; }
pre {
  margin: 0;
  background: var(--code-bg);
  border: 1px solid var(--border);
  border-radius: 6px;
  padding: 16px;
  overflow-x: auto;
  font: 13px/1.55 var(--mono);
  color: var(--text);
}
pre + pre, pre + .panel-title { margin-top: 14px; }
pre.error { background: var(--surface); border-left: 3px solid var(--error); white-space: pre-wrap; }
.e { color: var(--error); font-weight: 600; }
.a { color: var(--tok-annotation); }
.k { color: var(--tok-keyword); }
.s { color: var(--tok-string); }
.c { color: var(--tok-comment); font-style: italic; }

/* Table */
.table-scroll { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; font-size: 14px; background: var(--surface); }
th, td { padding: 8px 12px; border-bottom: 1px solid var(--border); text-align: left; white-space: nowrap; }
td.num, th.num { text-align: right; font-family: var(--mono); }
tr.tiko td { font-weight: 700; }

/* Footer */
.site-footer { padding: 40px 0 56px; font-size: 14px; color: var(--text-3); }
.footer-links { display: flex; flex-wrap: wrap; gap: 8px 24px; padding: 0; margin: 0 0 16px; list-style: none; }

@media (max-width: 720px) {
  .hero-inner { flex-direction: column; align-items: flex-start; }
  .hero-graph { width: 200px; max-width: 70%; }
  .cards, .panels { grid-template-columns: 1fr; }
  section { padding: 48px 0; }
  .site-nav a { margin-left: 14px; }
}
```

- [ ] **Step 3: Create `site/index.html`**

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Tiko — a compile-time orchestrator for Java</title>
  <meta name="description" content="Tiko is a compile-time dependency injection and event framework for Java. Tiko orchestrates, it doesn't bundle — direct access, compile-time safe, nothing wrapped.">
  <link rel="icon" href="favicon.svg" type="image/svg+xml">
  <link rel="stylesheet" href="style.css">
</head>
<body>

<header class="grid-bg">
  <div class="wrap site-header">
    <a class="brand" href="./">TIKO</a>
    <nav class="site-nav" aria-label="Main">
      <a href="https://github.com/tomas-samek/tiko-di/blob/main/docs/orchestrator-model.md">Docs</a>
      <a href="https://github.com/tomas-samek/tiko-di/tree/main/tiko-examples">Examples</a>
      <a href="https://github.com/tomas-samek/tiko-di">GitHub</a>
    </nav>
  </div>
</header>

<main>
<div class="grid-bg hero">
  <div class="wrap">
      <div class="hero-inner">
        <div class="hero-copy">
          <h1>A compile-time orchestrator for Java</h1>
          <p class="pitch">Tiko orchestrates, it doesn't bundle — direct access, compile-time safe, nothing wrapped.</p>
          <p class="support">Every Java LTS from 21, plus every GA since the latest LTS · today <!-- sync:jdks -->21 · 25 · 26 · 27<!-- /sync:jdks --></p>
          <div class="actions">
            <a class="button primary" href="#install">Get started</a>
            <a class="button secondary" href="https://github.com/tomas-samek/tiko-di">GitHub</a>
            <a href="https://central.sonatype.com/artifact/io.github.tomas-samek/tiko-bom"><img class="badge" src="https://img.shields.io/maven-central/v/io.github.tomas-samek/tiko-bom?label=Maven%20Central&amp;color=0e7490" alt="Latest version on Maven Central"></a>
          </div>
        </div>
        <svg class="hero-graph" viewBox="0 0 110 96" role="img" aria-label="Wiring graph: a Service depends on a Repo, a Bus and a Pool; a dashed red edge marks a dependency the compiler cannot resolve">
          <line class="graph-edge" x1="20" y1="18" x2="58" y2="48" stroke-width="1.5"/>
          <line class="graph-edge" x1="20" y1="78" x2="58" y2="48" stroke-width="1.5"/>
          <line class="graph-edge" x1="58" y1="48" x2="94" y2="22" stroke-width="1.5"/>
          <line class="graph-edge broken" x1="58" y1="48" x2="94" y2="74" stroke-width="1.5"/>
          <rect class="graph-node" x="4" y="10" width="32" height="16" rx="3"/>
          <text class="graph-label" x="20" y="21" text-anchor="middle">Repo</text>
          <rect class="graph-node" x="4" y="70" width="32" height="16" rx="3"/>
          <text class="graph-label" x="20" y="81" text-anchor="middle">Bus</text>
          <rect class="graph-node center" x="40" y="40" width="36" height="16" rx="3"/>
          <text class="graph-label center" x="58" y="51" text-anchor="middle">Service</text>
          <rect class="graph-node" x="78" y="14" width="30" height="16" rx="3"/>
          <text class="graph-label" x="93" y="25" text-anchor="middle">Pool</text>
          <rect class="graph-node broken" x="78" y="66" width="30" height="16" rx="3"/>
          <text class="graph-label broken" x="93" y="77" text-anchor="middle">???</text>
        </svg>
      </div>
  </div>
</div>

<section id="what" class="plain-bg">
  <div class="wrap">
    <p class="label">What Tiko is</p>
    <h2>The three buckets</h2>
    <p class="lede">The container, the scopes, the event bus and the wiring graph ship as the framework. Every concern in a Tiko-built service lands in exactly one bucket.</p>
    <div class="cards">
      <div class="card">
        <h3>Core — what Tiko ships</h3>
        <p>DI container, scopes (<code>SINGLETON</code> / <code>EVENT</code> / <code>PROTOTYPE</code>), event bus, <code>@EventHandler</code> + <code>@EventTrigger</code>, compile-time validation, lifecycle hooks, typed <code>@Configuration</code> records.</p>
      </div>
      <div class="card">
        <h3>Plug in — you bring the library</h3>
        <p>HTTP, DataSource, migrations, caching, templates, security, any SDK client. Declare a <code>@Produces</code> method, return the library's value, consume it as a constructor parameter. No wrapper, no adapter layer.</p>
      </div>
      <div class="card">
        <h3>Open design questions</h3>
        <p>Async generalisation, scheduling as a tick event, retry as an event loop — the small set of unresolved questions about extending the event model itself.</p>
      </div>
    </div>
  </div>
</section>

<section id="taste" class="grid-bg">
  <div class="wrap">
    <p class="label">30-second taste</p>
    <h2>Plain classes, constructor injection</h2>
    <p class="lede">The annotation processor validates all dependencies at compile time and generates the wiring code — plain Java you can read and step through. No reflection, no classpath scanning in your wiring.</p>
<pre><code><span class="a">@Component</span>(scope = Scope.SINGLETON)
<span class="k">public class</span> UserRepository {
    <span class="k">public</span> User findById(String id) { <span class="c">/* ... */</span> <span class="k">return null</span>; }
}

<span class="a">@Component</span>(scope = Scope.SINGLETON)
<span class="k">public class</span> UserService {
    <span class="k">private final</span> UserRepository repository;

    <span class="a">@Inject</span>
    <span class="k">public</span> UserService(UserRepository repository) {
        <span class="k">this</span>.repository = repository;
    }
}

<span class="k">try</span> (Container container = Tiko.create()) {
    UserService service = container.get(UserService.<span class="k">class</span>);
}</code></pre>
  </div>
</section>

<section id="safe" class="plain-bg">
  <div class="wrap">
    <p class="label">Compile-time safe</p>
    <h2>Wiring mistakes fail the build, not production</h2>
    <p class="lede">The processor checks the whole graph — unresolved and ambiguous dependencies, cycles, scope violations — and tells you how to fix it. This is the real output for the code on the left.</p>
    <div class="panels">
      <div class="panel">
        <p class="panel-title">OrderService.java</p>
<pre><code><!-- sync:order-service --><span class="k">package</span> demo;

<span class="k">import</span> io.tiko.Scope;
<span class="k">import</span> io.tiko.annotations.Component;
<span class="k">import</span> io.tiko.annotations.Inject;

<span class="a">@Component</span>(scope = Scope.SINGLETON)
<span class="k">public class</span> OrderService {
    <span class="a">@Inject</span>
    <span class="k">public</span> OrderService(PaymentGateway gateway) {}
}<!-- /sync:order-service --></code></pre>
        <p class="panel-title">PaymentGateway.java</p>
<pre><code><!-- sync:payment-gateway --><span class="k">package</span> demo;

<span class="k">public interface</span> PaymentGateway {
    <span class="k">void</span> charge(String orderId, <span class="k">long</span> cents);
}<!-- /sync:payment-gateway --></code></pre>
      </div>
      <div class="panel">
        <p class="panel-title">javac</p>
<pre class="error"><code><!-- sync:compile-error -->OrderService.java:8: <span class="e">error:</span> Cannot resolve dependency: demo.PaymentGateway

  Suggested fixes:
  1. Add a @Component class of type demo.PaymentGateway
  2. Create a @Produces factory method that returns demo.PaymentGateway
  3. Check if the dependency has the correct qualifier (@Named)<!-- /sync:compile-error --></code></pre>
      </div>
    </div>
  </div>
</section>

<section id="plugin" class="grid-bg">
  <div class="wrap">
    <p class="label">Plug in any library</p>
    <h2>Tiko owns the seam, never the library</h2>
    <p class="lede">A <code>@Produces</code> method hands the container a real HikariCP pool; your repository takes a plain <code>javax.sql.DataSource</code>. Nothing is wrapped — you call the driver directly. From <a href="https://github.com/tomas-samek/tiko-di/tree/main/tiko-examples/15_quickstart">tiko-examples/15_quickstart</a>.</p>
    <div class="panels">
      <div class="panel">
        <p class="panel-title">DataSourceFactory.java</p>
<pre><code><span class="a">@Component</span>(scope = Scope.SINGLETON)
<span class="k">public class</span> DataSourceFactory {

    <span class="k">private final</span> AppConfig config;

    <span class="a">@Inject</span>
    <span class="k">public</span> DataSourceFactory(AppConfig config) {
        <span class="k">this</span>.config = config;
    }

    <span class="a">@Produces</span>(scope = Scope.SINGLETON)
    <span class="k">public</span> DataSource dataSource() {
        <span class="k">var</span> hc = <span class="k">new</span> HikariConfig();
        hc.setJdbcUrl(config.db().url());
        hc.setUsername(config.db().user());
        hc.setPassword(config.db().password());
        hc.setMaximumPoolSize(config.db().poolSize());
        <span class="k">return new</span> HikariDataSource(hc);
    }
}</code></pre>
      </div>
      <div class="panel">
        <p class="panel-title">NoteRepository.java</p>
<pre><code><span class="a">@Component</span>(scope = Scope.SINGLETON)
<span class="k">public class</span> NoteRepository {

    <span class="k">private final</span> DataSource ds;

    <span class="a">@Inject</span>
    <span class="k">public</span> NoteRepository(DataSource ds) {
        <span class="k">this</span>.ds = ds;
    }

    <span class="c">// raw JDBC against the pool: ds.getConnection() ...</span>
}</code></pre>
      </div>
    </div>
  </div>
</section>

<section id="events" class="plain-bg">
  <div class="wrap">
    <p class="label">Events chain</p>
    <h2>A handler's return value is the next event</h2>
    <p class="lede"><code>@EventTrigger</code> publishes what the handler returns; a guard decides whether the chain continues. Routing is by payload type, checked at compile time. From <a href="https://github.com/tomas-samek/tiko-di/tree/main/tiko-examples/03_events">tiko-examples/03_events</a> (logging trimmed).</p>
<pre><code><span class="a">@Component</span>(scope = Scope.SINGLETON)
<span class="k">public class</span> OrderWorkflow {

    <span class="a">@EventHandler</span>
    <span class="a">@EventTrigger</span>(eventName = <span class="s">"OrderValidated"</span>)
    <span class="k">public</span> OrderValidated onPlaced(OrderPlaced event) {
        <span class="k">boolean</span> valid = event.total() &gt; 0;
        <span class="k">return new</span> OrderValidated(event.orderId(), event.customer(), event.total(), valid);
    }

    <span class="a">@EventHandler</span>
    <span class="a">@EventTrigger</span>(eventName = <span class="s">"OrderShipped"</span>, guard = ValidOrderGuard.<span class="k">class</span>)
    <span class="k">public</span> OrderShipped onValidated(OrderValidated event) {
        <span class="k">return new</span> OrderShipped(event.orderId(), event.customer(), event.total());
    }
}</code></pre>
  </div>
</section>

<section id="under-the-hood" class="grid-bg">
  <div class="wrap">
    <p class="label">Under the hood</p>
    <h2>Generated code you can step through</h2>
    <p class="lede">For the 30-second taste above, the processor emits a factory like this (excerpt). It's ordinary Java — set a breakpoint in it.</p>
<pre><code><span class="a">@Generated</span>(<span class="s">"io.tiko.processor.generator.ComponentFactoryGenerator"</span>)
<span class="k">public final class</span> UserServiceFactory {
  <span class="c">// ...</span>
  <span class="k">public</span> UserService create() {
    UserRepository repository = container.options().resolveOverride(UserRepository.<span class="k">class</span>, () -&gt; container.getUserRepository());
    UserService instance = <span class="k">new</span> UserService(repository);
    <span class="k">return</span> instance;
  }
}</code></pre>

    <h3 style="margin-top:40px">Measured cold start</h3>
    <p class="note">The same four-singleton, two-module workload in eight setups. Median of 10 cold JVM invocations, default JVM and GC, Java 21, on a development laptop. These numbers move on different hardware — re-run locally before drawing conclusions. <code>total_ns</code> sums create + first gets + close.</p>
    <div class="table-scroll">
      <table>
        <thead>
          <tr><th>Setup</th><th class="num">Wall-clock (ms)</th><th class="num"><code>total_ns</code> (ms)</th><th>Style</th></tr>
        </thead>
        <tbody>
          <tr><td>jvm baseline (<code>java -version</code>)</td><td class="num">104</td><td class="num">—</td><td>—</td></tr>
          <tr><td>plain (no DI)</td><td class="num">172</td><td class="num">36</td><td>floor reference</td></tr>
          <tr><td>dagger</td><td class="num">186</td><td class="num">44</td><td>compile-time, lazy</td></tr>
          <tr class="tiko"><td>tiko</td><td class="num">202</td><td class="num">61</td><td>compile-time, lazy</td></tr>
          <tr><td>avaje</td><td class="num">228</td><td class="num">105</td><td>compile-time, eager</td></tr>
          <tr><td>hk2</td><td class="num">307</td><td class="num">159</td><td>runtime, reflection, lazy</td></tr>
          <tr><td>guice</td><td class="num">373</td><td class="num">230</td><td>runtime, reflection, lazy</td></tr>
          <tr><td>micronaut (inject-only)</td><td class="num">459</td><td class="num">308</td><td>compile-time, eager + AOP</td></tr>
          <tr><td>spring</td><td class="num">529</td><td class="num">368</td><td>runtime, reflection, eager</td></tr>
        </tbody>
      </table>
    </div>
    <p class="note">The honest reading: the dominant axis is <strong>lazy vs eager init</strong>, not "compile-time vs runtime." Four clusters emerge — lean compile-time-lazy (plain, Dagger, Tiko at 36–61 ms <code>total_ns</code>), compile-time-eager (Avaje at 105 ms), runtime-reflection-lazy (HK2, Guice at 159–230 ms), and eager-with-overhead (Micronaut, Spring at 308–368 ms). Within each laziness class the compile-time framework is cheaper (Tiko &lt; Guice; Avaje &lt; Spring), but Avaje (compile-time + eager) is slower than HK2 and Guice (runtime + lazy) — eagerness costs more than reflection saves at this scale.</p>
    <p class="note">Methodology, per-phase tables and reproduction: <a href="https://github.com/tomas-samek/tiko-di/blob/main/comparisons/README.md">comparisons/README.md</a>.</p>
  </div>
</section>

<section id="install" class="plain-bg">
  <div class="wrap">
    <p class="label">Get started</p>
    <h2>Install from Maven Central</h2>
    <p class="lede">Import the BOM once; the modules and the annotation processor take their version from it. Supported JDKs: every Java LTS from 21, plus every GA since the latest LTS — today <!-- sync:jdks -->21 · 25 · 26 · 27<!-- /sync:jdks -->.</p>
<pre><code>&lt;dependencyManagement&gt;
    &lt;dependencies&gt;
        &lt;dependency&gt;
            &lt;groupId&gt;io.github.tomas-samek&lt;/groupId&gt;
            &lt;artifactId&gt;tiko-bom&lt;/artifactId&gt;
            &lt;version&gt;<!-- sync:bom-version -->0.5.0<!-- /sync:bom-version -->&lt;/version&gt;
            &lt;type&gt;pom&lt;/type&gt;
            &lt;scope&gt;import&lt;/scope&gt;
        &lt;/dependency&gt;
    &lt;/dependencies&gt;
&lt;/dependencyManagement&gt;

&lt;dependencies&gt;
    &lt;dependency&gt;
        &lt;groupId&gt;io.github.tomas-samek&lt;/groupId&gt;
        &lt;artifactId&gt;tiko-api&lt;/artifactId&gt;
    &lt;/dependency&gt;
    &lt;dependency&gt;
        &lt;groupId&gt;io.github.tomas-samek&lt;/groupId&gt;
        &lt;artifactId&gt;tiko-runtime&lt;/artifactId&gt;
    &lt;/dependency&gt;
&lt;/dependencies&gt;

&lt;build&gt;
    &lt;plugins&gt;
        &lt;plugin&gt;
            &lt;groupId&gt;org.apache.maven.plugins&lt;/groupId&gt;
            &lt;artifactId&gt;maven-compiler-plugin&lt;/artifactId&gt;
            &lt;version&gt;3.13.0&lt;/version&gt;
            &lt;configuration&gt;
                &lt;annotationProcessorPaths&gt;
                    &lt;path&gt;
                        &lt;groupId&gt;io.github.tomas-samek&lt;/groupId&gt;
                        &lt;artifactId&gt;tiko-processor&lt;/artifactId&gt;
                    &lt;/path&gt;
                &lt;/annotationProcessorPaths&gt;
            &lt;/configuration&gt;
        &lt;/plugin&gt;
    &lt;/plugins&gt;
&lt;/build&gt;</code></pre>
    <p class="note">On JDK 23+, <code>javac</code> runs annotation processing only when asked — the snippet above already does (it needs <code>maven-compiler-plugin</code> ≥ 3.13.0). Gradle and plain <code>javac</code>: <a href="https://github.com/tomas-samek/tiko-di/blob/main/docs/jdk-23-setup.md">jdk-23-setup.md</a>. Starting fresh? The <a href="https://github.com/tomas-samek/tiko-di#scaffold-a-new-project-archetype">Maven archetype</a> scaffolds a runnable project.</p>
  </div>
</section>
</main>

<footer class="grid-bg">
  <div class="wrap site-footer">
    <ul class="footer-links">
      <li><a href="https://github.com/tomas-samek/tiko-di/blob/main/docs/orchestrator-model.md">Orchestrator model</a></li>
      <li><a href="https://github.com/tomas-samek/tiko-di/blob/main/docs/di-and-scopes.md">DI &amp; scopes</a></li>
      <li><a href="https://github.com/tomas-samek/tiko-di/blob/main/docs/events.md">Events</a></li>
      <li><a href="https://github.com/tomas-samek/tiko-di/tree/main/tiko-examples">Examples</a></li>
      <li><a href="https://github.com/tomas-samek/tiko-di#scaffold-a-new-project-archetype">Archetype</a></li>
      <li><a href="https://central.sonatype.com/artifact/io.github.tomas-samek/tiko-bom">Maven Central</a></li>
      <li><a href="https://github.com/tomas-samek/tiko-di">GitHub</a></li>
    </ul>
    <p>Tiko is open source under the <a href="https://github.com/tomas-samek/tiko-di/blob/main/LICENSE">MIT license</a>.</p>
  </div>
</footer>

</body>
</html>
```

- [ ] **Step 4: Check the markers**

Run: `grep -c "<!-- sync:" site/index.html`
Expected: `6` (order-service, payment-gateway, compile-error, bom-version, and jdks twice)

- [ ] **Step 5: Commit**

```bash
git add site/
git commit -m "feat(site): add the GitHub Pages landing page"
```

---

### Task 2: Compile-error drift test

**Files:**
- Create: `tiko-processor/src/test/java/io/tiko/processor/SitePage.java`
- Create: `tiko-processor/src/test/java/io/tiko/processor/SiteCompileErrorDemoTest.java`

**Interfaces:**
- Consumes: the `order-service`, `payment-gateway` and `compile-error` markers from Task 1.
- Produces: `SitePage` (package-private, `io.tiko.processor`): `static String section(String page, String name)`, `static List<String> sections(String page, String name)`, `static String normalize(String text)`.

- [ ] **Step 1: Write the helper**

```java
package io.tiko.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads {@code <!-- sync:NAME -->} … {@code <!-- /sync:NAME -->} blocks out of {@code site/index.html} as
 * plain text: tags stripped, the four entities the page uses unescaped.
 */
final class SitePage {

    private SitePage() {}

    /** The single block called {@code name}; fails if it's missing or duplicated, so a test can't pass vacuously. */
    static String section(String page, String name) {
        var found = sections(page, name);
        if (found.size() != 1) {
            throw new IllegalStateException("Expected exactly one sync:" + name + " block in site/index.html, found "
                    + found.size());
        }
        return found.get(0);
    }

    static List<String> sections(String page, String name) {
        var quoted = Pattern.quote(name);
        var matcher = Pattern.compile("<!-- sync:" + quoted + " -->(.*?)<!-- /sync:" + quoted + " -->", Pattern.DOTALL)
                .matcher(page);
        var found = new ArrayList<String>();
        while (matcher.find()) {
            found.add(toText(matcher.group(1)));
        }
        return found;
    }

    /** Trailing whitespace per line and surrounding blank lines removed; line endings normalised to {@code \n}. */
    static String normalize(String text) {
        return text.lines().map(String::stripTrailing).collect(Collectors.joining("\n")).strip();
    }

    private static String toText(String html) {
        return html.replaceAll("<[^>]+>", "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&");
    }
}
```

- [ ] **Step 2: Write the test**

```java
package io.tiko.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.testing.compile.Compiler;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * Drift gate for the landing page: the "Compile-time safe" section of {@code site/index.html} shows two
 * source files and the error Tiko reports for them. This compiles exactly the page's sources and fails
 * when the processor's real output no longer matches what the page shows.
 */
class SiteCompileErrorDemoTest {

    /** Relative to the {@code tiko-processor} module directory. */
    private static final Path PAGE = Path.of("..", "site", "index.html");

    @Test
    void pageShowsTheErrorTikoActuallyReports() throws IOException {
        var page = Files.readString(PAGE);
        var orderService = SitePage.section(page, "order-service");
        var paymentGateway = SitePage.section(page, "payment-gateway");
        var shownError = SitePage.section(page, "compile-error");

        var compilation = Compiler.javac()
                .withProcessors(new TikoAnnotationProcessor())
                .compile(
                        JavaFileObjects.forSourceString("demo.OrderService", orderService),
                        JavaFileObjects.forSourceString("demo.PaymentGateway", paymentGateway));

        var errors = compilation.errors();
        assertThat(errors).as("the page's sample must produce exactly one error").hasSize(1);
        var error = errors.get(0);
        var actual = "OrderService.java:" + error.getLineNumber() + ": error: " + error.getMessage(Locale.ROOT);

        assertThat(SitePage.normalize(shownError))
                .as("site/index.html shows a different compile error than Tiko reports for the page's own sample."
                        + " Update the sync:compile-error block to the actual output.")
                .isEqualTo(SitePage.normalize(actual));
    }
}
```

- [ ] **Step 3: Run it**

Run: `mvn -B test -pl tiko-processor -am -Dtest=SiteCompileErrorDemoTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 4: Prove it catches drift**

Temporarily change `3. Check if the dependency` to `3. Verify the dependency` in `site/index.html`, then rerun the Step 3 command.
Expected: FAIL with "site/index.html shows a different compile error". Then revert: `git checkout site/index.html`.

- [ ] **Step 5: Prove a missing marker fails**

Temporarily delete the `<!-- sync:compile-error -->` opening comment, rerun the Step 3 command.
Expected: ERROR with `IllegalStateException: Expected exactly one sync:compile-error block in site/index.html, found 0`. Then revert: `git checkout site/index.html`.

- [ ] **Step 6: Format and commit**

```bash
mvn -B -q -pl '!tiko-bom' spotless:apply
git add tiko-processor/src/test/java/io/tiko/processor/SitePage.java tiko-processor/src/test/java/io/tiko/processor/SiteCompileErrorDemoTest.java
git commit -m "test(site): fail the build when the landing page's compile-error demo drifts"
```

---

### Task 3: Version, JDK and URL drift test

**Files:**
- Create: `tiko-archetype/src/test/java/io/tiko/archetype/SitePage.java`
- Create: `tiko-archetype/src/test/java/io/tiko/archetype/SiteInSyncTest.java`
- Modify: `.github/workflows/maven.yml` (the line above `java: [ '21', '25', '26', '27' ]` in the `build` job)

**Interfaces:**
- Consumes: the `bom-version` and `jdks` markers from Task 1; the README Installation snippet and Java badge; the `build` job matrix.
- Produces: `SitePage` (package-private, `io.tiko.archetype`), with the same three methods as in Task 2.

- [ ] **Step 1: Write the helper**

Create `tiko-archetype/src/test/java/io/tiko/archetype/SitePage.java` with exactly the Task 2 Step 1 code, changing only the first line to `package io.tiko.archetype;`. The two modules share no test-jar, so the duplication is deliberate.

```java
package io.tiko.archetype;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads {@code <!-- sync:NAME -->} … {@code <!-- /sync:NAME -->} blocks out of {@code site/index.html} as
 * plain text: tags stripped, the four entities the page uses unescaped.
 */
final class SitePage {

    private SitePage() {}

    /** The single block called {@code name}; fails if it's missing or duplicated, so a test can't pass vacuously. */
    static String section(String page, String name) {
        var found = sections(page, name);
        if (found.size() != 1) {
            throw new IllegalStateException("Expected exactly one sync:" + name + " block in site/index.html, found "
                    + found.size());
        }
        return found.get(0);
    }

    static List<String> sections(String page, String name) {
        var quoted = Pattern.quote(name);
        var matcher = Pattern.compile("<!-- sync:" + quoted + " -->(.*?)<!-- /sync:" + quoted + " -->", Pattern.DOTALL)
                .matcher(page);
        var found = new ArrayList<String>();
        while (matcher.find()) {
            found.add(toText(matcher.group(1)));
        }
        return found;
    }

    /** Trailing whitespace per line and surrounding blank lines removed; line endings normalised to {@code \n}. */
    static String normalize(String text) {
        return text.lines().map(String::stripTrailing).collect(Collectors.joining("\n")).strip();
    }

    private static String toText(String html) {
        return html.replaceAll("<[^>]+>", "")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&amp;", "&");
    }
}
```

- [ ] **Step 2: Write the test**

```java
package io.tiko.archetype;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Drift gate for the landing page ({@code site/index.html}). The page states the released BOM version and
 * the supported JDKs as literal text; both go stale silently. JDK support policy: every LTS from 21 onward
 * plus every GA since the latest LTS — the page, the README Java badge and the CI build matrix must list the
 * same set.
 */
class SiteInSyncTest {

    /** Paths relative to the {@code tiko-archetype} module directory. */
    private static final Path PAGE = Path.of("..", "site", "index.html");

    private static final Path README = Path.of("..", "README.md");
    private static final Path CI_WORKFLOW = Path.of("..", ".github", "workflows", "maven.yml");

    private static final Pattern README_BOM_VERSION =
            Pattern.compile("<artifactId>tiko-bom</artifactId>\\s*<version>([^<]+)</version>");
    private static final Pattern README_JAVA_BADGE = Pattern.compile("img\\.shields\\.io/badge/Java-([^-]+)-");
    private static final Pattern CI_BUILD_MATRIX = Pattern.compile("(?s)\\bbuild:.*?java: \\[([^\\]]*)]");
    private static final Pattern ROOT_RELATIVE_URL = Pattern.compile("(?:href|src)=\"/(?!/)");

    @Test
    void bomVersionMatchesReadme() throws IOException {
        var pageVersion = SitePage.section(Files.readString(PAGE), "bom-version").strip();

        assertThat(pageVersion)
                .as("site/index.html install snippet advertises a different tiko-bom version than README.md."
                        + " Bump both on release (tiko-release skill, Step 6).")
                .isEqualTo(firstGroup(README_BOM_VERSION, Files.readString(README)));
    }

    static Stream<Arguments> jdkListings() throws IOException {
        var page = Files.readString(PAGE);
        var readme = Files.readString(README);
        var badge = URLDecoder.decode(firstGroup(README_JAVA_BADGE, readme), StandardCharsets.UTF_8);
        var pageListings = SitePage.sections(page, "jdks");
        assertThat(pageListings)
                .as("site/index.html must state the JDK list in the hero and the install section")
                .hasSizeGreaterThanOrEqualTo(2);
        var rows = pageListings.stream()
                .map(listing -> Arguments.of("site/index.html sync:jdks", split(listing, "·")));
        return Stream.concat(rows, Stream.of(Arguments.of("README Java badge", split(badge, "\\|"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("jdkListings")
    void jdkListMatchesCiBuildMatrix(String where, List<String> jdks) throws IOException {
        var matrix = split(firstGroup(CI_BUILD_MATRIX, Files.readString(CI_WORKFLOW)).replace("'", ""), ",");

        assertThat(jdks)
                .as("%s lists different JDKs than the build matrix in .github/workflows/maven.yml. Policy: every"
                        + " LTS from 21 onward plus every GA since the latest LTS; update all three together.", where)
                .isEqualTo(matrix);
    }

    @Test
    void pageUsesNoRootRelativeUrls() throws IOException {
        assertThat(ROOT_RELATIVE_URL.matcher(Files.readString(PAGE)).find())
                .as("The site is served under /tiko-di/, so a root-relative href/src (\"/...\") would 404."
                        + " Use a relative path.")
                .isFalse();
    }

    private static String firstGroup(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        if (!matcher.find()) {
            throw new IllegalStateException("Pattern not found: " + pattern.pattern());
        }
        return matcher.group(1);
    }

    private static List<String> split(String list, String separator) {
        return Arrays.stream(list.split(separator)).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
```

- [ ] **Step 3: Run it**

Run: `mvn -B test -pl tiko-archetype -Dtest=SiteInSyncTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 5, Failures: 0, Errors: 0` (one version test, three JDK rows: hero, install and badge, one URL test)

- [ ] **Step 4: Prove each check catches drift**

Make each change below in turn, rerun the Step 3 command, check that the named test fails, then restore the file with `git checkout <file>`:
- `site/index.html`: change the `sync:bom-version` content to `0.4.0`. Expected: `bomVersionMatchesReadme` fails.
- `.github/workflows/maven.yml`: change the build matrix to `[ '21', '25', '26' ]`. Expected: all three `jdkListMatchesCiBuildMatrix` rows fail.
- `site/index.html`: change `href="style.css"` to `href="/style.css"`. Expected: `pageUsesNoRootRelativeUrls` fails.

- [ ] **Step 5: Name the policy next to the matrix**

In `.github/workflows/maven.yml`, `build` job, insert the comment directly above `        java: [ '21', '25', '26', '27' ]`:

```yaml
        # JDK support policy: every LTS from 21 onward plus every GA since the latest LTS.
        # README Java badge and site/index.html list the same set (SiteInSyncTest enforces it).
        java: [ '21', '25', '26', '27' ]
```

Rerun the Step 3 command. Expected: still 5 passing.

- [ ] **Step 6: Format and commit**

```bash
mvn -B -q -pl '!tiko-bom' spotless:apply
git add tiko-archetype/src/test/java/io/tiko/archetype/SitePage.java tiko-archetype/src/test/java/io/tiko/archetype/SiteInSyncTest.java .github/workflows/maven.yml
git commit -m "test(site): keep the landing page's BOM version and JDK list in sync"
```

---

### Task 4: Browser verification

No files change unless a check fails. If one does, fix `site/` and commit `fix(site): <what>`.

- [ ] **Step 1: Open the page**

Open `site/index.html` from disk in Chrome (Claude-in-Chrome: `navigate` to the `file:///W:/workspace/tiko-di/site/index.html` URL). Confirm every section renders: hero with diagram, three cards, six code blocks, error panel, table, install snippet, footer.

- [ ] **Step 2: Phone width and escaping**

Resize to 375 × 800 (`resize_window`). Then run in the page (`javascript_tool`):

```js
({
  pageOverflow: document.documentElement.scrollWidth > window.innerWidth,
  installRendersXml: document.querySelector('#install pre').textContent.includes('<dependencyManagement>'),
  h1Count: document.querySelectorAll('h1').length
})
```

Expected: `{ pageOverflow: false, installRendersXml: true, h1Count: 1 }`. Take a screenshot of the hero and the compile-error section at this width: the panels are stacked, and code scrolls inside its own block.

- [ ] **Step 3: Dark mode and contrast**

Emulate dark mode: DevTools Rendering → `prefers-color-scheme: dark`, or switch the OS theme. Screenshot the hero and the "Compile-time safe" section. Check WCAG AA contrast (≥ 4.5:1 for normal text) for these pairs. Compute them in the page with `javascript_tool` using the WCAG relative-luminance formula, or check each pair in a contrast checker:
- light: `#13263a`, `#3f5568`, `#5a7085` on `#f5f8fb` and `#ffffff`; `#0e7490`, `#7c3aed`, `#b45309`, `#dc2626` on `#f5f8fb` and `#ffffff`
- dark: `#e3ecf4`, `#a9bccd`, `#8ea4b8` on `#0c1722`, `#0f1d2a` and `#0a141e`; `#22b8cf`, `#c4b5fd`, `#fbbf24`, `#f87171` on `#0a141e` and `#0f1d2a`

Expected: every pair ≥ 4.5:1. If one fails, darken it (light) or lighten it (dark) in `style.css`, then recheck.

- [ ] **Step 4: Links**

```bash
grep -oE 'href="https?://[^"]+"' site/index.html | sed -E 's/^href="|"$//g' | sort -u | while read -r u; do
  printf '%s %s\n' "$(curl -s -o /dev/null -w '%{http_code}' -L "$u")" "$u"
done
grep -oE 'href="#[^"]+"' site/index.html | sort -u
```

Expected: every URL prints `200`. Each `#anchor` (`#install`) matches an `id=` in the page.

---

### Task 5: Deployment workflow and docs touch-ups

**Files:**
- Create: `.github/workflows/pages.yml`
- Modify: `README.md` (the `**Status: on Maven Central**` line)
- Modify: `.ai-skills/tiko-release/SKILL.md` (Step 6 bullet list and the straggler `grep`)

- [ ] **Step 1: Check the current major versions of the Pages actions**

```bash
for a in actions/configure-pages actions/upload-pages-artifact actions/deploy-pages; do
  printf '%s ' "$a"; gh api "repos/$a/releases/latest" --jq .tag_name
done
```

Use each action's major tag (e.g. `v5.0.0` → `@v5`) in Step 2. The YAML below shows the majors current when this plan was written; replace any that are older than the output.

- [ ] **Step 2: Create `.github/workflows/pages.yml`**

```yaml
# Deploys the static landing page in site/ to https://tomas-samek.github.io/tiko-di/.
# One-time repo setting: Settings → Pages → Source = "GitHub Actions".
name: Deploy site to GitHub Pages

on:
  push:
    branches: [ "main" ]
    paths:
      - 'site/**'
      - '.github/workflows/pages.yml'
  workflow_dispatch:

permissions:
  contents: read
  pages: write
  id-token: write

concurrency:
  group: pages
  cancel-in-progress: false

jobs:
  deploy:
    name: Deploy
    runs-on: ubuntu-latest
    environment:
      name: github-pages
      url: ${{ steps.deployment.outputs.page_url }}
    steps:
      - uses: actions/checkout@v4
      - uses: actions/configure-pages@v5
      - uses: actions/upload-pages-artifact@v3
        with:
          path: site
      - id: deployment
        uses: actions/deploy-pages@v4
```

- [ ] **Step 3: Add the Website link to the README**

In `README.md`, replace the line

```markdown
**Status: on Maven Central** (latest version in the badge above). Suitable for early-adopter experimentation. See [docs/roadmap.md](./docs/roadmap.md) for what ships today and what's next.
```

with

```markdown
**Website: [tomas-samek.github.io/tiko-di](https://tomas-samek.github.io/tiko-di/)** · **Status: on Maven Central** (latest version in the badge above). Suitable for early-adopter experimentation. See [docs/roadmap.md](./docs/roadmap.md) for what ships today and what's next.
```

- [ ] **Step 4: Add the site to the release checklist**

In `.ai-skills/tiko-release/SKILL.md`, Step 6, add this bullet after the `docs/jdk-23-setup.md` bullet:

```markdown
- `site/index.html` — the `tiko-bom` version between the
  `<!-- sync:bom-version -->` markers in the Install snippet.
  `SiteInSyncTest` fails the build if it disagrees with the README.
```

and change the straggler sweep line `grep -n "<prior X.Y.Z>" README.md docs/jdk-23-setup.md` to:

```bash
grep -n "<prior X.Y.Z>" README.md docs/jdk-23-setup.md site/index.html
```

- [ ] **Step 5: Check the skill copy shipped in the archetype**

Run: `mvn -B test -pl tiko-archetype`
Expected: all tests pass. `ArchetypeBundledSkillsInSyncTest` only covers the `tiko-build` skills; if it fails mentioning `tiko-release`, apply the same edit to the bundled copy it names.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/pages.yml README.md .ai-skills/tiko-release/SKILL.md
git commit -m "ci(site): deploy site/ to GitHub Pages; link it from the README and release checklist"
```

---

### Task 6: Full build, PR and rollout

The repository-settings steps (Step 3 and Step 6) change public settings: ask the user before each.

- [ ] **Step 1: Full build on JDK 21**

Run: `mvn -B clean package > "$SCRATCHPAD/build.log" 2>&1; echo "exit=$?"` (with `$SCRATCHPAD` set to the session scratchpad directory, so the log stays out of the repo)
Expected: `exit=0` and `BUILD SUCCESS`; `SiteCompileErrorDemoTest` and `SiteInSyncTest` appear in the surefire output with 0 failures.

- [ ] **Step 2: Push and open the PR**

Write this body to `pr-site.md` in the session scratchpad directory (never in the repo):

```markdown
## Summary

- **`site/`** — a static landing page for `https://tomas-samek.github.io/tiko-di/`: what Tiko is (pitch, three buckets), a 30-second taste, and evidence — the real compile error for a missing dependency, a `@Produces` HikariCP plug-in, `@EventHandler` + `@EventTrigger` chaining, a generated-factory excerpt and the cold-start table. No JavaScript, no web fonts, no tracking; light/dark follows the OS.
- **`.github/workflows/pages.yml`** — deploys `site/` with the official Pages actions on pushes to `main` that touch it.
- **Drift tests** — `SiteCompileErrorDemoTest` compiles the page's sample and checks the shown error is the real one; `SiteInSyncTest` checks the BOM version against the README and the JDK list against the CI matrix and README badge (JDK support policy: every LTS from 21 plus every GA since the latest LTS), and that no URL is root-relative.
- README links the site; the release checklist bumps `site/index.html`.

Design: `docs/superpowers/specs/2026-10-05-github-pages-landing-design.md`.

## Verification

- `mvn -B clean package` on JDK 21: BUILD SUCCESS, new tests green; each drift test was shown to fail when its source of truth was changed.
- Chrome at desktop and 375 px, light and dark: no horizontal scroll, install XML renders, WCAG AA contrast on all text/token pairs.
- Every external link returns 200.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
```

Then run (with `$SCRATCHPAD` set to the session scratchpad directory):

```bash
git push -u origin feat/github-pages-landing
gh pr create --base main --title "feat(site): GitHub Pages landing page" --body-file "$SCRATCHPAD/pr-site.md"
```

After creating it, verify no bare mention: every `@` in the PR body must be inside a code span.

Wait for CI to finish, then query Sonar's open issues on the PR before calling it ready.

- [ ] **Step 3: Enable Pages with the GitHub Actions source (ask the user first)**

```bash
gh api repos/tomas-samek/tiko-di/pages -X POST -f build_type=workflow
gh api repos/tomas-samek/tiko-di/pages --jq '"\(.build_type) \(.html_url)"'
```

Expected: `workflow https://tomas-samek.github.io/tiko-di/`

- [ ] **Step 4: After the user merges, watch the deploy**

```bash
gh run list --workflow pages.yml --limit 1
gh run watch <run-id> --exit-status
```

Expected: the run succeeds.

- [ ] **Step 5: Check the live site**

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://tomas-samek.github.io/tiko-di/
curl -s -o /dev/null -w '%{http_code}\n' https://tomas-samek.github.io/tiko-di/style.css
curl -s -o /dev/null -w '%{http_code}\n' https://tomas-samek.github.io/tiko-di/favicon.svg
```

Expected: `200` three times. Open the live URL in Chrome and screenshot the hero in light and dark mode.

- [ ] **Step 6: Set the repo homepage (ask the user first)**

```bash
gh api repos/tomas-samek/tiko-di -X PATCH -f homepage=https://tomas-samek.github.io/tiko-di/ --jq .homepage
```

Expected: `https://tomas-samek.github.io/tiko-di/`
