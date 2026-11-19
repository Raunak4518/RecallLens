package org.photolibrary.app.index

import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

internal val selectedScriptOcrPack: ScriptOcrPack? = ScriptOcrPack("korean", setOf(Character.UnicodeScript.HANGUL)) {
    TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
}
