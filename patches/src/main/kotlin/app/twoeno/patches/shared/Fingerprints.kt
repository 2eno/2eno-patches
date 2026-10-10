package app.twoeno.patches.shared

import app.morphe.patcher.Fingerprint

/**
 * Every OkHttp response passes through this method, after all interceptors of the app.
 */
internal object InterceptorChainProceedFingerprint : Fingerprint(
    definingClass = "Lokhttp3/internal/http/RealInterceptorChain;",
    name = "proceed",
    returnType = "Lokhttp3/Response;",
    parameters = listOf("Lokhttp3/Request;"),
)
