package org.photolibrary.app

import android.app.Application
import org.photolibrary.app.detection.OnnxRegionModels
import org.photolibrary.app.identity.IdentityModels
import org.photolibrary.app.library.IdentityMatcher
import org.photolibrary.app.library.CaptureEvents
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.CloudTextStore
import org.photolibrary.app.index.GarmentEvidenceStore
import org.photolibrary.app.index.LocalPhotoIndexer
import org.photolibrary.app.index.configuredTextFactory
import org.photolibrary.app.index.scriptAwareFactory
import org.photolibrary.app.index.LocalTextIndex
import org.photolibrary.app.index.NamedSubjectStore
import org.photolibrary.app.index.PhotoProcessorFactory
import org.photolibrary.app.index.IndexingControl
import org.photolibrary.app.index.LocalIndexCoordinator
import org.photolibrary.app.index.PhotoIndexScheduler
import org.photolibrary.app.index.LocationCache
import org.photolibrary.app.library.PhotoLibrary
import org.photolibrary.app.library.PhotoDetectionScan
import org.photolibrary.app.library.PhotoLocations
import org.photolibrary.app.library.PhotoSubjects
import org.photolibrary.app.semantic.ClipModel

class PhotoLibraryApplication : Application() {
    internal val library by lazy { PhotoLibrary(this) }
    internal val regionModels by lazy { OnnxRegionModels(assets) }
    internal val cloudText by lazy { CloudTextStore(storage) }
    internal val garmentEvidence by lazy { GarmentEvidenceStore(storage) }
    internal val garmentAnalysis by lazy { org.photolibrary.app.library.CachedGarmentAnalysis(
        library, garmentEvidence, org.photolibrary.app.detection.GarmentModels(assets), regionModels) }
    internal val clothingSearch by lazy { org.photolibrary.app.library.ClothingSearch(garmentAnalysis, garmentEvidence, photoLocations,
        org.photolibrary.app.library.PhotoEvidenceValidation(library)) }
    private val identityModels by lazy { IdentityModels(assets) }
    internal val photoDetection by lazy { PhotoDetectionScan(library, regionModels, identityModels) }
    internal val identityMatcher by lazy { IdentityMatcher(library, regionModels, identityModels, storage) }
    internal val storage by lazy { EncryptedPhotoIndex(noBackupFilesDir.resolve("photo-text-index"), "photo-library.ocr-wrap.v1") }
    private val textFactory by lazy { scriptAwareFactory(configuredTextFactory(this)) }
    internal val textIndex by lazy { LocalTextIndex.create(storage, library::loadPixels, library::currentRevision, textFactory, BuildConfig.DETAIL_OCR) }
    internal val photoSubjects by lazy { PhotoSubjects(library, NamedSubjectStore(storage)) }
    internal val photoLocations by lazy { PhotoLocations(this, library, LocationCache(storage)) }
    internal val contentSearch by lazy { org.photolibrary.app.library.PhotoContentSearch(photoLocations,
        org.photolibrary.app.library.PhotoEvidenceValidation(library)) }
    internal val clip by lazy { ClipModel(assets) }
    internal val captureEvents by lazy {
        assets.open("search/events.json").bufferedReader().use { CaptureEvents.read(it.readText()) }
    }
    internal val visualIndex by lazy { LocalPhotoIndexer(library::loadPixels, PhotoProcessorFactory(clip.revision, clip::imageProcessor),
        storage::openEmbeddings, library::currentRevision, clip.copySignatureUpgrade, reuseEquivalentPixels = true) }
    internal val indexing by lazy {
        LocalIndexCoordinator(
            IndexingControl(storage), library::list, textIndex,
            { if (clip.available) visualIndex else null },
            { "decoder-v1:${textFactory.model}:detail=${BuildConfig.DETAIL_OCR}:${if (clip.available) clip.revision else "no-visual"}" },
            visualBatchSize = 32,
            fair = BuildConfig.FAIR_INDEX,
        )
    }
    internal val indexScheduler by lazy { PhotoIndexScheduler(this) }
}
