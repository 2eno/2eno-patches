package app.twoeno.patches.kleinanzeigen.pur

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.returnEarly
import app.twoeno.patches.shared.Constants.COMPATIBILITY_KLEINANZEIGEN
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.Locale

private const val CONFIG_PACKAGE = "Lebk/config/"
private const val FLAG_NAME_PREFIX = "AdFreeSubscription"
private const val FLAG_KEY = "ad_free_subscription"

private fun Method.constStrings() = implementation?.instructions?.mapNotNull { instruction ->
    if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) return@mapNotNull null
    ((instruction as ReferenceInstruction).reference as StringReference).string
} ?: emptyList()

/**
 * Remote config flags are Kotlin data objects in the `ebk.config` package,
 * whose generated toString() returns their name, e.g. "AdFreeSubscriptionEnabled".
 */
private fun ClassDef.isPurFlag() = methods.any { method ->
    method.name == "toString" && method.parameterTypes.isEmpty() &&
        method.constStrings().any { it.startsWith(FLAG_NAME_PREFIX) }
}

/**
 * Only for instance methods without parameters: v0 is either a free register or `this`, which is not used anymore.
 */
private fun MutableMethod.returnFalseObject() {
    addInstructions(
        0,
        """
            sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;
            return-object v0
        """,
    )
}

@Suppress("unused")
val hidePurPatch = bytecodePatch(
    name = "Hide Pur",
    description = "Hides the offers of the ad free subscription \"Kleinanzeigen Pur\".",
) {
    compatibleWith(COMPATIBILITY_KLEINANZEIGEN)

    execute {
        val flags = mutableListOf<ClassDef>()
        classDefForEach { classDef ->
            if (classDef.type.startsWith(CONFIG_PACKAGE) && classDef.isPurFlag()) flags += classDef
        }

        var patched = 0
        flags.forEach { flag ->
            mutableClassDefBy(flag).methods.forEach { method ->
                if (AccessFlags.STATIC.isSet(method.accessFlags) || method.parameterTypes.isNotEmpty() ||
                    method.implementation == null || method.name == "toString"
                ) return@forEach

                when (method.returnType) {
                    // The remote key: rename it, so the server value is never found.
                    "Ljava/lang/String;" -> {
                        val key = method.constStrings().singleOrNull()
                            ?.takeIf { it.lowercase(Locale.ROOT).contains(FLAG_KEY) } ?: return@forEach
                        method.returnEarly("${key}_hidden")
                    }
                    // The default value.
                    "Z" -> method.returnEarly(false)
                    "Ljava/lang/Object;", "Ljava/lang/Boolean;" -> {
                        if (method.instructions.none { it.opcode == Opcode.SGET_OBJECT }) return@forEach
                        method.returnFalseObject()
                    }
                    else -> return@forEach
                }
                patched++
            }
        }
        if (patched == 0) throw PatchException("Could not find the Pur flags")
    }
}
