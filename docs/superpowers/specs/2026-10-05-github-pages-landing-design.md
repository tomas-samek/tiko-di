# GitHub Pages landing page

**Status:** approved (design)
**URL:** `https://tomas-samek.github.io/tiko-di/`
**Depends on:** #466 (README Java badge lists 27). `SiteInSyncTest` compares the badge with the CI matrix, so it would fail on a `main` without #466.
**Relates to:** #463 (JDK 27 support), #465 (Spotless on JDK 27), the public-framing rules from milestone #11 (epic #262)

## Goal

A single landing page that lets a **Java developer evaluating Tiko** understand, in a couple of minutes, what Tiko is, see evidence that the claims hold, and know how to install it. AI-agent consumption is not a design driver.

Shape: landing page now, user docs later (option 3). The later docs site must slot in without rewriting the landing page.

## Decisions (from brainstorming)

| Topic | Decision |
|---|---|
| Scope | One static landing page now; `/tiko-di/docs/` reserved for a later docs site |
| Audience | Java developers evaluating Tiko |
| Hosting | GitHub's default address `https://tomas-samek.github.io/tiko-di/`; no custom domain |
| Tooling | Hand-written static HTML + CSS (no generator, no Node/Python toolchain) |
| Evidence on the page | Compile-time error demo, `@Produces` plug-in example, event chaining example, generated code + cold-start numbers |
| Visual direction | "Blueprint" layout (faint engineering grid, wiring-graph diagram, teal accent) with "editorial" typography (serif headings, italic pitch, sans-serif UI) |
| JDK messaging | Follows the JDK support policy below, not a hardcoded "21+" |

## JDK support policy

**Supported JDKs = every LTS from 21 onward, plus every GA release since the latest LTS.** An LTS leaves the set only by a deliberate decision when it's needed and obsolete, never automatically. Non-LTS releases leave the set when the next LTS ships.

| When | Supported set |
|---|---|
| Oct 2026 | 21, 25, 26, 27 |
| JDK 28 GA (Mar 2027) | 21, 25, 26, 27, 28 |
| JDK 29 LTS (Sep 2027) | 21, 25, 29 |

The CI build matrix (`.github/workflows/maven.yml`, `build` job), the README Java badge, and the landing page all show exactly this set. Purpose: keep the badge and messaging concise while still showing support for the latest GA. Moving the Java 21 baseline (`--release`, archetype, "Java 21+" wording) is a separate decision outside this policy.

## Page content (top to bottom)

All copy follows the public-framing rules: lead with what Tiko is, the three buckets, the one-line pitch used verbatim, no banned vocabulary ("gap", "missing", "not yet supported", "limitation", "tiko's equivalent of", or anything that concedes the migration frame). Other frameworks appear only in the cold-start section.

1. **Hero.** Name; definition *"A compile-time orchestrator for Java"*; pitch verbatim: *"Tiko orchestrates, it doesn't bundle — direct access, compile-time safe, nothing wrapped."*; support line *"Every Java LTS from 21, plus every GA since the latest LTS · today 21 · 25 · 26 · 27"*; buttons **Get started** (anchor to Install) and **GitHub**; live Maven Central version badge (shields.io, the only third-party request); the wiring-graph diagram with the unresolved edge in red.
2. **What Tiko is — three buckets** as three cards: *Core* (what Tiko ships), *Plug in* (you bring the library; Tiko orchestrates the seam via `@Produces`), *Open design questions*. Wording taken from the README's "The three buckets" section.
3. **30-second taste.** The README "Quick example" (`UserRepository` → `UserService` → `Tiko.create()`), with the README's "plain Java you can read and step through. No reflection, no classpath scanning in your wiring." line.
4. **Compile-time safe.** `OrderService` injecting an unimplemented `PaymentGateway`, next to the real processor output. Both sources appear as complete files (package, imports, no `…`), because the drift test compiles exactly what the page shows and the `:8` line number refers to that file:
   ```
   OrderService.java:8: error: Cannot resolve dependency: demo.PaymentGateway

     Suggested fixes:
     1. Add a @Component class of type demo.PaymentGateway
     2. Create a @Produces factory method that returns demo.PaymentGateway
     3. Check if the dependency has the correct qualifier (@Named)
   ```
5. **Plug in any library.** A `@Produces` method returning a HikariCP `DataSource`, quoted from `tiko-examples/15_quickstart/.../DataSourceFactory.java`, plus a constructor consuming it.
6. **Events chain.** `@EventHandler` + `@EventTrigger`, where a handler's return value becomes the next event, quoted from `tiko-examples/03_events/.../OrderWorkflow.java`.
7. **Under the hood.** A short excerpt of a real generated factory, labelled as an excerpt; then the README's cold-start table with its methodology caveat ("these numbers move on different hardware"), its "honest reading" paragraph, and a link to `comparisons/README.md`.
8. **Install.** BOM import + `annotationProcessorPaths`, as in the README's Installation section; the support line again; the JDK 23+ annotation-processing note linking `docs/jdk-23-setup.md`.
9. **Footer.** Links: docs on GitHub (`docs/orchestrator-model.md` and friends), examples, archetype, Maven Central, MIT license.

Code samples are pre-highlighted with plain `<span>` elements. They're quoted from code that compiles in the repo (sections 3, 5, 6) or from real output (sections 4, 7).

## Visual design

As approved in the mockups (`visual-style-v2`):

- **Light:** background `#f5f8fb` with a 16 px grid in `#e3eaf1`; text `#13263a`; secondary `#3f5568`; accent teal `#0e7490`; error red `#dc2626`.
- **Dark:** background `#0c1722` with grid `#162636`; text `#e3ecf4`; secondary `#a9bccd`; accent `#22b8cf`; error `#f87171`.
- **Typography:** headings and pitch in a system serif stack (`Georgia, 'Times New Roman', serif`), the pitch italic; UI and body in `system-ui, sans-serif`; code in `ui-monospace, Consolas, monospace`. No web fonts.
- **Sections** alternate a plain background and the faint grid; each has a small uppercase teal label, a serif heading, one explanatory sentence, then code.
- **Colour scheme** follows the visitor's OS (`prefers-color-scheme`), with no toggle and no JavaScript.
- **Responsive:** side-by-side panels (code next to its error) stack below ~720 px; no horizontal page scroll at 375 px; long code lines scroll inside their own block.

## Repository layout

```
site/
  index.html     # the page
  style.css      # tokens on :root, dark-mode overrides, layout
  favicon.svg    # small mark derived from the wiring diagram
```

Constraints: no JavaScript, no web fonts, no analytics or tracking, no third-party requests except the shields.io badge image.

## Deployment

New workflow `.github/workflows/pages.yml`:

- **Triggers:** `push` to `main` with `paths: ['site/**', '.github/workflows/pages.yml']`; `workflow_dispatch`.
- **Permissions:** `contents: read`, `pages: write`, `id-token: write`.
- **Concurrency:** group `pages`, `cancel-in-progress: false`.
- **Steps:** checkout → `actions/configure-pages` → `actions/upload-pages-artifact` with `path: site` → `actions/deploy-pages` (environment `github-pages`).
- No build step.

**Adding docs later:** a future docs generator renders into `docs/` inside the same artifact (served at `/tiko-di/docs/`), so `site/index.html` stays untouched.

**One-time repository setup** (done during rollout, with the user's go-ahead): Pages source set to "GitHub Actions"; the repo's homepage link set to the site URL.

## Keeping the page accurate

These drift tests follow the archetype doc-sync pattern (#408): module tests read repo-root files via `Path.of("..", …)` and fail the build on mismatch. They locate page content through HTML comment markers such as `<!-- sync:compile-error -->` … `<!-- /sync:compile-error -->`, then strip tags and unescape `&lt;` `&gt;` `&amp;`.

1. **`SiteCompileErrorDemoTest`** (`tiko-processor`): compiles the page's `OrderService` / `PaymentGateway` sources (extracted from the page) with `TikoAnnotationProcessor` and asserts that the reported error's message and line number match the page's error block.
2. **`SiteInSyncTest`** (`tiko-archetype`, next to the existing doc-sync tests):
   - the install snippet's `tiko-bom` version equals the README Installation section's version;
   - the JDK list on the page equals the `build` job's `java:` matrix in `.github/workflows/maven.yml`, and equals the versions in the README Java badge.

**Maintained by hand** (not tested): the quoted samples from `15_quickstart`, `03_events` and the README; the generated-code excerpt (labelled as an excerpt); the cold-start table (copied with its caveat).

**Release checklist:** `.ai-skills/tiko-release/SKILL.md` Step 6 ("bump the README + install-doc version pins") gains `site/index.html`. The version drift test backs this up.

## Verification before merge

- `site/index.html` opened in Chrome at desktop width and at 375 px, in light and dark mode: every section renders, and there's no horizontal page scroll.
- Accessibility:
  - WCAG AA contrast for text and accent in both schemes;
  - one `h1`, then section headings in order;
  - the wiring diagram has a text description (`role="img"` + `aria-label`);
  - buttons are real links.
- Every `href` on the page returns 200; in-page anchors resolve.
- `mvn -B package` passes on JDK 21 with the new drift tests; CI covers the other JDKs.

## Rollout

1. One PR from `feat/github-pages-landing`: `site/`, `pages.yml`, both drift tests, a "Website" link near the top of the README, and the release-checklist addition.
2. Before merging: switch Pages to "GitHub Actions" (repo setting, via the GitHub API, after asking the user). Otherwise the first deploy fails.
3. After merge: the workflow deploys; the live URL is checked (loads, links resolve, both colour schemes); the repo homepage link is set.

## Out of scope

- The docs site (path reserved; later work).
- A custom domain.
- Analytics or tracking.
- Search, a blog, or a changelog page.
- The AI-benchmark compliance claim.
- Changing the Java 21 baseline.
