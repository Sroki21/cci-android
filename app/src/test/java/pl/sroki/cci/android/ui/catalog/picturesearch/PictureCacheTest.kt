package pl.sroki.cci.android.ui.catalog.picturesearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PictureCacheTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val teraz = 1_800_000_000_000L

    private fun plik(sciezka: String, wiekMs: Long): File =
        File(tempFolder.root, sciezka).apply {
            parentFile?.mkdirs()
            writeText("x")
            setLastModified(teraz - wiekMs)
        }

    @Test
    fun `kasuje wczorajsze wycinki i zdjecia z aparatu`() {
        val stary = plik("crop_1.jpg", MAX_AGE_MS + 1000)
        val stareZdjecie = plik("camera_images/photo_1.jpg", MAX_AGE_MS + 1000)

        pruneImageCache(tempFolder.root, teraz)

        assertFalse("stary wycinek zostal", stary.exists())
        assertFalse("stare zdjecie zostalo", stareZdjecie.exists())
    }

    @Test
    fun `zostawia pliki z biezacej sesji`() {
        val swiezy = plik("crop_2.jpg", 5_000)
        val swiezeZdjecie = plik("camera_images/photo_2.jpg", 5_000)

        pruneImageCache(tempFolder.root, teraz)

        assertTrue("swiezy wycinek skasowany", swiezy.exists())
        assertTrue("swieze zdjecie skasowane", swiezeZdjecie.exists())
    }

    @Test
    fun `nie rusza cudzych plikow w cache`() {
        val obcy = plik("http_cache_entry.0", MAX_AGE_MS * 10)

        pruneImageCache(tempFolder.root, teraz)

        assertTrue("skasowano plik spoza szukania po zdjeciu", obcy.exists())
    }

    @Test
    fun `dziala, gdy katalog aparatu jeszcze nie istnieje`() {
        pruneImageCache(tempFolder.root, teraz)
    }

    @Test
    fun `nazwy plikow nie zderzaja sie miedzy wycinkiem a aparatem`() {
        val wycinek = newCropFile(tempFolder.root, teraz)
        val zdjecie = newCameraFile(tempFolder.root, teraz)

        assertTrue("katalog aparatu powinien powstac", zdjecie.parentFile!!.isDirectory)
        assertFalse("sciezki nie moga byc te same", wycinek.absolutePath == zdjecie.absolutePath)
    }
}
