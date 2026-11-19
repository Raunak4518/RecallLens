import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "9.1.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

val mlKitLatinVersion = "16.0.1"
val paddleOcrBenchmark = providers.gradleProperty("photolibraryPaddleBenchmark").map(String::toBoolean).orElse(false)
val ocrEngine = providers.gradleProperty("photolibraryOcr").orElse("mlkit").get()
require(ocrEngine in setOf("mlkit", "tiny")) { "Supported photolibraryOcr values are mlkit and tiny" }
val visualQueryExpansions = providers.gradleProperty("photolibraryQueryExpansions").orElse("").get()
require(visualQueryExpansions.split(',').all { it in setOf("", "typo", "synonym", "transliteration") })
val ocrScript = providers.gradleProperty("photolibraryOcrScript").orElse("none").get()
require(ocrScript in setOf("none", "devanagari", "chinese", "japanese", "korean"))
val scriptedOcrPolicy = providers.gradleProperty("photolibraryOcrScriptPolicy").orElse("all").get()
require(scriptedOcrPolicy in setOf("all", "sparse"))
val tinyPack = ocrEngine == "tiny"
val detailOcr = providers.gradleProperty("photolibraryDetailOcr").orElse("false").get().toBooleanStrict()
val fairIndex = providers.gradleProperty("photolibraryFairIndex").orElse("false").get().toBooleanStrict()
val tinySdk = layout.buildDirectory.dir("ocr-android-sdk").get().asFile
val tinyAssets = layout.buildDirectory.dir("generated/ocr-tiny-assets")
if (tinyPack) {
    val files = listOf("LICENSE", "java/com/paddle/ocr/engine/OCREngine.kt",
        "assets/paddle-mobile/tiny/det.onnx", "assets/paddle-mobile/tiny/rec.onnx", "assets/paddle-mobile/tiny/rec.yml")
    require(files.all { tinySdk.resolve(it).isFile }) {
        "Stage PP-OCRv6 Tiny with python tools/stage_hybrid_ocr_models.py --tiny"
    }
    val stageTinyAssets = tasks.register<Sync>("stageTinyOcrAssets") {
        from(tinySdk.resolve("assets")) { include("paddle-mobile/tiny/**") }
        from(tinySdk.resolve("LICENSE")) { into("paddle-mobile/notices") }
        into(tinyAssets)
    }
    tasks.named("preBuild").configure { dependsOn(stageTinyAssets) }
}
val targetAbi = providers.gradleProperty("photolibraryAbi").orNull
require(targetAbi == null || targetAbi in setOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86"))

android {
    namespace = "org.photolibrary.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.photolibrary.app"
        minSdk = 30
        buildConfigField("String", "OCR_SCRIPT_POLICY", "\"$scriptedOcrPolicy\"")
        buildConfigField("String", "VISUAL_QUERY_EXPANSIONS", "\"$visualQueryExpansions\"")
        buildConfigField("boolean", "SCENE_DETAIL_VIEWS", providers.gradleProperty("photolibrarySceneViews").map { it.toBooleanStrict().toString() }.orElse("false").get())
        buildConfigField("boolean", "COMPACT_SCENE_INDEX", providers.gradleProperty("photolibraryCompactIndex").map { it.toBooleanStrict().toString() }.orElse("false").get())
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (targetAbi != null) ndk.abiFilters += targetAbi
        buildConfigField("boolean", "DETAIL_OCR", detailOcr.toString())
        buildConfigField("boolean", "FAIR_INDEX", fairIndex.toString())
        buildConfigField("String", "ML_KIT_LATIN_VERSION", "\"$mlKitLatinVersion\"")
    }

    buildTypes {
        create("feasibility") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".feasibility"
            matchingFallbacks += "debug"
        }
    }
    testBuildType = "feasibility"
    sourceSets.getByName("androidTest").assets.directories.add("build/public-evaluation")
    sourceSets.getByName("main").kotlin.srcDir("src/ocrScripts/$ocrScript/kotlin")
    sourceSets.getByName("main").kotlin.srcDir(if (tinyPack) "src/ocrTiny/kotlin" else "src/ocrMlKit/kotlin")
    if (tinyPack) {
        sourceSets.getByName("main").kotlin.srcDir(tinySdk.resolve("java"))
        sourceSets.getByName("main").assets.directories.add(tinyAssets.get().asFile.path)
    }
    if (paddleOcrBenchmark.get()) {
        if (!tinyPack) sourceSets.getByName("androidTest").kotlin.srcDir("build/ocr-android-sdk/java")
        sourceSets.getByName("androidTest").kotlin.srcDir(rootProject.file("tools/paddle-mobile"))
        sourceSets.getByName("androidTest").assets.directories.add("build/ocr-android-sdk/assets")
    }
    sourceSets.getByName("main").assets.directories.add(rootProject.file(".local-models").path)

    androidResources {
        noCompress += "onnx"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    implementation("androidx.work:work-runtime:2.12.0")
    implementation("androidx.room:room-runtime:2.8.5")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.room:room-runtime:2.8.5")
    androidTestAnnotationProcessor("androidx.room:room-compiler:2.8.5")
    implementation("net.zetetic:sqlcipher-android:4.20.0")
    implementation("androidx.sqlite:sqlite:2.6.2")
    implementation("com.google.mlkit:text-recognition:$mlKitLatinVersion")
    if (ocrScript != "none") implementation("com.google.mlkit:text-recognition-$ocrScript:$mlKitLatinVersion")
    androidTestImplementation("com.google.crypto.tink:tink-android:1.24.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
    implementation("com.google.ai.edge.litert:litert:1.4.2")
    androidTestImplementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    if (tinyPack) implementation("org.opencv:opencv:4.12.0")
    if (paddleOcrBenchmark.get() && !tinyPack) androidTestImplementation("org.opencv:opencv:4.12.0")
}
