package org.photolibrary.app.index

import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

internal val selectedScriptOcrPack: ScriptOcrPack? = ScriptOcrPack("chinese", setOf(Character.UnicodeScript.HAN)) {
    TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
}
