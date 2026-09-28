package me.rerere.rikkahub.ui.components.ai

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraCaptureContractTest {
    @Test
    fun cameraReceivesFullResolutionOutputWithReadWriteGrants() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.parse("content://${context.packageName}.fileprovider/camera.jpg")
        val intent = CameraCaptureContract().createIntent(context, uri)

        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent.action)
        @Suppress("DEPRECATION")
        assertEquals(uri, intent.getParcelableExtra<Uri>(MediaStore.EXTRA_OUTPUT))
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
    }

    @Test
    fun cancellationIsNotImportedAsAPhoto() {
        val contract = CameraCaptureContract()
        assertFalse(contract.parseResult(Activity.RESULT_CANCELED, null))
        assertTrue(contract.parseResult(Activity.RESULT_OK, null))
    }
}
