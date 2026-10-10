package app.twoeno.patches.interpals.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.twoeno.patches.interpals.ApplicationOnCreateFingerprint
import app.twoeno.patches.shared.Constants.COMPATIBILITY_INTERPALS
import app.twoeno.patches.shared.EXTENSION
import app.twoeno.patches.shared.EXTENSION_PACKAGE
import app.twoeno.patches.shared.InterceptorChainProceedFingerprint
import app.twoeno.patches.shared.replaceParameter

private const val EXTENSION_CLASS = "$EXTENSION_PACKAGE/interpals/FeedFilterPatch;"

@Suppress("unused")
val feedFilterPatch = bytecodePatch(
    name = "Feed age and gender filter",
    description = "Adds \"Age & gender\" to the filter of the feed.",
) {
    compatibleWith(COMPATIBILITY_INTERPALS)

    extendWith(EXTENSION)

    execute {
        ApplicationOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->install(Landroid/app/Application;)V",
        )

        InterceptorChainProceedFingerprint.method.replaceParameter(
            0,
            "$EXTENSION_CLASS->filterRequest(Ljava/lang/Object;)Ljava/lang/Object;",
        )
    }
}
