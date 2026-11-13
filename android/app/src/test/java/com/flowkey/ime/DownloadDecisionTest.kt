package com.flowkey.ime

import com.flowkey.ime.DownloadDecision.After
import com.flowkey.ime.DownloadDecision.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadDecisionTest {

    @Test fun `before the request`() {
        assertEquals(Before.REQUEST, DownloadDecision.beforeRequest(0, 100))
        assertEquals(Before.REQUEST, DownloadDecision.beforeRequest(40, 100))
        assertEquals(Before.VERIFY, DownloadDecision.beforeRequest(100, 100))
        assertEquals(Before.DISCARD, DownloadDecision.beforeRequest(101, 100))
    }

    @Test fun `resume only when the server continues exactly at the part length`() {
        assertEquals(After.APPEND, DownloadDecision.onResponse(206, 40, 40))
        assertEquals(After.TRUNCATE, DownloadDecision.onResponse(206, 40, 0))
        assertEquals(After.TRUNCATE, DownloadDecision.onResponse(206, 40, null))
        assertEquals(After.TRUNCATE, DownloadDecision.onResponse(200, 40, null))
        assertEquals(After.TRUNCATE, DownloadDecision.onResponse(200, 0, null))
    }

    @Test fun `range not satisfiable and errors`() {
        assertEquals(After.VERIFY, DownloadDecision.onResponse(416, 40, null))
        assertEquals(After.FAIL, DownloadDecision.onResponse(416, 0, null))
        assertEquals(After.FAIL, DownloadDecision.onResponse(404, 0, null))
        assertEquals(After.FAIL, DownloadDecision.onResponse(500, 40, null))
    }

    @Test fun `content range header`() {
        assertEquals(1000L, DownloadDecision.contentRangeStart("bytes 1000-2999/3000"))
        assertEquals(0L, DownloadDecision.contentRangeStart("bytes 0-99/*"))
        assertNull(DownloadDecision.contentRangeStart("bytes */3000"))
    }
}
