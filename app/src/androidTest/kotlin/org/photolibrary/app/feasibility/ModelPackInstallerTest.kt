package org.photolibrary.app.feasibility

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.photolibrary.app.BuildConfig
import org.photolibrary.app.PhotoLibraryApplication
import org.photolibrary.app.models.ModelFileStore
import org.photolibrary.app.models.ModelPackCatalog
import org.photolibrary.app.models.ModelPackFile
import org.photolibrary.app.models.ModelPackInstaller

@RunWith(AndroidJUnit4::class)
class ModelPackInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun realCorePackOpensSpecialistRuntimesAndReopensAfterActivation() {
        val token = InstrumentationRegistry.getArguments().getString("realSpecialistPack")
        assumeNotNull(token)
        require(checkNotNull(token).matches(Regex("specialist-smoke-[0-9a-f]{32}\\.zip")))
        assertEquals("core", BuildConfig.MODEL_PROFILE)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PhotoLibraryApplication
        val archive = app.filesDir.resolve(token)
        val root = temporaryFolder.newFolder("real-specialist-pack")
        val catalog = ModelPackCatalog.from(app.assets)
        assertTrue(catalog.files.any { it.path.startsWith("identity/") })
        assertTrue(catalog.files.any { it.path.startsWith("detection/") })
        assertTrue(catalog.files.any { it.path.startsWith("garment/") })
        val files = ModelFileStore(app.assets, root, catalog)
        assertFalse(files.installed())
        val installer = ModelPackInstaller(root, catalog, files, app::validateSpecialistPack)
        archive.inputStream().use { assertEquals(catalog.version, installer.install(it)) }
        assertTrue(files.validInstalled())
        val reopened = ModelFileStore(app.assets, root, catalog)
        assertTrue(reopened.validInstalled())
        app.validateSpecialistPack(reopened)
        ModelPackInstaller(root, catalog, reopened, app::validateSpecialistPack).remove()
        assertFalse(ModelFileStore(app.assets, root, catalog).installed())
    }

    @Test
    fun validPackIsActivatedAfterRuntimeValidation() {
        val expected = "pinned model bytes".toByteArray()
        var validated = false
        val fixture = fixture(expected) { candidate ->
            assertArrayEquals(expected, candidate.open(PATH).use { it.readBytes() })
            validated = true
        }

        assertEquals(fixture.catalog.version, fixture.installer.install(archive(fixture).inputStream()))
        assertTrue(validated)
        assertTrue(fixture.files.installed())
        assertArrayEquals(expected, fixture.files.open(PATH).use { it.readBytes() })
        assertNotNull(fixture.files.activeDirectory())
    }

    @Test
    fun changedModelBytesAreRejectedWithoutActivation() {
        val expected = "pinned model bytes".toByteArray()
        val fixture = fixture(expected)

        assertThrows(IOException::class.java) {
            fixture.installer.install(archive(fixture, "tamper model bytes".toByteArray()).inputStream())
        }

        assertNull(fixture.files.activeDirectory())
        assertTrue(fixture.packRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun changedInstalledModelIsRejectedBeforeLoading() {
        val expected = "pinned model bytes".toByteArray()
        val fixture = fixture(expected)
        fixture.installer.install(archive(fixture).inputStream())
        val active = checkNotNull(fixture.files.activeDirectory())
        val installed = File(File(fixture.packRoot, active), PATH)
        installed.writeBytes("tamper model bytes".toByteArray())
        assertTrue(installed.setLastModified(System.currentTimeMillis() + 5_000))

        assertThrows(IOException::class.java) { fixture.files.open(PATH) }

        assertEquals(active, fixture.files.activeDirectory())
        assertFalse(fixture.files.validInstalled())
    }

    @Test
    fun traversalEntryIsRejectedBeforeWritingOutsideStaging() {
        val expected = "pinned model bytes".toByteArray()
        val fixture = fixture(expected)
        val outside = File(fixture.root.parentFile, "escape")

        assertThrows(IOException::class.java) {
            fixture.installer.install(archive(fixture, extraName = "../escape", extraBytes = expected).inputStream())
        }

        assertFalse(outside.exists())
        assertNull(fixture.files.activeDirectory())
    }

    @Test
    fun validationFailureLeavesPreviousActivePointerUnchanged() {
        val expected = "pinned model bytes".toByteArray()
        val fixture = fixture(expected)
        fixture.installer.install(archive(fixture).inputStream())
        val active = checkNotNull(fixture.files.activeDirectory())
        val installed = File(File(fixture.packRoot, active), PATH)
        val changed = "tamper model bytes".toByteArray()
        assertEquals(expected.size, changed.size)
        installed.writeBytes(changed)
        assertTrue(installed.setLastModified(System.currentTimeMillis() + 5_000))
        val failingInstaller = ModelPackInstaller(fixture.root, fixture.catalog, fixture.files) { throw IOException("Runtime rejected candidate") }

        assertThrows(IOException::class.java) { failingInstaller.install(archive(fixture).inputStream()) }

        assertEquals(active, fixture.files.activeDirectory())
        assertTrue(File(fixture.packRoot, active).isDirectory)
    }

    @Test
    fun removingPackDeactivatesAndDeletesInstalledFiles() {
        val fixture = fixture("pinned model bytes".toByteArray())
        fixture.installer.install(archive(fixture).inputStream())

        fixture.installer.remove()

        assertNull(fixture.files.activeDirectory())
        assertFalse(fixture.packRoot.exists())
        assertFalse(fixture.files.installed())
    }

    @Test
    fun interruptedCleanupRemovesTemporaryStateAndPreservesActivePack() {
        val fixture = fixture("pinned model bytes".toByteArray())
        fixture.installer.install(archive(fixture).inputStream())
        val active = checkNotNull(fixture.files.activeDirectory())
        val staging = File(fixture.packRoot, ".staging-interrupted")
        assertTrue(staging.mkdirs())
        val temporaryPointer = File(fixture.root, "${fixture.catalog.id}.active.interrupted.tmp")
        temporaryPointer.writeText("partial")

        fixture.installer.cleanupInterrupted()

        assertFalse(staging.exists())
        assertFalse(temporaryPointer.exists())
        assertEquals(active, fixture.files.activeDirectory())
        assertTrue(fixture.files.validInstalled())
    }

    private fun fixture(
        bytes: ByteArray,
        validate: (ModelFileStore) -> Unit = {},
    ): Fixture {
        val modelDigest = digest(bytes)
        val version = digest("$PATH:$modelDigest:${bytes.size}".toByteArray())
        val catalog = ModelPackCatalog(1, "specialist-models", version, listOf(ModelPackFile(PATH, modelDigest, bytes.size.toLong())))
        val root = temporaryFolder.newFolder("packs")
        val files = ModelFileStore(
            InstrumentationRegistry.getInstrumentation().targetContext.assets,
            root,
            catalog,
        )
        val installer = ModelPackInstaller(root, catalog, files, validate)
        return Fixture(root, catalog, files, installer, bytes)
    }

    private fun manifest(catalog: ModelPackCatalog): ByteArray =
        """{"schema":${catalog.schema},"id":"${catalog.id}","version":"${catalog.version}","files":[{"path":"$PATH","sha256":"${catalog.files.single().sha256}","bytes":${catalog.files.single().bytes}}]}"""
            .toByteArray()

    private fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun archive(
        fixture: Fixture,
        modelBytes: ByteArray = fixture.bytes,
        extraName: String? = null,
        extraBytes: ByteArray = ByteArray(0),
    ): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest(fixture.catalog))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(PATH))
            zip.write(modelBytes)
            zip.closeEntry()
            if (extraName != null) {
                zip.putNextEntry(ZipEntry(extraName))
                zip.write(extraBytes)
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    private data class Fixture(
        val root: File,
        val catalog: ModelPackCatalog,
        val files: ModelFileStore,
        val installer: ModelPackInstaller,
        val bytes: ByteArray,
    ) {
        val packRoot get() = File(root, catalog.id)
    }

    private companion object {
        const val PATH = "identity/model-fixture.onnx"
    }
}
