package pl.sroki.cci.android.ui.catalog.picturesearch

import java.io.File

/**
 * Pliki tymczasowe szukania po zdjęciu: zdjęcie prosto z aparatu i wynik kadrowania.
 *
 * Oba trafiają do `cacheDir`, ale system czyści cache dopiero, gdy zabraknie miejsca na dysku —
 * bez sprzątania każde szukanie zostawiało tam dwa pliki na stałe. Nie da się skasować ich
 * zaraz po użyciu, bo wskazuje na nie `selectedImageUri` aż do końca wyszukiwania, więc
 * kasujemy przy wejściu na ekran wszystko, co jest starsze niż [MAX_AGE_MS].
 */
internal const val MAX_AGE_MS = 24L * 60 * 60 * 1000

private const val CROP_PREFIX = "crop_"
private const val CAMERA_DIR = "camera_images"

internal fun newCropFile(cacheDir: File, now: Long = System.currentTimeMillis()): File =
    File(cacheDir, "$CROP_PREFIX$now.jpg")

internal fun newCameraFile(cacheDir: File, now: Long = System.currentTimeMillis()): File =
    File(cacheDir, "$CAMERA_DIR/photo_$now.jpg").also { it.parentFile?.mkdirs() }

internal fun pruneImageCache(
    cacheDir: File,
    now: Long = System.currentTimeMillis(),
    maxAgeMs: Long = MAX_AGE_MS,
) {
    val stare = { file: File -> now - file.lastModified() > maxAgeMs }

    cacheDir.listFiles { file -> file.isFile && file.name.startsWith(CROP_PREFIX) }
        ?.filter(stare)
        ?.forEach { it.delete() }

    File(cacheDir, CAMERA_DIR).listFiles { file -> file.isFile }
        ?.filter(stare)
        ?.forEach { it.delete() }
}
