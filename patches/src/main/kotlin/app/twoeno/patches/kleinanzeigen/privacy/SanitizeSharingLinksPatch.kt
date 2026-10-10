package app.twoeno.patches.kleinanzeigen.privacy

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.twoeno.patches.shared.Constants.COMPATIBILITY_KLEINANZEIGEN
import app.twoeno.patches.shared.EXTENSION
import app.twoeno.patches.shared.EXTENSION_PACKAGE
import app.twoeno.patches.shared.replaceReturnedObjects
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS = "$EXTENSION_PACKAGE/kleinanzeigen/SanitizeSharingLinksPatch;"

/**
 * Part of the tracking parameters added to shared links.
 */
private const val SHARING_CAMPAIGN = "utm_campaign=socialbuttons"

@Suppress("unused")
val sanitizeSharingLinksPatch = bytecodePatch(
    name = "Sanitize sharing links",
    description = "Removes the tracking parameters (utm_*) from shared listing and profile links.",
) {
    compatibleWith(COMPATIBILITY_KLEINANZEIGEN)

    extendWith(EXTENSION)

    execute {
        val urlBuilders = classDefByStrings(SHARING_CAMPAIGN, StringComparisonType.CONTAINS).flatMap { classDef ->
            mutableClassDefBy(classDef).methods.filter { method ->
                method.returnType == "Ljava/lang/String;" && method.implementation?.instructions?.any { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as? StringReference)
                        ?.string?.contains(SHARING_CAMPAIGN) == true
                } == true
            }
        }
        if (urlBuilders.isEmpty()) throw PatchException("Could not find the sharing url builders")

        urlBuilders.forEach {
            it.replaceReturnedObjects("$EXTENSION_CLASS->sanitize(Ljava/lang/String;)Ljava/lang/String;")
        }
    }
}
