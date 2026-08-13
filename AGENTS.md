# Repository instructions

This repository owns the published Kotlin (JVM and Android) client and agent SDK for supported Thalovant public API and HiveMind runtime contracts. Read the platform contracts in `../infra-manifests/docs/thalovant-platform/` when available.

Rules:

- Preserve compatibility with the documented JVM 17 bytecode target and the Thalovant API support window.
- Update types, implementation, examples, tests, changelog, version, and public documentation together for observable contract changes. The Gradle `version`, `SDK_VERSION`, `DEFAULT_USER_AGENT` (`ThalovantKotlinSDK/<version>`), `CHANGELOG.md`, and `README.md` install snippet must move together in a release.
- Consume additive server behavior only after compatible server support exists.
- Never publish credentials, Maven tokens, identity files, or generated secrets.
- Do not create a release for internal platform changes with no Kotlin SDK impact; record `no SDK impact` in the coordinated change instead.
- Validate package contents and a clean-project resolution of `com.thalovant:thalovant-sdk:<version>` before declaring a release complete.
- Update affected `docs.thalovant.com` SDK pages in the same release train.

Validate with `./gradlew build` (compiles, runs the mock-server unit tests, and assembles the jar). A published release also requires a clean-project dependency resolution and an import smoke test on JVM 17.

Rollback by publishing a corrected patch release; published Maven artifacts are immutable and must not be deleted once consumers may depend on them.
