package org.photolibrary.app.index

import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions

internal val selectedScriptOcrPack: ScriptOcrPack? = ScriptOcrPack("devanagari", setOf(Character.UnicodeScript.DEVANAGARI)) {
    TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
}
