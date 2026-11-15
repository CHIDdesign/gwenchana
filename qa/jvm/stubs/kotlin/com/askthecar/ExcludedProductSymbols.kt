// Stand-ins for the few symbols the included product files use from files the bench does NOT compile.
// Nothing here is product logic; each stand-in is the "feature absent" case.
package com.askthecar

import android.content.Context
import android.graphics.Bitmap
import java.time.ZoneId

/** Gradle-generated in the real build. Empty URLs/keys = the "-Ppublic" build: no relay, no phone sync, no Pleos keys. */
object BuildConfig {
    const val RELAY_URL = ""
    const val RELAY_TOKEN = ""
    const val SYNC_URL = ""
    const val PLEOS_CLIENT_ID = ""
    const val PLEOS_CLIENT_SECRET = ""
}

/** CarService.kt (Android Service) is not compiled; Consent only needs the class literal for stopService(). */
class CarService

/** CameraVideo.kt (Compose UI) is not compiled; Crash.kt only needs the file names. Values must match the product. */
object CameraVideo {
    const val FILE = "cameras.mp4"
    const val END_FILE = "cameras_end.txt"
}

/** Vision.kt (MediaPipe) is not compiled. No Bitmap is ever decoded on the bench, so these are unreachable. */
object VehicleFinder {
    fun nearest(ctx: Context, label: String, bmp: Bitmap): Result<Sighting?> = Result.success(null)
    fun sentence(s: Sighting): String = ""
}

/** ClaimPdf.kt (android.graphics.pdf) is not compiled; Report.kt uses the zone and a direction word. */
object ClaimText {
    val KST: ZoneId = ZoneId.of("Asia/Seoul")
    fun side(deg: Double): String = "(side ${deg.toInt()}deg)"
}

/** ClaimPdf.kt: PDF output is not available on the bench (FactSheet.write is never called by these tests). */
data class ClaimOwner(val name: String?, val phone: String?, val plate: String?, val insurer: String?, val policyNo: String?)
object ClaimPdf {
    fun write(ctx: Context, out: java.io.File, mine: HandoffData, photos: List<java.io.File> = emptyList(), owner: ClaimOwner? = null): java.io.File =
        throw UnsupportedOperationException("ClaimPdf is not available on the JVM bench")
}
