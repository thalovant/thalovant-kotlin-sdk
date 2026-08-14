# Releasing the Thalovant Kotlin SDK

The SDK is published to Maven Central as `com.thalovant:thalovant-sdk` through
the Sonatype Central Portal (central.sonatype.com). The Gradle `version`,
`SDK_VERSION`, `CHANGELOG.md`, and the `README.md` install snippet must move
together in a release. `DEFAULT_USER_AGENT` is derived from `SDK_VERSION`
(`ThalovantKotlinSDK/$SDK_VERSION`), so it is never bumped by hand.

## Prerequisites (one-time)

1. **Verified namespace.** `com.thalovant` must be a verified namespace for the
   publishing account in the Central Portal (central.sonatype.com →
   Namespaces). Verification is done via a DNS TXT record on `thalovant.com`.
   The first publish will be rejected until the namespace is verified.
2. **Repository secrets.** The publish workflow requires:

   | Secret | Contents | Where to get it |
   | --- | --- | --- |
   | `MAVEN_CENTRAL_USERNAME` | Central Portal token username | central.sonatype.com → Account → Generate User Token (for the account owning the `com.thalovant` namespace) |
   | `MAVEN_CENTRAL_PASSWORD` | Central Portal token password | Generated together with the username above |
   | `SIGNING_KEY` | ASCII-armored PGP **private** key (`gpg --armor --export-secret-keys <key-id>`) | The org release-signing key; publish the public key to `keyserver.ubuntu.com` / `keys.openpgp.org` so Central can validate signatures |
   | `SIGNING_PASSWORD` | Passphrase of that PGP key | Set when the key was created |

   Signing is applied only when `SIGNING_KEY` is present in the environment, so
   local builds and CI test runs need none of these.

## Publish

1. Update `build.gradle.kts` `version`, `SDK_VERSION` in
   `src/main/kotlin/com/thalovant/sdk/Constants.kt`, `CHANGELOG.md`, the
   `README.md` install snippet, and any affected docs to the same version.
   `DEFAULT_USER_AGENT` follows `SDK_VERSION` automatically.
2. Run `./gradlew build` and `./gradlew publishToMavenLocal`, and inspect the
   staged POM and jars under `~/.m2/repository/com/thalovant/thalovant-sdk/`.
3. Merge to `main`. The **Auto Release** workflow detects that the version has
   no matching `v<version>` tag, re-runs the build, creates the tag and GitHub
   release, and dispatches the **Publish Maven Package** workflow. (If the
   current version is already tagged but release-relevant files changed, it
   auto-bumps a patch version across the files listed above first.)
4. The publish workflow builds and tests the tagged commit, generates a
   CycloneDX SBOM, attests provenance and SBOM, publishes the signed bundle to
   the Central Portal, then polls
   `https://repo1.maven.org/maven2/com/thalovant/thalovant-sdk/` until the
   version appears. Central sync takes 10-30 minutes; a verification timeout
   usually means the sync is slow, not that the publish failed — check
   https://central.sonatype.com/publishing/deployments before re-running.
5. Validate a clean-project dependency resolution of
   `com.thalovant:thalovant-sdk:<version>` and an import smoke test on JVM 17
   before declaring the release complete.

A publish can also be run manually: **Actions → Publish Maven Package → Run
workflow** with the immutable `release_tag` (for example `v0.1.0`).

## Rollback

Published Maven Central artifacts are immutable: they cannot be deleted,
replaced, or re-uploaded once released, and consumers may already depend on
them.

1. Do not attempt to remove or overwrite a broken version.
2. Publish a corrected patch release with aligned version, constants,
   changelog, and README.
3. Update `docs.thalovant.com` and compatibility notes to name the replacement
   version.
