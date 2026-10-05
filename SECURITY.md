# Security policy

## Supported versions

Tiko is pre-1.0. Security fixes go into the next release of the latest minor line; there are no backports.

| Version | Supported |
|---|---|
| 0.5.x (latest) | ✅ |
| older | ❌ — upgrade to the latest release |

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
- Triage and a fix on a best-effort basis, coordinated with you. Tiko is maintained by one person; we keep you informed rather than promise a date.
- Public disclosure through a GitHub Security Advisory, with a CVE where warranted, once a fixed release is available on Maven Central. Disclosure happens at most 90 days after your report.
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
