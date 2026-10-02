# Thalovant Kotlin SDK

[![CI](https://github.com/thalovant/thalovant-kotlin-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/thalovant/thalovant-kotlin-sdk/actions/workflows/ci.yml)
[![Licence](https://img.shields.io/github/license/thalovant/thalovant-kotlin-sdk)](LICENSE)
[![Docs](https://img.shields.io/badge/docs-docs.thalovant.com-5c6bc0)](https://docs.thalovant.com/developers/sdks/kotlin/)

Kotlin SDK for connecting JVM and Android apps, services, and agents to
Thalovant hubs.

The control API is used to discover hubs and provision a client identity. After
that, the SDK talks directly to the hub data plane over WSS.

Full documentation: <https://docs.thalovant.com/developers/sdks/kotlin/>

## Requirements

- JVM 17 or newer (or Android with `minSdk` supporting Java 17 bytecode via
  desugaring/AGP defaults).
- A Thalovant account with API access for authenticated control-plane actions.
- A hub id or slug, and a client identity for that hub. You can create one
  through the API or use one downloaded from the dashboard.

## Install

```kotlin
dependencies {
    implementation("com.thalovant:thalovant-sdk:0.8.2")
}
```

## Quick start

```kotlin
import com.thalovant.sdk.CreateClientIdentityOptions
import com.thalovant.sdk.ThalovantClient
import com.thalovant.sdk.ThalovantControlPlane
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val api = ThalovantControlPlane()
    api.login("you@example.com", "password")

    val result = api.createClientIdentity(
        "hub-id",
        CreateClientIdentityOptions(name = "kotlin-demo-client"),
    )

    val client = ThalovantClient(result.identity)
    try {
        val reply = client.ask("Tell me a short clean joke.")
        println(reply.text)
    } finally {
        client.close()
    }
}
```

Keep `result.identity` secret, and do not log `result.asJson(includeSecrets = true)`.

## Documentation

Everything else lives in the documentation.

| Topic | Where |
| :--- | :--- |
| Install, first request, MFA and browser sign-in, API tokens | [Kotlin SDK](https://docs.thalovant.com/developers/sdks/kotlin/) |
| Saved identities, key storage, request and query IDs | [Kotlin SDK](https://docs.thalovant.com/developers/sdks/kotlin/) |
| Provisioning hubs, skills, sessions, events, deadlines | [Kotlin SDK](https://docs.thalovant.com/developers/sdks/kotlin/) |
| Errors, common issues, runtime protocol | [Kotlin SDK](https://docs.thalovant.com/developers/sdks/kotlin/) |
| All Thalovant documentation | [docs.thalovant.com](https://docs.thalovant.com) |

## Durable memory

The control plane also exposes explicit opt-in memory for private Daily Desk
and workspace assistants through `createMemoryItem` and `listMemoryItems`
(with `MemoryCreatePayload`, `MemoryListOptions`, `MemoryScope`, and
`MemoryKind`). The Kotlin SDK documentation page does not cover it yet.

## Development

```bash
./gradlew build
```

## Security

See [SECURITY.md](https://github.com/thalovant/.github/blob/main/SECURITY.md)
for how to report a vulnerability.

## Licence

MIT. See [LICENSE](LICENSE).
