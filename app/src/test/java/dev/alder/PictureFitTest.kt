package dev.alder

import androidx.media3.ui.AspectRatioFrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test

class PictureFitTest {
    @Test fun eachChoiceHasItsResizeMode() {
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, resizeModeFor("fit"))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, resizeModeFor("zoom"))
        assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FILL, resizeModeFor("stretch"))
    }

    @Test fun unknownValueFits() = assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, resizeModeFor("something old"))
}
