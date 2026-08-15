package com.thalovant.sdk

/**
 * SDK release version — the single source of truth for the version inside this
 * package. Keep in sync with the Gradle `version`, README, and CHANGELOG.
 */
public const val SDK_VERSION: String = "0.1.3"

/**
 * Default User-Agent sent to the control plane and the hub data plane.
 *
 * Derived from [SDK_VERSION] so a release bump cannot leave the user agent
 * behind: there is no second version literal to forget.
 */
public const val DEFAULT_USER_AGENT: String = "ThalovantKotlinSDK/$SDK_VERSION"

/** Default Thalovant control-plane API URL. */
public const val DEFAULT_CONTROL_API_URL: String = "https://api.thalovant.com"
