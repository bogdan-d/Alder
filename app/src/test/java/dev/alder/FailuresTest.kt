package dev.alder

import androidx.media3.common.PlaybackException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException

class FailuresTest {
    @Test fun failedOrPendingSearchCanNeverReportNoMatches() {
        assertNull(searchFailure(emptyList(), completed = 2, total = 2))
        assertEquals(Failure.TIMEOUT, searchFailure(emptyList(), completed = 1, total = 2))
        assertEquals(Failure.ACCESS, searchFailure(listOf(Failure.ACCESS), completed = 2, total = 2))
    }

    @Test fun classifyKnownFailuresWithoutUsingExceptionText() {
        assertEquals(Failure.SIGN_IN, failureOf(Stremio.ApiException(Stremio.NO_SESSION, "secret")))
        assertEquals(Failure.TIMEOUT, failureOf(SocketTimeoutException("secret")))
        assertEquals(Failure.BUSY, failureOf(HttpError(429)))
        assertEquals(Failure.FORMAT, playbackFailure(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, null))
        assertEquals("error", failureReason(IllegalStateException("https://example.org/key")))
    }

    @Test fun offlineProgressPreservesLibraryFieldsAndResumesFromTheLocalCheckpoint() {
        val remote = JSONObject("""{"_id":"tt1","type":"movie","_mtime":"2026-11-13T10:00:00.000Z","removed":false,
            "state":{"video_id":"tt1","timeOffset":4000,"duration":100000,"timeWatched":4000,"overallTimeWatched":5000,"watched":"existing"}}""")
        val checkpoint = JSONObject("""{"_id":"tt1","type":"movie","_mtime":"2026-11-13T10:01:00.000Z","removed":true,
            "alderProvisional":true,"state":{"video_id":"tt1","timeOffset":12000,"duration":100000,"timeWatched":8000,"overallTimeWatched":8000}}""")
        assertTrue(newerProgress(checkpoint, remote))
        assertFalse(newerProgress(remote, checkpoint))
        val merged = applyProgress(checkpoint, remote)
        assertTrue(Library.inList(merged))
        assertEquals(12_000L, Library.resumeAt(merged, "tt1"))
        assertEquals(13_000L, merged.state.getLong("overallTimeWatched"))
        assertEquals("existing", merged.state.getString("watched"))
        assertFalse(merged.has("alderProvisional"))
        assertEquals(4000L, Library.resumeAt(remote, "tt1"))
    }
}
