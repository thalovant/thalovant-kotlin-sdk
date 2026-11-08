<!-- Last Edit: Claude Opus 5.5 - 2026-11-08 - Motive: Sign-in section for browser sign-in (0.10.0), final reference 39e31d8: what a loopback sign-in promises, an empty device name, the example in a coroutine. -->

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/thalovant/.github/main/profile/assets/thalovant-logo-dark.png">
    <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/thalovant/.github/main/profile/assets/thalovant-logo-light.png">
    <img src="https://raw.githubusercontent.com/thalovant/.github/main/profile/assets/thalovant-logo-light.png" alt="Thalovant" width="320">
  </picture>
</p>

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
    implementation("com.thalovant:thalovant-sdk:0.10.0")
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
    // Opens the browser on this machine, or prints a code to approve from any
    // browser where there is none.
    api.interactiveLogin()

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

Keep `result.identity` secret, and treat the raw `result.hub` and
`result.client` API resources the same way: they carry the bootstrap
credentials. The default `result.asJson()` redacts all of them and is safe to
log. `result.asJson(includeSecrets = true)` returns the real secrets, so never
log or persist it.

## Sign in

```kotlin
import com.thalovant.sdk.InteractiveLoginOptions
import com.thalovant.sdk.LoginMethod
import com.thalovant.sdk.ThalovantControlPlane
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val api = ThalovantControlPlane()
    // AUTO: the browser here, or a device code where there is none.
    // --browser or --device forces one.
    val method = when {
        "--browser" in args -> LoginMethod.BROWSER
        "--device" in args -> LoginMethod.DEVICE
        else -> LoginMethod.AUTO
    }
    api.interactiveLogin(InteractiveLoginOptions(method = method))
    println("Signed in; token id ${api.tokenId}")
}
```

`interactiveLogin()` opens your browser at the Thalovant consent page and
listens on `127.0.0.1` for the answer (OAuth with PKCE on a loopback redirect,
RFC 8252). It always prints the address too, in case the browser does not
open. The browser that approves delivers the code to this computer's
`127.0.0.1`, so a link or code sent to someone on another computer delivers
nothing to the sender. That is all it promises: a forwarded callback port
(`ssh -L`, a port-forwarding tool) or another user of the same computer can
still complete a sign-in (see [AUDIT.md](AUDIT.md)).

On a machine that cannot show a browser, it prints a code to enter at
`thalovant.com/activate` from any browser instead: over SSH, in CI, or on Linux
with neither `DISPLAY` nor `WAYLAND_DISPLAY`. It does the same against an API
that does not offer browser sign-in yet, or that it cannot reach. `browserLogin()` and
`loginWithBrowser()` (the device login) are the two methods on their own. All
of them are `suspend` functions, and cancelling one stops the sign-in.

`scopes` asks for what the token may do. The default is `hubs:read` and
`clients:write`. `deviceName` is what the consent page and the token show. The
default (`null`) is `thalovant-kotlin-sdk on <hostname>`; an empty string sends
none. `BrowserLoginOptions.opener`
opens the URL your own way, and `prompt` presents it yourself.

The result is a scoped API token that lasts 365 days and is listed in the
dashboard. It counts against your plan. `api.revokeApiToken()` revokes it.

A denied sign-in throws `ThalovantDeviceLoginDeniedException`. A browser
sign-in that nobody answers within 300 seconds (`timeoutMillis`) throws
`ThalovantDeviceLoginExpiredException`; a device login that runs past its own
900 seconds throws `ThalovantTimeoutException`. Accounts with a password can still use
`api.login(email, password)`.

Browser sign-in is for a JVM on a desktop or a server. Android reports itself
as Linux without a display, so `interactiveLogin()` uses a device login there,
and `browserLogin()` is not supported on Android: an app signs in with
`NativeSignIn` and its own registered redirect, as thalovant-android does.

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

See [SECURITY.md](SECURITY.md)
for how to report a vulnerability.

## Licence

MIT. See [LICENSE](LICENSE).
