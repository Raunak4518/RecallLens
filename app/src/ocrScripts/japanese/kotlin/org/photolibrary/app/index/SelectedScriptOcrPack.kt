package org.photolibrary.app.index

import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

internal val selectedScriptOcrPack: ScriptOcrPack? = ScriptOcrPack("japanese", setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HAN)) {
    TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
}
