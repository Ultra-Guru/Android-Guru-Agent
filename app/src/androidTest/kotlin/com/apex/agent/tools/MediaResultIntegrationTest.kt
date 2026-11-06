package com.apex.agent.tools

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apex.agent.core.tools.builtin.ImageCaptureTool
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real host + broker + Android result dispatch; only the external UI is intercepted. */
@RunWith(AndroidJUnit4::class)
class MediaResultIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun <T> external(
        action: String,
        resultCode: Int,
        data: Intent? = null,
        onLaunch: (Intent) -> Unit = {},
        block: () -> T
    ): T {
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != action) return null
                onLaunch(intent)
                return Instrumentation.ActivityResult(resultCode, data)
            }
        }
        instrumentation.addMonitor(monitor)
        return try { block() } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun selectedDocumentIsReturnedByRealHost() = runBlocking {
        val file = File(context.cacheDir, "picked.txt").apply { writeText("selected") }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        external(Intent.ACTION_OPEN_DOCUMENT, Activity.RESULT_OK, Intent().setData(uri)) {
            runBlocking {
                val result = FilePicker(context).pick("text/plain", false)
                assertEquals(listOf(uri.toString()), result.uris)
                assertEquals(listOf("picked.txt"), result.displayNames)
            }
        }
    }

    @Test fun cameraCancellationIsNotSuccess() {
        external(MediaStore.ACTION_IMAGE_CAPTURE, Activity.RESULT_CANCELED) {
            runBlocking {
                val result = CameraCapturer(context).capture(ImageCaptureTool.CaptureParams(80, null, false))
                assertFalse("Starting a camera is not a captured photo", result.success)
            }
        }
    }

    @Test fun successfulCameraRequiresReadableImageAndGrantedContentUri() {
        external(MediaStore.ACTION_IMAGE_CAPTURE, Activity.RESULT_OK, onLaunch = { intent ->
            val uri = intent.getParcelableExtra<android.net.Uri>(MediaStore.EXTRA_OUTPUT)!!
            assertEquals("content", uri.scheme)
            assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
            assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
            context.contentResolver.openOutputStream(uri)!!.use {
                Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
        }) {
            runBlocking {
                val result = CameraCapturer(context).capture(ImageCaptureTool.CaptureParams(80, null, false))
                assertTrue(result.errorMessage, result.success)
                assertTrue("Must verify real bytes, not intent launch", (result.sizeBytes ?: 0) > 0)
            }
        }
    }

    @Test fun cameraOkWithoutImageIsFailure() {
        external(MediaStore.ACTION_IMAGE_CAPTURE, Activity.RESULT_OK) {
            runBlocking {
                assertFalse(CameraCapturer(context).capture(ImageCaptureTool.CaptureParams(80, null, false)).success)
            }
        }
    }
}
