# Issue Fix Playbook

Counterpart to [qa-playbook.md](./qa-playbook.md). The QA playbook tells you how to surface bugs and write them up; this one tells you how to work them once they're filed.

## Purpose

`mvn test` answers "do the existing tests pass?" and the QA playbook answers "what gaps need filing?" — neither tells you how to *fix* a filed bug without becoming part of the problem. The default failure modes when fixing issues are:

- **Anchoring on the reporter's hypothesis.** They saw the first error and stopped digging. You inherit their model.
- **Satisfying the literal acceptance** with a superficial fix that doesn't satisfy the *intent*. The acceptance is a description of the user-visible outcome, not a checklist to game.
- **Scope creep "while I'm here."** Adjacent bugs you noticed during investigation belong in separate issues so bisect history stays clean.
- **Not adding a regression test.** Fixes without tests rot; the next recurrence costs full re-investigation.

The discipline below is what survived the 2026-05-24 #149 fix — see "Case study" at the bottom for the concrete walkthrough.

## When to run this discipline

Every time you pick up a `bug` or `enhancement` issue that names a specific defect or missing behaviour. Pure-doc and pure-refactor issues are usually mechanical and skip Phase 1; everything else gets all four phases.

## The four phases

### Phase 1 — Reproduce, cold

Before reading any speculative content in the issue body (well-written bodies won't have any per QA playbook policy, but legacy issues might):

- [ ] Reproduce the failure on your machine, or in CI for environment-specific bugs. If you can't reproduce, you can't fix — file a "needs more info" comment, don't guess.
- [ ] Capture the actual error verbatim. The text matters; "doesn't work" is not evidence.
- [ ] Read the relevant code paths fresh. Anything the issue body claims about file paths is evidence-shaped but still hypothesis-shaped — treat as data, not gospel. Verify by reading the code.
- [ ] If reproducing requires forked-subprocess execution (Kafka ITs, MCP server, custom-logger example), make sure diagnostic plumbing is in place — drain subprocess stdout, capture stderr, surface what the subprocess actually printed. Silent subprocess failures are the most common cause of "the test just times out" misdiagnosis.

### Phase 2 — Form your own hypothesis

- [ ] Use the actual error message, stack trace, or behaviour delta as the seed.
- [ ] Cross-check against the documented contract — CLAUDE.md, the relevant README, the code's javadoc. The issue's claim about "what should happen" may itself be wrong; the contract may say something different from what either of you thought.
- [ ] If the issue body proposes a cause, list it as one hypothesis among others rather than the working hypothesis. Multi-hypothesis posture is cheap insurance.
- [ ] Ask: is the issue body's framing the *minimum scope* I need to fix to resolve the user's actual problem, or is it a narrow slice that papers over something deeper? Silently-skipped tests, in particular, are unindexed debt — fixing the skip surfaces accumulated rot.

### Phase 3 — Fix the root cause, not the symptom

- [ ] Narrowest possible scope. Smaller fixes are easier to verify, easier to revert, easier to bisect.
- [ ] The acceptance is the user-visible outcome, not the mechanism. If your fix achieves the outcome by a different mechanism than the reporter imagined, that's fine — note the deviation in the PR description.
- [ ] If your fix uncovers a deeper bug, file the deeper bug as a separate issue rather than scope-creeping the current one. Linking the two in commit messages or PR bodies keeps the trail intact.
- [ ] If the bug is in a shared API (config layer, processor, runtime container), consider whether the fix is *targeted* (one user-reported symptom) or *general* (one class of bugs). The general fix is usually right when both are similar size; when the general fix is much larger, ship the targeted one and file the general one.

### Phase 4 — Verify

- [ ] Reproduce the failure one more time to confirm it stops failing.
- [ ] Add a regression test that pins the contract. Mirror the existing test style for the module: unit test (`*Test`) for compile-time / pure-function behaviour, integration test (`*IT`) for subprocess / external-resource cases. Per CLAUDE.md, `*IT` needs failsafe wiring (root pom handles it project-wide post-#193).
- [ ] Run the broader test suite (`mvn verify` if your fix touches anything beyond a single module). Catch unintended consequences before CI does.
- [ ] On a multi-iteration fix, each CI cycle should pinpoint progress: name `await().alias(...)` phases, log subprocess stdout, prefer named exceptions over generic ones. The faster the next iteration knows which hypothesis to attack, the cheaper the loop.

## Common traps

- **"It worked on my machine."** CI cold-start environments are slower than dev laptops, especially for forked-JVM + Testcontainers workflows. Generous timeouts (60s+) for ready-signals and resource availability are the right default.
- **`inheritIO()` on subprocess tests.** Hides everything the subprocess printed; you lose the diagnostic that would have told you the real cause. Always drain subprocess streams on a daemon thread so they surface in test output.
- **Trusting the issue body's "Files" list as a fix prescription.** It's a list of files relevant to the symptom — entry points to start reading. The fix may touch entirely different files (or one module deeper than the symptom).
- **Fixing the symptom in the example instead of the framework.** If the user's natural usage triggers a confusing error, the bug is likely in the framework's contract, not the user's config. Resist the urge to massage the user-facing artefact to placate the validator.
- **Iterating CI cycles without diagnostic improvements.** Each red CI run should leave the test better instrumented, even if the fix didn't land. The cycle compounds if every failure gives you more visibility into the next attempt.

## Workflow

The skill discipline behind this playbook lives in `superpowers:systematic-debugging` — invoke it when starting any debugging task. The phases above mirror its Phase 1 (Root Cause Investigation) → Phase 2 (Pattern Analysis) → Phase 3 (Hypothesis & Testing) → Phase 4 (Implementation), with the additions tuned for "you're picking up an issue someone else wrote."

When the work is structurally larger than a single bug (multi-step fix touching several modules), upgrade to `superpowers:writing-plans` + `superpowers:subagent-driven-development`. The fix workflow remains the same; you're just delegating Phase 3 to subagents and reviewing between steps.

## Case study — 2026-05-24 #149

The canonical demonstration of why this discipline matters. The issue body framed the problem as a `maven-failsafe-plugin` wiring gap with a timing-flavoured acceptance ("IT runs end-to-end and passes within 30s"). The actual fix chain was three iterations deep:

1. **Surface (issue's framing):** failsafe not wired → IT silently never runs. Resolved separately by #193 wiring failsafe project-wide. After that, the IT ran but timed out at 30s.
2. **First-cycle hypothesis:** the 2-second sleep waiting for Kafka consumer-group join was too short for cold-start CI. Bumped timeouts, added ready-signal awaits. CI still red — but now the `await().alias(...)` named the failing phase: "warehouse-service ready line on stdout."
3. **Second cycle (decisive):** the diagnostic plumbing surfaced what was actually happening — warehouse-service was crashing on startup with `ConfigValidationException: unknown top-level section 'tiko'`. The user's natural nested YAML form (`tiko: kafka:`) didn't match Tiko's literal-key prefix matching. Fixed in `tiko-config` to traverse dotted prefixes as nested paths. CI still red.
4. **Third cycle:** with the prefix-matching fix, the validator passed but the binder reported `producer-properties` and `consumer-properties` missing. `tiko-kafka`'s baked-in `defaults.yaml` used flat-dotted form (`"tiko.kafka":`) while the user's app used nested — the layered deep-merge treated them as different keys. Flipped `defaults.yaml` to nested. CI green.

Lessons:

- The issue body framed a symptom (silent skip). The actual scope was three bugs in three modules. None of which would have been visible without first wiring failsafe (the original ask) and adding diagnostic plumbing (Phase 1 discipline).
- Following the body's implicit theory ("just fix the timing") cost one CI cycle. Dropping that theory after evidence arrived was what unblocked the rest.
- Each CI cycle made the next one cheaper because the test got more instrumented (drain stdout → name phases via alias → ASCII-stable output). The Awaitility `alias` calls were what pinpointed phase 3's actual failure.
- The targeted fix (flip `defaults.yaml`) won over the general fix (canonicalise all dotted-string keys to nested at load time). General fix is a separately file-able enhancement; targeted fix shipped the user value.

When you find yourself iterating a fix and each cycle reveals something the issue body didn't mention, you're not failing — you're paying down debt the body didn't know existed.

## Out of scope

Bug investigation that doesn't have an issue filed yet. Use `superpowers:systematic-debugging` directly, or file the bug through the QA playbook first then work it through this one. Pure feature work (no underlying defect, just "add this") follows `superpowers:brainstorming` + `superpowers:writing-plans` instead.
