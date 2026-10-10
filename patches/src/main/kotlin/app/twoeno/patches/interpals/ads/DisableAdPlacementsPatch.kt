package app.twoeno.patches.interpals.ads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.p0Register
import app.twoeno.patches.interpals.CreateAssetLoaderFingerprint
import app.twoeno.patches.interpals.CreateFileLoaderFingerprint
import app.twoeno.patches.shared.Constants.COMPATIBILITY_INTERPALS
import app.twoeno.patches.shared.EXTENSION
import app.twoeno.patches.shared.EXTENSION_PACKAGE
import app.twoeno.patches.shared.InterceptorChainProceedFingerprint
import app.twoeno.patches.shared.replaceParameter
import app.twoeno.patches.shared.replaceReturnedObjects
import java.util.logging.Logger

private const val EXTENSION_CLASS = "$EXTENSION_PACKAGE/interpals/DisableAdPlacementsPatch;"

@Suppress("unused")
val disableAdPlacementsPatch = bytecodePatch(
    name = "Disable ad placements",
    description = "Turns off all ad placements of the app, also the ones shown before the ad config is loaded.",
) {
    compatibleWith(COMPATIBILITY_INTERPALS)

    extendWith(EXTENSION)

    execute {
        // The ad config downloaded from the server.
        InterceptorChainProceedFingerprint.method.apply {
            replaceParameter(0, "$EXTENSION_CLASS->filterRequest(Ljava/lang/Object;)Ljava/lang/Object;")
            replaceReturnedObjects("$EXTENSION_CLASS->filterResponse(Ljava/lang/Object;)Ljava/lang/Object;")
        }

        // The defaults compiled into the JavaScript bundle: load a patched copy of the bundle.
        val createFileLoader = CreateFileLoaderFingerprint.method
        CreateAssetLoaderFingerprint.method.apply {
            if (p0Register == 0) {
                Logger.getLogger(this::class.java.name).warning(
                    "No free register in createAssetLoader, the default ad placements stay enabled"
                )
                return@execute
            }

            // p0 is the companion, p1 the context, p2 the asset url and p3 whether to load synchronously.
            addInstructionsWithLabels(
                0,
                """
                    invoke-static { p1, p2 }, $EXTENSION_CLASS->patchedBundlePath(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v0
                    if-eqz v0, :original_bundle
                    invoke-virtual { p0, v0, p2, p3 }, $createFileLoader
                    move-result-object v0
                    return-object v0
                """,
                ExternalLabel("original_bundle", getInstruction(0)),
            )
        }
    }
}
