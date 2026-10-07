# Security policy

## Supported versions

Tiko is pre-1.0. Security fixes go into the next release of the latest minor line; there are no backports.

| Version | Supported |
|---|---|
| Latest minor release ([Maven Central](https://central.sonatype.com/artifact/io.github.tomas-samek/tiko-bom)) | ✅ |
| Older minor lines | ❌ — upgrade to the latest release |

## Reporting a vulnerability

Please report vulnerabilities **privately** through GitHub:
[Report a vulnerability](https://github.com/tomas-samek/tiko-di/security/advisories/new)
(the Security tab → "Report a vulnerability"). Don't open a public issue, PR or discussion.

Include:

- the affected module(s) and version(s), e.g. `tiko-kafka 0.5.0`;
- a minimal reproduction (code, configuration, or input);
- the impact as you understand it.

## What to expect

- **Acknowledgement within 7 days.**
- Triage and a fix on a best-effort basis, coordinated with you. Tiko is maintained by one person, so we keep you informed as the fix progresses.
- Public disclosure through a GitHub Security Advisory, with a CVE where warranted, once a fixed release is available on Maven Central. We aim for that within 90 days of your report; if a fix needs longer, we agree the disclosure date with you.
- Credit in the advisory and release notes, unless you'd rather not be named.

## Scope

**In scope**

- The published `io.github.tomas-samek:tiko-*` artifacts.
- Code that `tiko-processor` and `tiko-kafka-processor` generate into your build.
- Projects created by `tiko-archetype`.

**Out of scope**

- `tiko-examples/` and `comparisons/` — demonstration code, not shipped.
- The security of applications built on Tiko, including the libraries you plug in (HTTP servers, auth, databases). Report those to their maintainers.

## Bundled dependencies

`tiko-kafka` shades Jackson (relocated under `io.tiko.kafka.internal.jackson`). A vulnerability there is fixed by a new Tiko release; upgrading Jackson in your own build doesn't change the shaded copy.

`tiko-mcp` is distributed as a self-contained jar that bundles its runtime dependencies (the MCP SDK, Reactor, a JSON-schema validator, Jackson, SnakeYAML). The same applies: a fix arrives with a new Tiko release.

GitHub's automated dependency alerts cover both. `tiko-kafka` declares every Jackson module it bundles, and on every change to `main` the repository submits the fully resolved dependency tree of each module, bundled and transitive dependencies included, to GitHub's dependency graph.
