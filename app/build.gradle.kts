import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application") version "9.1.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

val mlKitLatinVersion = "16.0.1"
val paddleOcrBenchmark = providers.gradleProperty("photolibraryPaddleBenchmark").map(String::toBoolean).orElse(false)
val modelProfile = providers.gradleProperty("photolibraryModelProfile").orElse("full").get()
require(modelProfile in setOf("full", "core")) { "Supported photolibraryModelProfile values are full and core" }
val clipTextPrecision = providers.gradleProperty("photolibraryClipTextPrecision").orElse("quantized").get()
val clipTextModelFile = mapOf(
    "quantized" to "text_model_quantized.onnx",
    "q4" to "text_model_q4.onnx",
).getOrElse(clipTextPrecision) { error("Supported photolibraryClipTextPrecision values are quantized and q4") }
val selectedModelAssets = layout.buildDirectory.dir("generated/selected-model-assets")
val visualRerankerSource = providers.gradleProperty("photolibraryVisualReranker").orNull?.let(rootProject::file)
val visualRerankerSha = "7faf3b04a355ce7491e5a3f61aedaff2988d5cac2c299199b440891d82839c97"
val rerankerTextGraphSha = "e4ccd15d806b8af841a036884b42034535d58e04c52df00f56e73f72d3c166d5"
fun File.contentSha256(): String = inputStream().use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val chunk = ByteArray(65_536)
    while (true) {
        val count = stream.read(chunk)
        if (count < 0) break
        digest.update(chunk, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
val stageSelectedModelAssets = tasks.register<Sync>("stageSelectedModelAssets") {
    inputs.property("modelProfile", modelProfile)
    inputs.property("clipTextModelFile", clipTextModelFile)
    if (clipTextPrecision == "q4") {
        val q4Graph = rootProject.file(".local-models/clip/$clipTextModelFile")
        require(q4Graph.isFile) { "Stage the Q4 graph with tools/stage_app_clip.py --q4-source before building" }
    }
    if (visualRerankerSource != null) {
        require(clipTextPrecision == "q4") { "The experimental visual reranker requires Q4" }
        require(!providers.gradleProperty("photolibrarySceneViews").orElse("false").get().toBooleanStrict())
        require(!providers.gradleProperty("photolibraryCompactIndex").orElse("false").get().toBooleanStrict())
        val graph = rootProject.file(".local-models/clip/$clipTextModelFile")
        require(visualRerankerSource.isFile && visualRerankerSource.contentSha256() == visualRerankerSha)
        require(graph.contentSha256() == rerankerTextGraphSha)
        from(visualRerankerSource) {
            into("visual-reranker")
            rename { "weights.plr" }
        }
    }
    from(rootProject.file(".local-models")) {
        if (modelProfile == "core") include("clip/**")
        exclude("visual-reranker/**")
        exclude {
            it.path in setOf("clip/text_model_quantized.onnx", "clip/text_model_q4.onnx") &&
                it.path != "clip/$clipTextModelFile"
        }
    }
    into(selectedModelAssets)
}
val modelCatalogFile = layout.buildDirectory.file("generated/model-pack-catalog/catalog.json")
val modelCatalogAssets = layout.buildDirectory.dir("generated/model-pack-catalog-assets")
val specialistModelFiles = fileTree(rootProject.file(".local-models")) {
    include("detection/**", "identity/**", "garment/**")
}
val generateModelPackCatalog = tasks.register("generateModelPackCatalog") {
    inputs.files(specialistModelFiles)
    outputs.file(modelCatalogFile)
    doLast {
        val root = rootProject.file(".local-models")
        val records = specialistModelFiles.files.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.map { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            Triple(path, hash, file.length())
        }
        val identity = records.joinToString("|") { "${it.first}:${it.second}:${it.third}" }
        val version = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val entries = records.joinToString(",") { (path, hash, bytes) ->
            "{\"path\":\"$path\",\"sha256\":\"$hash\",\"bytes\":$bytes}"
        }
        val manifest = "{\"schema\":1,\"id\":\"specialist-models\",\"version\":\"$version\",\"files\":[$entries]}"
        val output = modelCatalogFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(manifest)
    }
}
val stageModelPackCatalog = tasks.register<Sync>("stageModelPackCatalog") {
    dependsOn(generateModelPackCatalog)
    from(modelCatalogFile) {
        into("model-packs")
        rename { "catalog.json" }
    }
    into(modelCatalogAssets)
}
tasks.register<Zip>("packageSpecialistModelPack") {
    dependsOn(generateModelPackCatalog)
    archiveFileName.set("specialist-model-pack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/model-packs"))
    from(rootProject.file(".local-models")) {
        include("detection/**", "identity/**", "garment/**")
    }
    from(modelCatalogFile) { rename { "manifest.json" } }
}
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
        buildConfigField("String", "MODEL_PROFILE", "\"$modelProfile\"")
        buildConfigField("String", "CLIP_TEXT_MODEL_FILE", "\"$clipTextModelFile\"")
        buildConfigField("String", "VISUAL_RERANKER_TEXT_SHA", "\"${if (visualRerankerSource == null) "" else rerankerTextGraphSha}\"")
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
    sourceSets.getByName("main").assets.directories.add(selectedModelAssets.get().asFile.path)
    sourceSets.getByName("main").assets.directories.add(modelCatalogAssets.get().asFile.path)
    tasks.named("preBuild").configure { dependsOn(stageSelectedModelAssets, stageModelPackCatalog) }

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
