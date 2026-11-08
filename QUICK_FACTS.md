Last Edit: Claude Opus 5.5 - 2026-11-08 - Motive: Browser sign-in (0.10.0): version, sign-in entry points, parity digests.

# Quick facts: thalovant-kotlin-sdk

| Fact | Value |
| :--- | :--- |
| Package | com.thalovant:thalovant-sdk (Maven) |
| Version | 0.10.0 (build.gradle.kts, `SDK_VERSION` in Constants.kt) |
| Public surface | ThalovantClient, ThalovantControlPlane, HubSession (src/main/kotlin/com/thalovant/sdk) |
| Sign-in entry points | `ThalovantControlPlane.interactiveLogin(InteractiveLoginOptions)` (chooser), `browserLogin(BrowserLoginOptions)` (PKCE on a `127.0.0.1` loopback redirect, RFC 8252), `loginWithBrowser(DeviceLoginOptions)` (device login, RFC 8628), `login(email, password)` |
| Browser sign-in pieces | `NativeSignIn.browserAuthorizationUrl`, `loopbackRedirectUri`, `newState`, `canOpenBrowser`, `defaultDeviceName`, `SDK_CLIENT_ID` (`thalovant-sdk`); `NativeSignInConfig.fromJson`; `LoginMethod` (NativeSignIn.kt, BrowserLogin.kt, Loopback.kt) |
| Browser sign-in on Android | not supported: `interactiveLogin()` picks a device login there; an app uses `NativeSignIn.begin` and its own redirect |
| Parity | consumer of the Python reference; acknowledges digests `57c7e71f6456` (current) and `49744ee385bf` (candidate, thalovant-python-sdk#113) in `contracts/sdk-parity.json` |
| Conformance | vectors vendored in `src/test/resources/thalovant/`; results in `contracts/conformance-results.json` (`THALOVANT_CONFORMANCE_OUT`) |
| Chat apps (`/v1/bridges`) | not covered (`chat-apps` not applicable); see the Python, Node, Go or Rust SDK or the MCP server |
| Validate | `./gradlew --no-daemon build -PtestJavaVersion=17` (CI also runs 21) |
| Release | merge to `main` with release-relevant changes -> `auto-release.yml` |
