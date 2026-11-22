package dev.buyolitsez.b2player

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryTest {
    @Test fun oldAndNewLinesMergeKeepingTheDevice() {
        val old = "1\ta/x.flac\n2\ta/y.flac\n"                       // before the device was kept
        val bucket = "2\ta/y.flac\tphone\n3\ta/z.flac\tmac\n"
        val merged = mergeHistory(parseHistory(old), parseHistory(bucket))
        assertEquals("1\ta/x.flac\n2\ta/y.flac\tphone\n3\ta/z.flac\tmac\n", formatHistory(merged))
    }
}
