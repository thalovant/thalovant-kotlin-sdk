Last Edit: Claude Opus 5.5 - 2026-11-08 - Motive: Browser sign-in questions (0.10.0); CodeRabbit on #59: which configuration failures choose a device login, the two timeouts, and the shared-computer limit.

# FAQ: thalovant-kotlin-sdk

**Can I manage chat apps (Telegram, Discord, Matrix, Mattermost, Twitch bridges) with this SDK?**
No. Chat apps are managed from the console, the Python, Node, Go and Rust SDKs, or the MCP server. `contracts/sdk-parity.json` records the `chat-apps` capability as not applicable here.

**Why does this repository change when the Python SDK adds a feature?**
The Python SDK is the parity reference. Each consumer records the reference digest it has reviewed, and answers every capability, in `contracts/sdk-parity.json`; the reference cannot release until every consumer has.

**How does a desktop or server program sign in without a password?**
Call `interactiveLogin()`. On a machine that can show a browser it runs `browserLogin()`: it listens on `127.0.0.1` on a port the system picks, opens the consent page, prints the address too, and exchanges the code the browser brings back (PKCE, S256). Elsewhere it runs a device login (`loginWithBrowser()`) and prints a code to approve at `thalovant.com/activate` from any browser.

**Why browser sign-in rather than the device code?**
A device code can be relayed: somebody reads it off one screen and types it in anywhere, which is how device-code phishing works. A loopback redirect cannot be: the browser that approves delivers the code to its own computer, so a sign-in link that reaches somebody on another computer gives them nothing. It does not protect against another user signed in to the same computer: one who can read the URL (from the process table, say) can approve it in their own browser with their own account, and the program is then signed in as them. See AUDIT.md.

**When does `interactiveLogin()` use a device login instead?**
When `CI`, `SSH_CONNECTION` or `SSH_TTY` is set; on Linux (and the BSDs, and Android) without `DISPLAY` or `WAYLAND_DISPLAY`; and when `GET /v1/auth/native/config` answers 404 (an API without browser sign-in), answers any other error, answers something unusable, or cannot reach the API at all (the `IOException` is caught, as the Python reference falls back on its `ThalovantAPIUnreachableError`). The same failures with `LoginMethod.BROWSER` are thrown, the unreachable one as its `IOException`. `InteractiveLoginOptions(method = LoginMethod.BROWSER)` or `LoginMethod.DEVICE` forces one. Once the browser has been opened, a denied or expired browser sign-in stays that way: it never turns into a device login.

**Can an Android app use `browserLogin()`?**
No. Android reports itself as Linux without a display, so `interactiveLogin()` uses a device login, and the platform opener does nothing there. An Android app signs in with `NativeSignIn.begin(...)` and `completeNativeSignIn(...)` and its own registered redirect, as thalovant-android does.

**The browser did not open. What now?**
Open the printed URL (`Opening your browser to sign in to Thalovant. If it does not open, visit:`) in a browser on the same machine. Pass `BrowserLoginOptions(opener = { url -> ... })` to open it your own way, or `prompt` to present it yourself.

**What do the sign-in errors mean?**
`ThalovantDeviceLoginDeniedException`: the person chose Deny. `ThalovantDeviceLoginExpiredException`: nobody came back to a browser sign-in within its `timeoutMillis` (300 s by default, for `browserLogin()` and for the browser path of `interactiveLogin()`), or the API reported a device code expired. A device login that runs past its own `timeoutMillis` (900 s by default, for `loginWithBrowser()` and for the device path of `interactiveLogin()`) throws `ThalovantTimeoutException` instead. `InteractiveLoginOptions.timeoutMillis` replaces whichever default applies. `ThalovantApiException` with `detail` `invalid_grant`: the API would not exchange the code (it is single-use and lives 60 s; start again, never retry it). None of them carries the code, the verifier, the state or the token.
