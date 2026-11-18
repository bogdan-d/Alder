package dev.alder

import androidx.media3.exoplayer.DefaultLoadControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BufferSizeTest {
    private val mb = 1024L * 1024

    @Test fun largeHeapGets200Mb() = assertEquals((200 * mb).toInt(), bufferBytesFor(512 * mb))

    @Test fun middleHeapKeeps250MbFree() = assertEquals((134 * mb).toInt(), bufferBytesFor(384 * mb))

    @Test fun smallHeapKeepsMedia3Limit() {
        assertNull(bufferBytesFor(192 * mb))
        assertNull(bufferBytesFor(250 * mb + DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE))
    }
}
