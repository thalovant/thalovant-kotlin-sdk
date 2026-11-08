Last Edit: Claude Opus 5.5 - 2026-11-08 - Motive: Browser sign-in questions (0.10.0).

# FAQ: thalovant-kotlin-sdk

**Can I manage chat apps (Telegram, Discord, Matrix, Mattermost, Twitch bridges) with this SDK?**
No. Chat apps are managed from the console, the Python, Node, Go and Rust SDKs, or the MCP server. `contracts/sdk-parity.json` records the `chat-apps` capability as not applicable here.

**Why does this repository change when the Python SDK adds a feature?**
The Python SDK is the parity reference. Each consumer records the reference digest it has reviewed, and answers every capability, in `contracts/sdk-parity.json`; the reference cannot release until every consumer has.

**How does a desktop or server program sign in without a password?**
Call `interactiveLogin()`. On a machine that can show a browser it runs `browserLogin()`: it listens on `127.0.0.1` on a port the system picks, opens the consent page, prints the address too, and exchanges the code the browser brings back (PKCE, S256). Elsewhere it runs a device login (`loginWithBrowser()`) and prints a code to approve at `thalovant.com/activate` from any browser.

**Why browser sign-in rather than the device code?**
A device code can be relayed: somebody reads it off one screen and types it in anywhere, which is how device-code phishing works. A loopback redirect cannot be: the browser that approves delivers the code to its own computer, so a sign-in link sent to somebody else gives them nothing.

**When does `interactiveLogin()` use a device login instead?**
When `CI`, `SSH_CONNECTION` or `SSH_TTY` is set; on Linux (and the BSDs, and Android) without `DISPLAY` or `WAYLAND_DISPLAY`; and when `GET /v1/auth/native/config` answers 404 (an API without browser sign-in), fails, or answers something unusable. `InteractiveLoginOptions(method = LoginMethod.BROWSER)` or `LoginMethod.DEVICE` forces one. Once the browser has been opened, a denied or expired browser sign-in stays that way: it never turns into a device login.

**Can an Android app use `browserLogin()`?**
No. Android reports itself as Linux without a display, so `interactiveLogin()` uses a device login, and the platform opener does nothing there. An Android app signs in with `NativeSignIn.begin(...)` and `completeNativeSignIn(...)` and its own registered redirect, as thalovant-android does.

**The browser did not open. What now?**
Open the printed URL (`Opening your browser to sign in to Thalovant. If it does not open, visit:`) in a browser on the same machine. Pass `BrowserLoginOptions(opener = { url -> ... })` to open it your own way, or `prompt` to present it yourself.

**What do the sign-in errors mean?**
`ThalovantDeviceLoginDeniedException`: the person chose Deny. `ThalovantDeviceLoginExpiredException`: nobody came back within `timeoutMillis` (300 s by default). `ThalovantApiException` with `detail` `invalid_grant`: the API would not exchange the code (it is single-use and lives 60 s; start again, never retry it). None of them carries the code, the verifier, the state or the token.
