package com.thalovant.sdk

/**
 * SDK release version — the single source of truth for the version inside this
 * package. Keep in sync with the Gradle `version`, README, and CHANGELOG.
 */
public const val SDK_VERSION: String = "0.7.15"

/**
 * Default User-Agent sent to the control plane and the hub data plane.
 *
 * Derived from [SDK_VERSION] so a release bump cannot leave the user agent
 * behind: there is no second version literal to forget.
 */
public const val DEFAULT_USER_AGENT: String = "ThalovantKotlinSDK/$SDK_VERSION"

/** Default Thalovant control-plane API URL. */
public const val DEFAULT_CONTROL_API_URL: String = "https://api.thalovant.com"

/**
 * How long a hub is given to refuse a client after the Noise handshake.
 *
 * The v3 handshake carries this side's static key in its last message, so a
 * hub forms its opinion of that key after there is nothing left for it to
 * send. It answers a key it accepts with silence and one it will not have by
 * closing the socket -- so the only proof a connection was accepted is that
 * it is still open a round trip later.
 *
 * Measured hub-side cadence for a handshake with a cached PSK is about 34 ms,
 * and about 730 ms for a cold one, both on production hardware; a refusal
 * needs one network round trip on top. 750 ms covers the cold case on a
 * healthy link without making a phone wait for a connection it already has.
 *
 * See `HiveMindWssTransport.acceptanceWindowMs` for what a caller gets out of
 * it, and what a refusal slower than this costs (nothing but lateness).
 */
public const val DEFAULT_ACCEPTANCE_WINDOW_MS: Long = 750
