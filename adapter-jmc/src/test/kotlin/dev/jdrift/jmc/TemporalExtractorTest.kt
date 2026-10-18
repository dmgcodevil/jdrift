package dev.jdrift.jmc

import jdk.jfr.Event
import jdk.jfr.Name
import jdk.jfr.Recording
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit
import java.nio.file.Path

class TemporalExtractorTest {
    @Name("dev.jdrift.TestMarker")
    class Marker : Event()

    @TempDir lateinit var directory: Path

    @Test fun `missing CPU observations remain unavailable and extraction is repeatable`() {
        val path = directory.resolve("markers.jfr")
        Recording().use { recording ->
            recording.enable(Marker::class.java)
            recording.start()
            Marker().commit()
            Thread.sleep(30)
            Marker().commit()
            recording.stop()
            recording.dump(path)
        }
        val events = JfrLoaderToolkit.loadEvents(path.toFile())
        val first = TemporalExtractor().extract(events, "test-hash")
        assertEquals(first, TemporalExtractor().extract(events, "test-hash"))
        assertTrue(first.windows.isNotEmpty())
        assertTrue(first.windows.all { it.meanRecordedJvmCpuFraction == null && it.cpuObservationCount == 0L })
        assertTrue(first.methods.isEmpty())
        assertTrue(first.limitations.any { it.contains("does not prove idle") })
    }
}
