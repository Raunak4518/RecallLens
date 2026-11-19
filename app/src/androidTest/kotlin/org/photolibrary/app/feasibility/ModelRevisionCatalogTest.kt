package org.photolibrary.app.feasibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.detection.GarmentModelRevisions
import org.photolibrary.app.detection.GarmentModels
import org.photolibrary.app.detection.OnnxRegionModels
import org.photolibrary.app.models.ModelFileStore

@RunWith(AndroidJUnit4::class)
class ModelRevisionCatalogTest {
    @Test
    fun cachedGarmentDependenciesFollowCatalogPinnedArtifacts() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = ModelFileStore.bundled(context.assets)
        val garments = GarmentModels(files)
        val regions = OnnxRegionModels(files)

        assertEquals(
            GarmentModelRevisions.upperClothes(files.sha256("garment/selfie_multiclass_256x256.tflite")),
            garments.revision,
        )
        assertEquals(
            GarmentModelRevisions.personDetector(files.sha256("detection/yolox_nano.onnx")),
            garments.personDetectorRevision,
        )
        assertEquals(
            GarmentModelRevisions.faceDetector(files.sha256("detection/yunet.onnx")),
            regions.faceRevision,
        )
        assertNotEquals(garments.revision, GarmentModelRevisions.upperClothes("0".repeat(64)))
        assertTrue(garments.revision.version.matches(Regex("[0-9a-f]{64}:.+")))
    }
}
