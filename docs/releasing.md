# Releasing Tiko to Maven Central

Tiko publishes to the **Sonatype Central Portal** (the OSSRH successor) via the
`central-publishing-maven-plugin`. Everything release-specific lives behind the
`release` Maven profile, so day-to-day `mvn install` is unaffected. The publish is
**manual**: a maintainer triggers a workflow, and artifacts land in Central Portal
*staging* for inspection before they go public.

## Namespace

Artifacts publish under Maven groupId **`io.github.tomas-samek`** — a GitHub-verified
namespace on Central Portal (no DNS / domain required; verification ties the namespace
to the `tomas-samek` GitHub account). Java packages remain `io.tiko.*` and are
independent of the Maven coordinate.

## What gets published

The published surface is the parent POM plus everything `tiko-bom` exposes:

| Published                                                                 | Not published (`skipPublishing=true`)                          |
| ------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `tiko-parent`, `tiko-bom`, `tiko-api`, `tiko-runtime`, `tiko-processor`, `tiko-config`, `tiko-mcp`, `tiko-test`, `tiko-archetype`, `tiko-kafka`, `tiko-kafka-processor` | `tiko-kafka-it`, `tiko-examples` (+ children), `tiko-coverage` |

Exclusion is per module: each non-published module sets `<skipPublishing>true</skipPublishing>`
in its `<properties>` (the examples' 13 child modules inherit it from `tiko-examples`).

**When moving a module from "Not published" to "Published"** (dropping its
`skipPublishing=true`), add the matching `<dependency>` entry under
`tiko-bom/pom.xml`'s `<dependencyManagement>` in the same PR. Otherwise
consumers who import `tiko-bom` will have to specify a `<version>` on the
newly-published artifact, which defeats the BOM's purpose. The exception is
`tiko-archetype`: archetypes resolve via `-DarchetypeVersion=…`, not as
`<dependency>` blocks, and need no BOM entry.

## One-time setup

1. **Central Portal namespace.** Sign in at <https://central.sonatype.com>, click
   *Namespaces* → *Add Namespace*, enter `io.github.tomas-samek`. Central recognises
   the GitHub-prefix shape and runs OAuth-based verification (no DNS step); confirm
   the GitHub account match and the namespace flips to *Verified*.
2. **GPG key.** Generate a key, publish the **public** half to a keyserver Central
   trusts, and keep the private half for CI:
   ```bash
   gpg --full-generate-key
   gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID>   # value for the GPG_PRIVATE_KEY secret
   ```
3. **Central Portal token.** At <https://central.sonatype.com> → *Account* →
   *Generate User Token*. This yields a username/password pair (both are opaque
   random strings, not your portal login).
4. **Push-capable token.** Generate a Personal Access Token (or fine-grained token)
   owned by an account that can push to protected `main`. The workflow uses this
   instead of the default `GITHUB_TOKEN` for the release commit, the tag, and the
   next-snapshot commit (the default token can't bypass branch protection):
   - GitHub → *Settings* → *Developer settings* → *Personal access tokens* → generate a
     classic token with `repo` scope (or a fine-grained token scoped to this repo with
     `Contents: read+write` and `Metadata: read`).
   - Verify the owning account has bypass on `main` (org owners and repo admins do by
     default; otherwise add an explicit bypass rule in branch protection settings).
5. **Repository secrets** (Settings → Secrets and variables → Actions):

   | Secret               | Value                                                  |
   | -------------------- | ------------------------------------------------------ |
   | `CENTRAL_USERNAME`   | Central Portal token username                          |
   | `CENTRAL_TOKEN`      | Central Portal token password                          |
   | `GPG_PRIVATE_KEY`    | ASCII-armored secret key (from step 2)                 |
   | `GPG_PASSPHRASE`     | passphrase for that key                                |
   | `RELEASE_PUSH_TOKEN` | PAT from step 4 (push access to `main`)                |

## Cutting a release

`main` is always on a `*-SNAPSHOT` version between releases. The workflow handles the
release → next-snapshot dance — you only supply two version strings.

1. Make sure `main` is green.
2. Run the **"Release to Maven Central"** workflow (Actions → select the workflow →
   *Run workflow*). It takes two required inputs:

   | Input              | Example          | Purpose                                          |
   | ------------------ | ---------------- | ------------------------------------------------ |
   | `release_version`  | `0.2.0`          | Strips `-SNAPSHOT`, commits, tags `v0.2.0`.      |
   | `next_snapshot`    | `0.3.0-SNAPSHOT` | Post-deploy bump committed back to `main`.       |

   The workflow runs `mvn versions:set` for both versions (and patches the two inline
   `<tiko.version>` properties in `tiko-bom/pom.xml` and the archetype-resources
   template that `versions:set` doesn't reach), commits as `github-actions[bot]`,
   tags the release commit, and pushes both commits + the tag using
   `RELEASE_PUSH_TOKEN`. Between the two commits it runs `mvn -B -Prelease deploy`,
   which builds with `-Prelease` (attaches `-sources`/`-javadoc`, GPG-signs every
   artifact) and uploads the bundle to Central Portal **staging**.
3. Open <https://central.sonatype.com> → *Deployments*. Verify the staged deployment
   contains exactly the eleven artifacts listed above — and **nothing else** — then
   click **Publish**. (`autoPublish=false` is deliberate: this is the human gate.)
4. Artifacts appear on Central a short while after publishing. The GitHub Actions
   workflow run finished some seconds after the staging upload; it does not block on
   your Publish click.
5. Create a GitHub Release at the tag the workflow pushed (`v<release_version>`).
   `RELEASE-NOTES.md`'s `[Unreleased]` section is the canonical source for the body.

If `deploy` fails, the workflow aborts before pushing the release commit or tag, so
`main` is unchanged. Recovery is to fix the failure (e.g. transient SonarCloud API
hiccup, see PR #251 for prior art) and re-trigger the workflow. If `deploy` succeeds
but a later push fails, `main` may be missing the release/next-snapshot commits — check
the run log; the local refs from the runner are gone, but you can recreate manually
with `mvn versions:set` + a small PR.

## Verifying the build locally (no upload)

`-Prelease` artifacts and signatures can be produced locally if you have a GPG key:

```bash
# sources + javadoc jars, no signing, no upload
mvn -Prelease package -DskipTests -Dgpg.skip=true

# add real signatures (.asc for every artifact); still no upload
mvn -Prelease verify -DskipTests
```

The actual upload only happens on `deploy` with the `central` server credentials in
`settings.xml`, which is why it is confined to the CI release job.
