package eu.kanade.tachiyomi.multisrc.comicgamma

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayOutputStream

class PtImgInterceptor : Interceptor {

    private val json: Json by injectLazy()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val url = request.url.toString()
        if (!url.endsWith(".ptimg.json")) return response

        val metadata = json.decodeFromString<PtImg>(response.body!!.string())
        val imgRequest = request.newBuilder()
            .url(url.replaceAfterLast('/', metadata.getFilename())).build()
        val imgResponse = chain.proceed(imgRequest)
        val image = BitmapFactory.decodeStream(imgResponse.body!!.byteStream())
        val (width, height) = metadata.getViewSize()
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val view = Canvas(result)

        metadata.getTranslations().forEach {
            val src = Rect(it.ix, it.iy, it.ix + it.w, it.iy + it.h)
            val dst = Rect(it.vx, it.vy, it.vx + it.w, it.vy + it.h)
            view.drawBitmap(image, src, dst, null)
        }

        val output = ByteArrayOutputStream()
        result.compress(Bitmap.CompressFormat.JPEG, 90, output)
        val responseBody = output.toByteArray().toResponseBody("image/jpeg".toMediaType())
        return imgResponse.newBuilder().body(responseBody).build()
    }
}
