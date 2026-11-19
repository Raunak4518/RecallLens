package org.photolibrary.app.feasibility

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.photolibrary.app.index.EncryptedPhotoIndex
import org.photolibrary.app.index.IndexPhoto
import org.photolibrary.app.index.IndexingControl
import org.photolibrary.app.index.IndexingStage
import org.photolibrary.app.index.LocalIndexCoordinator
import org.photolibrary.app.index.LocalPhotoIndexer
import org.photolibrary.app.index.ModelRevision
import org.photolibrary.app.index.PhotoIndexSession
import org.photolibrary.app.index.PhotoProcessingException
import org.photolibrary.app.index.PhotoProcessor
import org.photolibrary.app.index.PhotoProcessorFactory
import org.photolibrary.app.index.PhotoText
import org.photolibrary.app.index.TextLine
import org.photolibrary.app.library.GalleryAccess
import org.photolibrary.app.library.LibrarySources
import org.photolibrary.app.library.PhotoOrigin
import org.photolibrary.app.library.SelectedPhoto
import org.photolibrary.app.semantic.PhotoEmbedding

@RunWith(AndroidJUnit4::class)
class StagedIndexingTest {
    @Test
    fun fastPassPublishesBeforeTextAndBothPassesResumeAfterReopening() = withFixture { fixture ->
        val first = fixture.coordinator()
        assertTrue(first.runBatch())
        assertEquals(listOf("visual:0", "visual:1"), fixture.calls)
        assertEquals(2, first.progress.value.visualReady)
        assertEquals(0, first.progress.value.textReady)
        assertEquals(fixture.uris(0, 1), first.progress.value.visualUpdated.keys)
        assertTrue(first.progress.value.textUpdated.isEmpty())
        assertEquals(IndexingStage.VISUAL, fixture.checkpoint().stage)
        assertEquals(2, fixture.checkpoint().cursor)
        assertEquals(fixture.uris(0, 1), fixture.visualIndex.readCached(fixture.inputs(), fixture.uris(0, 1)).keys)

        val afterVisualRestart = fixture.coordinator()
        repeat(4) {
            if (fixture.checkpoint().stage == IndexingStage.VISUAL) assertTrue(afterVisualRestart.runBatch())
        }
        assertEquals(IndexingStage.TEXT, fixture.checkpoint().stage)
        assertFalse(fixture.checkpoint().finished)
        assertEquals(listOf("visual:0", "visual:1", "visual:2", "visual:3", "visual:4"), fixture.calls)
        assertEquals(5, afterVisualRestart.progress.value.visualReady)
        assertTrue(afterVisualRestart.progress.value.textUpdated.isEmpty())

        assertTrue(afterVisualRestart.runBatch())
        assertEquals(listOf("text:0", "text:1"), fixture.calls.takeLast(2))
        assertEquals(2, afterVisualRestart.progress.value.textReady)
        assertEquals(fixture.uris(0, 1), afterVisualRestart.progress.value.textUpdated.keys)
        assertEquals(fixture.uris(2, 3, 4), afterVisualRestart.progress.value.visualUpdated.keys)
        assertEquals(2, fixture.checkpoint().cursor)

        val afterTextRestart = fixture.coordinator()
        finish(afterTextRestart)
        assertTrue(fixture.checkpoint().finished)
        assertEquals(5, afterTextRestart.progress.value.textReady)
        assertEquals(5, afterTextRestart.progress.value.visualReady)
        assertEquals((0..4).map { "visual:$it" } + (0..4).map { "text:$it" }, fixture.calls)
        assertFalse(fixture.coordinator().runBatch())
        assertEquals(10, fixture.calls.size)
    }

    @Test
    fun cachedVisualSearchEvidenceRemainsAvailableDuringBlockedTextAttempt() = withFixture { fixture ->
        val enteredText = CountDownLatch(1)
        val releaseText = CountDownLatch(1)
        fixture.textAction = {
            enteredText.countDown()
            check(releaseText.await(15, TimeUnit.SECONDS))
        }
        val coordinator = fixture.coordinator()
        repeat(4) {
            if (fixture.checkpoint().stage == IndexingStage.VISUAL) assertTrue(coordinator.runBatch())
        }
        assertEquals(IndexingStage.TEXT, fixture.checkpoint().stage)
        val batch = async(Dispatchers.IO) { coordinator.runBatch() }
        try {
            assertTrue(enteredText.await(10, TimeUnit.SECONDS))
            assertFalse(batch.isCompleted)
            val all = fixture.uris(0, 1, 2, 3, 4)
            assertEquals(5, coordinator.progress.value.visualReady)
            assertEquals(all, withTimeout(5_000) { fixture.visualIndex.readCached(fixture.inputs(), all).keys })
        } finally {
            releaseText.countDown()
            batch.await()
        }
        finish(coordinator)
        assertTrue(fixture.checkpoint().finished)
    }

    @Test
    fun restoredProgressPublishesPersistedPauseBeforeRunningWork() = withFixture { fixture ->
        val first = fixture.coordinator()
        assertTrue(first.runBatch())
        first.pause(true)
        val paused = fixture.checkpoint()
        assertTrue(paused.paused)
        assertEquals(IndexingStage.VISUAL, paused.stage)
        assertEquals(2, paused.cursor)

        val reopened = fixture.coordinator()
        assertEquals(0L, reopened.progress.value.generation)
        reopened.restoreProgress()
        val restored = reopened.progress.value
        assertEquals(paused, restored.checkpoint)
        assertTrue(restored.generation > 0L)
        assertTrue(restored.visualUpdated.isEmpty())
        assertTrue(restored.textUpdated.isEmpty())
        reopened.restoreProgress()
        assertEquals(restored.generation, reopened.progress.value.generation)
        assertFalse(reopened.runBatch())
        assertEquals(listOf("visual:0", "visual:1"), fixture.calls)

        reopened.pause(false)
        assertTrue(reopened.runBatch())
        assertEquals(listOf("visual:0", "visual:1", "visual:2", "visual:3"), fixture.calls)
        assertEquals(4, reopened.progress.value.visualReady)
        finish(reopened)
    }

    @Test
    fun canceledTextBatchPublishesSelectedPhotosAndRetainsCommittedEvidenceBeforeResume() = withFixture { fixture ->
        val secondEntered = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        fixture.textAction = { id ->
            if (id == 1) {
                secondEntered.countDown()
                check(releaseSecond.await(15, TimeUnit.SECONDS))
            }
        }
        val coordinator = fixture.coordinator()
        repeat(4) {
            if (fixture.checkpoint().stage == IndexingStage.VISUAL) assertTrue(coordinator.runBatch())
        }
        val before = coordinator.progress.value
        assertEquals(IndexingStage.TEXT, before.checkpoint.stage)
        assertEquals(0, before.checkpoint.cursor)
        assertTrue(before.textUpdated.isEmpty())

        val batch = async(Dispatchers.IO) { coordinator.runBatch() }
        try {
            assertTrue(secondEntered.await(10, TimeUnit.SECONDS))
        } finally {
            batch.cancel()
            releaseSecond.countDown()
            batch.join()
        }

        val interrupted = coordinator.progress.value
        assertTrue(batch.isCancelled)
        assertTrue(interrupted.generation > before.generation)
        assertEquals(fixture.uris(0, 1), interrupted.textUpdated.filterValues { it > before.generation }.keys)
        assertEquals(before.visualUpdated, interrupted.visualUpdated)
        assertEquals(before.checkpoint, fixture.checkpoint())
        assertEquals(fixture.uris(0), fixture.textIndex.readCached(fixture.inputs(), fixture.uris(0, 1)).keys)

        fixture.textAction = {}
        finish(coordinator)
        assertEquals(listOf("text:0", "text:1", "text:1", "text:2", "text:3", "text:4"), fixture.calls.takeLast(6))
        assertEquals(5, coordinator.progress.value.textReady)
        assertTrue(fixture.checkpoint().finished)
    }

    @Test
    fun textFailureKeepsVisualEvidenceAndChangedSourceRestartsAtVisual() = withFixture { fixture ->
        fixture.textAction = { if (it == 1) throw PhotoProcessingException(IllegalStateException("Fixture text failure")) }
        val coordinator = fixture.coordinator()
        finish(coordinator)
        assertTrue(fixture.checkpoint().finished)
        assertEquals(1, fixture.checkpoint().failed)
        assertEquals(5, coordinator.progress.value.visualReady)
        assertEquals(4, coordinator.progress.value.textReady)
        assertEquals((0..4).map { "visual:$it" } + (0..4).map { "text:$it" }, fixture.calls)

        fixture.photos = fixture.photos.mapIndexed { index, photo ->
            if (index == 2) photo.copy(sourceRevision = "edited") else photo
        }
        fixture.textAction = {}
        fixture.calls.clear()
        val changed = fixture.coordinator()
        finish(changed)
        assertEquals(listOf("visual:2", "text:1", "text:2"), fixture.calls)
        assertEquals(0, fixture.checkpoint().failed)
        assertEquals(5, changed.progress.value.visualReady)
        assertEquals(5, changed.progress.value.textReady)
    }

    private suspend fun finish(coordinator: LocalIndexCoordinator) {
        repeat(12) { if (!coordinator.runBatch()) return }
        error("Indexing did not finish")
    }

    private fun withFixture(test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "staged-index-${UUID.randomUUID()}"
        val directory = context.noBackupFilesDir.resolve(alias)
        try {
            test(Fixture(EncryptedPhotoIndex(directory, alias)))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
            directory.deleteRecursively()
        }
    }

    private class Fixture(private val storage: EncryptedPhotoIndex) {
        var photos = List(5) { SelectedPhoto(Uri.parse("content://staged/photo/$it"), null, PhotoOrigin.GALLERY, "revision-$it") }
        val calls = mutableListOf<String>()
        var textAction: (Int) -> Unit = {}
        val textIndex = index("text", storage::open) { id ->
            textAction(id)
            PhotoText(listOf(TextLine("Staged photo $id", "en", null)))
        }
        val visualIndex = index("visual", storage::openEmbeddings) { id ->
            PhotoEmbedding.normalized(FloatArray(512).apply { this[id] = 1f })
        }

        fun coordinator() = LocalIndexCoordinator(
            IndexingControl(storage), { LibrarySources(GalleryAccess.ALL, emptyList(), photos) },
            textIndex, { visualIndex }, { "staged-index-v1" }, batchSize = 2,
        )

        fun checkpoint() = IndexingControl(storage).read()

        fun inputs() = photos.map { IndexPhoto(it.uri, it.sourceRevision) }

        fun uris(vararg ids: Int) = ids.mapTo(linkedSetOf()) { photos[it].uri }

        private fun <Output : Any> index(
            stage: String,
            open: () -> PhotoIndexSession<Output>,
            result: (Int) -> Output,
        ): LocalPhotoIndexer<Output> {
            val revision = ModelRevision("fixture-$stage", "1", 32)
            return LocalPhotoIndexer(
                loadImage = { uri, _ ->
                    val id = checkNotNull(uri.lastPathSegment).toInt()
                    val edited = photos.firstOrNull { it.uri == uri }?.sourceRevision == "edited"
                    Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.rgb(id, if (edited) 1 else 0, 0))
                    }
                },
                processorFactory = PhotoProcessorFactory(revision) {
                    object : PhotoProcessor<Output> {
                        override val model = revision
                        override fun process(image: Bitmap): Output {
                            val id = Color.red(image.getPixel(0, 0))
                            calls += "$stage:$id"
                            return result(id)
                        }
                        override fun close() = Unit
                    }
                },
                openIndex = open,
                readRevision = { uri -> photos.firstOrNull { it.uri == uri }?.sourceRevision },
            )
        }
    }
}
