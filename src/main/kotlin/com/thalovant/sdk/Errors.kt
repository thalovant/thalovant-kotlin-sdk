package com.thalovant.sdk

/** Base class for every exception thrown by the Thalovant SDK. */
public open class ThalovantException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Identity files or identity payloads are missing fields or are insecure. */
public class ThalovantIdentityException(message: String, cause: Throwable? = null) : ThalovantException(message, cause)

/** Data-plane connection or handshake failures. */
public class ThalovantConnectionException(message: String, cause: Throwable? = null) : ThalovantException(message, cause)

/** Deadlines exceeded while waiting on the hub. */
public class ThalovantTimeoutException(message: String) : ThalovantException(message)

/** The hub reported a runtime failure while handling a request. */
public class ThalovantRuntimeException(message: String) : ThalovantException(message)

/**
 * Control-plane API failures. [statusCode] and [body] are set when the API
 * responded with a non-2xx status; both are null for local failures such as a
 * missing access token or an unexpected response shape.
 */
public class ThalovantApiException(
    message: String,
    public val statusCode: Int? = null,
    public val body: String? = null,
) : ThalovantException(message)

/** The requested data-plane protocol is unavailable or unsupported. */
public class ThalovantUnsupportedProtocolException(message: String) : ThalovantException(message)

/** The device sign-in request was denied in the browser. */
public class ThalovantDeviceLoginDeniedException(message: String) : ThalovantException(message)

/** The device sign-in code expired before it was approved. */
public class ThalovantDeviceLoginExpiredException(message: String) : ThalovantException(message)
