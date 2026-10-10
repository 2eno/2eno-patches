package app.twoeno.patches.interpals.misc

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.twoeno.patches.interpals.ApplicationOnCreateFingerprint
import app.twoeno.patches.shared.Constants.COMPATIBILITY_INTERPALS
import app.twoeno.patches.shared.EXTENSION
import app.twoeno.patches.shared.EXTENSION_PACKAGE

private const val EXTENSION_CLASS = "$EXTENSION_PACKAGE/interpals/DownloadPhotosPatch;"

@Suppress("unused")
val downloadPhotosPatch = bytecodePatch(
    name = "Download photos",
    description = "Adds \"Download photo\" to the menu of the photo viewer.",
) {
    compatibleWith(COMPATIBILITY_INTERPALS)

    extendWith(EXTENSION)

    execute {
        ApplicationOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->install(Landroid/app/Application;)V",
        )
    }
}
