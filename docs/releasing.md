# Releasing Tiko to Maven Central

Tiko publishes to the **Sonatype Central Portal** (the OSSRH successor) via the
`central-publishing-maven-plugin`. Everything release-specific lives behind the
`release` Maven profile, so day-to-day `mvn install` is unaffected. The publish is
**manual**: a maintainer triggers a workflow, and artifacts land in Central Portal
*staging* for inspection before they go public.

## What gets published

The published surface is the parent POM plus everything `tiko-bom` exposes:

| Published                                                                 | Not published (`skipPublishing=true`)                          |
| ------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `tiko-parent`, `tiko-bom`, `tiko-api`, `tiko-runtime`, `tiko-processor`, `tiko-config`, `tiko-mcp`, `tiko-test` | `tiko-kafka`, `tiko-kafka-processor`, `tiko-kafka-it`, `tiko-examples` (+ children), `tiko-archetype`, `tiko-coverage` |

Exclusion is per module: each non-published module sets `<skipPublishing>true</skipPublishing>`
in its `<properties>` (the examples' 13 child modules inherit it from `tiko-examples`).

## One-time setup

1. **GPG key.** Generate a key, publish the **public** half to a keyserver Central
   trusts, and keep the private half for CI:
   ```bash
   gpg --full-generate-key
   gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
   gpg --armor --export-secret-keys <KEY_ID>   # value for the GPG_PRIVATE_KEY secret
   ```
2. **Central Portal token.** At <https://central.sonatype.com> → *Account* →
   *Generate User Token*. This yields a username/password pair.
3. **Repository secrets** (Settings → Secrets and variables → Actions):

   | Secret             | Value                                            |
   | ------------------ | ------------------------------------------------ |
   | `CENTRAL_USERNAME` | Central Portal token username                    |
   | `CENTRAL_TOKEN`    | Central Portal token password                    |
   | `GPG_PRIVATE_KEY`  | ASCII-armored secret key (from step 1)           |
   | `GPG_PASSPHRASE`   | passphrase for that key                          |

## Cutting a release

1. Make sure `main` is green and the version in the POMs is the one you intend to ship
   (a non-`SNAPSHOT` version).
2. Run the **“Release to Maven Central”** workflow (Actions → select the workflow →
   *Run workflow*). It builds with `-Prelease`, attaches `-sources`/`-javadoc`,
   GPG-signs every artifact, and uploads the bundle to Central Portal **staging**.
3. Open <https://central.sonatype.com> → *Deployments*. Verify the staged deployment
   contains exactly the eight artifacts above — and **nothing else** — then click
   **Publish**. (`autoPublish=false` is deliberate: this is the human gate.)
4. Artifacts appear on Central a short while after publishing.

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
