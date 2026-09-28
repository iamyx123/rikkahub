package me.rerere.rikkahub.ui.components.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TakePicButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private var screenshots = 0
    private var photos = 0
    private var configurations = 0

    private fun showButton(enabled: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                TakePicButton(
                    onCaptureScreenshot = { screenshots++ },
                    onLaunchCamera = { photos++ },
                    onLongClick = { configurations++ },
                    enabled = enabled,
                )
            }
        }
    }

    private fun assertActions(screenshots: Int, photos: Int, configurations: Int) {
        // Let the single-tap confirmation window expire before checking for stray actions.
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle {
            assertEquals(screenshots, this.screenshots)
            assertEquals(photos, this.photos)
            assertEquals(configurations, this.configurations)
        }
    }

    @Test
    fun singleTapOnlyCapturesScreenshot() {
        showButton()
        compose.onNodeWithText("截图 / 拍照").performTouchInput { click() }
        assertActions(screenshots = 1, photos = 0, configurations = 0)
    }

    @Test
    fun doubleTapOpensCameraWithoutStartingScreenshot() {
        showButton()
        compose.onNodeWithText("截图 / 拍照").performTouchInput { doubleClick() }
        assertActions(screenshots = 0, photos = 1, configurations = 0)
    }

    @Test
    fun longPressOnlyOpensScreenshotConfiguration() {
        showButton()
        compose.onNodeWithText("截图 / 拍照").performTouchInput { longClick() }
        assertActions(screenshots = 0, photos = 0, configurations = 1)
    }

    @Test
    fun separatedTapsRemainIndependentScreenshots() {
        showButton()
        compose.onNodeWithText("截图 / 拍照").performTouchInput { click() }
        assertActions(screenshots = 1, photos = 0, configurations = 0)
        compose.onNodeWithText("截图 / 拍照").performTouchInput { click() }
        assertActions(screenshots = 2, photos = 0, configurations = 0)
    }

    @Test
    fun busyButtonDoesNotStartAnotherCapture() {
        showButton(enabled = false)
        compose.onNodeWithText("截图 / 拍照").performTouchInput { doubleClick() }
        compose.onNodeWithText("截图 / 拍照").performTouchInput { longClick() }
        assertActions(screenshots = 0, photos = 0, configurations = 0)
    }
}
