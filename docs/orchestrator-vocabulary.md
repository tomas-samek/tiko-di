# Orchestrator Vocabulary

Canonical language for tiko-di's public framing. Every doc that describes what
tiko is, what it ships, or what it deliberately doesn't ship — README, the
`tiko-build` skill, agent-config files, release notes, cookbook recipes — pulls
its vocabulary from this file. When a phrasing question comes up, this file
decides.

## One-line pitch

> Tiko orchestrates, it doesn't bundle — direct access, compile-time safe,
> nothing wrapped.

Repeat verbatim. No paraphrasing, no `[draft]` markers, no per-doc rewordings.

## The three buckets

Every concern in a tiko-built application falls into exactly one of three
buckets. Public-facing docs are organised around this axis.

- **Core** — what tiko itself ships and owns: the container, scopes
  (`SINGLETON` / `EVENT` / `PROTOTYPE`), compile-time wiring, the event bus,
  `@Configuration`, lifecycle hooks. The framework's surface area.

- **Plug in** — concerns tiko doesn't ship but expects you to bring in
  directly: HTTP servers, persistence, message brokers, schedulers, security.
  The orchestration seam is `@Produces` — you supply the library, tiko wires
  it. Tiko's value here is the seam, not the library.

- **Open design questions** — concerns where tiko hasn't yet picked a
  position because the right answer depends on context that hasn't been
  observed in real codebases yet. Different from "plug in" — for these, even
  the orchestration shape isn't settled. Honest scope note, not a deferral
  promise.

## Banned vocabulary

Each banned token concedes the migration frame — it implicitly compares tiko
to a feature-bundled framework on that framework's axis. Use the positive
replacement instead.

| Banned | Use instead |
|---|---|
| "gap" | "plug in" |
| "missing" | "you provide" — or, for compile-time concerns, what tiko *catches* |
| "not yet supported" | "direct access" / "we deliberately don't wrap" |
| "limitation" | "we deliberately don't wrap" for design choices; honest scope note in release notes for genuine v1 caveats |
| "tiko's equivalent of …" | Name the tiko-native primitive directly — no comparison axis |

Legitimate technical uses of "missing" (compile-time error messages) and
"limitation" (release-notes engineering disclosure) are unaffected — the ban
is on framing prose, not on accurate engineering language inside errors and
changelogs.

## Spirit clause

The rule bans **any phrasing that concedes the migration frame**, not only
the five tokens above. If a future doc invents a new phrasing that puts tiko
on someone else's axis — "tiko's take on X", "tiko-flavoured Y", "the X story
in tiko" — that phrasing falls under this rule too, even though it uses words
that aren't in the table. Review the framing, not just the wordlist.
