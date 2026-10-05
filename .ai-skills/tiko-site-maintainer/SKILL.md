---
name: tiko-site-maintainer
description: Use when changing the GitHub Pages landing page in site/ (copy, code samples, new sections, styling), when a new JDK goes GA or an LTS ships, after a tiko release reaches Maven Central, or when https://tomas-samek.github.io/tiko-di/ doesn't show a merged change.
---

# tiko-site-maintainer

The landing page (`site/` → `https://tomas-samek.github.io/tiko-di/`) is Tiko's public face for Java developers evaluating it. Two things keep it honest:

- **Drift tests** fail the build when the page's checkable claims go stale.
- **The framing rules** govern everything the tests can't check.

Design: `docs/superpowers/specs/2026-10-05-github-pages-landing-design.md`.

## Map

| What | Where |
|---|---|
| Page, styles, icon | `site/index.html`, `site/style.css`, `site/favicon.svg` (no JS, no web fonts, no tracking) |
| Deploy | `.github/workflows/pages.yml`, which runs on pushes to `main` that touch `site/**` or itself, plus `workflow_dispatch` |
| Compile-error demo guard | `tiko-processor/.../SiteCompileErrorDemoTest` compiles the page's `OrderService` / `PaymentGateway` and compares the shown error |
| Version / JDK / URL guard | `tiko-archetype/.../SiteInSyncTest`: BOM version = README, JDK list = CI `build` matrix = README badge, no root-relative URLs |
| Markers the tests read | `<!-- sync:order-service -->`, `sync:payment-gateway`, `sync:compile-error`, `sync:bom-version`, `sync:jdks` (×2, hero and install). Never delete or rename one. |

## 1. Editing page content

### The framing rule (hard)

The page leads with what Tiko **is**. Other frameworks are named **only** in the cold-start table section. Nowhere else does the page use any of these:
- "Coming from Spring", "if you know X", "X becomes Y" mappings, side-by-side API tables;
- "Tiko's equivalent of…";
- the banned words, or anything else that puts Tiko on another framework's axis.

Authority: `docs/orchestrator-vocabulary.md` (banned vocabulary and spirit clause), the README's three buckets, and the pitch used verbatim:

> Tiko orchestrates, it doesn't bundle — direct access, compile-time safe, nothing wrapped.

**When a request asks for a comparison** ("make it relatable to Spring devs"):
1. Write the compliant version: what the Tiko primitive *is*, and what it *catches* at compile time.
2. Tell the maintainer you left the comparison out because of the framing rule. Do this before you start, or in the PR body if you can't ask mid-task.
3. Add a comparison only if they explicitly override the rule for this change.

| Rationalization | Reality |
|---|---|
| "The maintainer asked for the comparison" | The framing rule is the maintainer's standing decision (milestone #11). Surface the conflict, don't resolve it silently. |
| "It's only one note at the bottom" | Readers absorb the comparison axis, not the disclaimers around it. One mapping table re-frames the whole section. |
| "Spring devs need a bridge" | The reframe exists because agents and readers took Tiko for "Spring minus features". |
| "The cold-start table names Spring, so it's allowed" | That section is the single, measured exception. It doesn't extend to feature copy. |

**Red flags — stop and rewrite:** "Coming from…", "becomes", "instead of X you…", "the X way", a two-column old/new table, "Spring's" anywhere outside `#under-the-hood`.

### Truth rules

- **Code is quoted, never invented.** Copy from a file that compiles in the repo, and name it in the lede (`From tiko-examples/…`). Trimming is fine if the text says so ("logging trimmed"). A class or method that isn't in the source doesn't go on the page.
- **Every claim is checked in the source first.** That covers config keys (`tiko-kafka`'s `defaults.yaml`), behaviour, and module names.

### Markup rules

- **Escape** `<`, `>` and `&` inside `<pre>` (generics, XML, `->`).
- **Relative same-site URLs only** (`style.css`, `#install`): the site is served under `/tiko-di/`.
- **Reuse existing classes:**
  - token colours `.a` (annotation), `.k` (keyword), `.s` (string), `.c` (comment);
  - section parts `.label`, `.lede`, `.note`;
  - layout `.panels` / `.panel`, `.cards` / `.card`.
- **Keep sections alternating** `plain-bg` / `grid-bg`.
- **New colours** need a light and a dark token in `:root` and in the dark-mode block, and must reach ≥ 4.5:1 contrast on their backgrounds.

## 2. A JDK goes GA

**Policy:** supported = every LTS from 21 onward, plus every GA since the latest LTS.

- A new **non-LTS** release is appended.
- A new **LTS** replaces the non-LTS releases: at 29 the list becomes 21, 25, 29.

Retiring an LTS or moving the Java 21 baseline is a separate decision. Ask; never do it as part of a JDK bump.

Update **every** place together:

| Place | Checked by test? |
|---|---|
| `.github/workflows/maven.yml`, `build` job `java: [...]` | yes (source of truth) |
| `site/index.html`, both `sync:jdks` markers | yes |
| `README.md` Java badge (`Java-21%20%7C%20…`) | yes |
| `README.md` "CI builds on JDK …" sentence | **no** |
| `tiko-bom/pom.xml` `<description>` "CI-tested on JDK …" | **no** |
| `pom.xml` `jdk27-skip-spotless` profile comment, and #465's status | **no** |

The **integration-test matrix** isn't governed by the policy. Ask before changing it.

Check first:
- Temurin publishes the new JDK (`api.adoptium.net/v3/info/available_releases`).
- palantir-java-format runs on it. If it doesn't, Spotless stays skipped via the `[27,)` profile. If it does, close #465 instead.

## 3. After a release

Follow `tiko-release` Step 6: it bumps `site/index.html`'s `sync:bom-version` with the README. Then run section 5's deploy check.

## 4. Verify before the PR

```bash
mvn -B test -pl tiko-processor,tiko-archetype -am -Dtest=SiteCompileErrorDemoTest,SiteInSyncTest -Dsurefire.failIfNoSpecifiedTests=false > "$SCRATCH/site.log" 2>&1; echo "exit=$?"
bash .ai-skills/tiko-site-maintainer/scripts/check-site.sh     # 375 px, light + dark; exits non-zero on failure
grep -oE 'href="https?://[^"]+"' site/index.html | sed -E 's/^href="|"$//g' | sort -u \
  | while read -r u; do echo "$(curl -s -o /dev/null -w '%{http_code}' -L "$u") $u"; done   # every link 200
```

`check-site.sh` reports, for each colour scheme:
- `pageOverflow` must be false (no sideways scroll at 375 px);
- the install XML renders literally;
- exactly one `h1`;
- 16 px side gutters;
- `color-scheme: light dark`.

Look at the screenshots it saves, too. If you added colours, compute their contrast. Run Spotless on JDK ≤ 26: it's skipped on 27+.

## 5. Deploy and troubleshooting

1. `gh run list --workflow pages.yml --limit 3`. The run's `headSha` must be the merge commit.
   - **No run:** the push didn't touch `site/**`; use `gh workflow run pages.yml`.
   - **Failed:** read `gh run view <id> --log-failed`.
2. `gh api repos/tomas-samek/tiko-di/pages --jq .build_type` must print `workflow`. `Get Pages site failed` / 404 means Pages is off or on branch mode. Changing it is a repo setting: ask first.
3. `curl -s https://tomas-samek.github.io/tiko-di/style.css | diff - site/style.css`. An empty diff means the server is current and the stale view is the browser or CDN cache (`max-age=600`), which clears on its own. Don't add cache-busting query strings to the page for this.
4. A deploy rejected for the branch: the `github-pages` environment's branch policy must allow `main`.

## Common mistakes

| Mistake | Effect |
|---|---|
| Feature section framed as a Spring mapping | Violates the framing rule; see §1 |
| Invented handler or class "to show the flow" | The page shows code that exists nowhere |
| JDK list changed only where the test looks | README sentence and BOM description go stale |
| `href="/…"` | 404 under `/tiko-di/` |
| Raw `<` in a code block | Text vanishes from the rendered page |
| Removing a `sync:` marker while editing | Drift test fails with "found 0" |
