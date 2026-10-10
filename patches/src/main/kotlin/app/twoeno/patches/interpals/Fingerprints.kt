package app.twoeno.patches.interpals

import app.morphe.patcher.Fingerprint

private const val JS_BUNDLE_LOADER_COMPANION = "Lcom/facebook/react/bridge/JSBundleLoader\$Companion;"

internal object CreateAssetLoaderFingerprint : Fingerprint(
    definingClass = JS_BUNDLE_LOADER_COMPANION,
    name = "createAssetLoader",
    returnType = "Lcom/facebook/react/bridge/JSBundleLoader;",
    parameters = listOf("Landroid/content/Context;", "Ljava/lang/String;", "Z"),
)

internal object CreateFileLoaderFingerprint : Fingerprint(
    definingClass = JS_BUNDLE_LOADER_COMPANION,
    name = "createFileLoader",
    returnType = "Lcom/facebook/react/bridge/JSBundleLoader;",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Z"),
)

internal object ApplicationOnCreateFingerprint : Fingerprint(
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
    custom = { _, classDef ->
        classDef.superclass == "Landroid/app/Application;" &&
            "Lcom/facebook/react/ReactApplication;" in classDef.interfaces
    },
)
