package app.twoeno.patches.kleinanzeigen.ads

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import app.twoeno.patches.shared.Constants.COMPATIBILITY_KLEINANZEIGEN
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val LIBERTY_PACKAGE = "Lde/kleinanzeigen/liberty/"
private const val HOME_PACKAGE = "Lebk/ui/home/"

/**
 * The ad configuration of the "Liberty" ad SDK looks up the ad placements of a page with
 * `(LibertyPageType, position: Int, String, Boolean): AdPlacement?` or
 * `(LibertyPageType, String): Map<..., ...>`. Without a placement no ad is loaded.
 */
private fun Method.isAdLookup(pageType: String): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags) || implementation == null) return false

    val parameters = parameterTypes.map { it.toString() }
    return (parameters == listOf(pageType, "I", "Ljava/lang/String;", "Z") && returnType.startsWith("L")) ||
        (parameters == listOf(pageType, "Ljava/lang/String;") &&
            returnType.startsWith("Ljava/util/") && returnType.endsWith("Map;"))
}

private fun Method.createsInstanceOf(type: String) = implementation?.instructions?.any { instruction ->
    instruction.opcode == Opcode.NEW_INSTANCE &&
        ((instruction as ReferenceInstruction).reference as TypeReference).type == type
} == true

@Suppress("unused")
val hideAdsPatch = bytecodePatch(
    name = "Hide ads",
    description = "Hides ads in the feed, search results and listings, and the promoted sellers in the feed.",
) {
    compatibleWith(COMPATIBILITY_KLEINANZEIGEN)

    execute {
        // The class names of the app are obfuscated, the package names are not.
        val libertyClasses = mutableListOf<ClassDef>()
        val homeClasses = mutableListOf<ClassDef>()
        classDefForEach { classDef ->
            when {
                classDef.type.startsWith(LIBERTY_PACKAGE) -> libertyClasses += classDef
                classDef.type.startsWith(HOME_PACKAGE) -> homeClasses += classDef
            }
        }

        // Ad placements.
        val pageType = libertyClasses.singleOrNull { classDef ->
            classDef.staticFields.any { it.name == "PAGE_ATTR_HOME" }
        }?.type ?: throw PatchException("Could not find the Liberty page type")

        val adLookups = libertyClasses.filter { classDef -> classDef.methods.any { it.isAdLookup(pageType) } }
            .flatMap { classDef -> mutableClassDefBy(classDef).methods.filter { it.isAdLookup(pageType) } }
        if (adLookups.isEmpty()) throw PatchException("Could not find the ad lookup methods")
        adLookups.forEach { it.returnEarly(null) }

        // Promoted sellers: "Lokaler Anbieter" and their "Gesponsert" listing in the feed.
        // The mapper creating them from the feed returns null if the feed has no promoted seller.
        val promotedSellerAd = classDefByStrings("PromotedSellerAd(companyInfo=").singleOrNull()?.type
            ?: throw PatchException("Could not find the promoted seller ad")
        val promotedSellerMappers = homeClasses.filter { classDef ->
            classDef.type != promotedSellerAd && classDef.methods.any { it.createsInstanceOf(promotedSellerAd) }
        }.flatMap { classDef ->
            mutableClassDefBy(classDef).methods.filter { method ->
                method.name != "<init>" && method.returnType.startsWith("L") &&
                    method.createsInstanceOf(promotedSellerAd)
            }
        }
        if (promotedSellerMappers.isEmpty()) throw PatchException("Could not find the promoted seller mapper")
        promotedSellerMappers.forEach { it.returnEarly(null) }
    }
}
