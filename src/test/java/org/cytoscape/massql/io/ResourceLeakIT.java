package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResourceLeakIT {
    private static final int CYCLES = 250;

    private static final List<String> FORMATS = List.of("micro.mgf", "micro.mzML", "micro.mzXML");

    private static int drain(Path p) {
        int n = 0;
        try (SpectraStream s = SpectraFile.open(p)) {
            while (s.hasNext()) {
                s.next();
                n++;
            }
        }
        return n;
    }

    private static void openReadOneClose(Path p) {
        try (SpectraStream s = SpectraFile.open(p)) {
            if (s.hasNext()) s.next();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"micro.mgf", "micro.mzML", "micro.mzXML"})
    void repeatedOpenAndCloseLeaksNothing(String name) {
        Path p = Fixtures.require("fixtures/micro/" + name);
        long before = openDescriptors();

        int first = drain(p);
        assertTrue(
                first > 0, name + ": the fixture must yield spectra, or this loop proves nothing");

        for (int i = 1; i < CYCLES; i++) {
            assertEquals(first, drain(p), name + ": scan count drifted on cycle " + i);
        }

        assertNoDescriptorGrowth(name, before, CYCLES);

        assertEquals(first, drain(p), name + ": still readable after " + CYCLES + " cycles");
    }

    @Test
    void interleavedFormatsAlsoRelease() {
        long before = openDescriptors();

        for (int i = 0; i < CYCLES; i++) {
            for (String name : FORMATS) {
                assertTrue(drain(Fixtures.require("fixtures/micro/" + name)) > 0, name);
            }
        }

        assertNoDescriptorGrowth("interleaved", before, CYCLES * FORMATS.size());
    }

    @Test
    void aLargeFileReleasesItsMappingToo() {
        Path big = Fixtures.require("data/PlusRise.mgf");
        long before = openDescriptors();

        for (int i = 0; i < CYCLES; i++) {
            openReadOneClose(big);
        }

        assertNoDescriptorGrowth("PlusRise.mgf", before, CYCLES);
        assertDoesNotThrow(
                () -> openReadOneClose(big), "still openable after " + CYCLES + " cycles");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"micro.mgf", "micro.mzML", "micro.mzXML"})
    void closeIsIdempotent(String name) {
        SpectraStream s = SpectraFile.open(Fixtures.require("fixtures/micro/" + name));
        s.next();
        s.close();

        assertDoesNotThrow(s::close, name + ": a second close must be harmless");
        assertDoesNotThrow(s::close, name + ": and so must a third");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"micro.mgf", "micro.mzML", "micro.mzXML"})
    void openingWithoutReadingStillReleases(String name) {
        Path p = Fixtures.require("fixtures/micro/" + name);
        long before = openDescriptors();

        for (int i = 0; i < CYCLES; i++) {
            SpectraFile.open(p).close();
        }

        assertNoDescriptorGrowth(name + " (never read)", before, CYCLES);
        assertTrue(drain(p) > 0, name + ": readable afterwards");
    }

    /**
     * The mapping, not the descriptor. Java frees a {@code MappedByteBuffer} only when the
     * collector reaches it, and Windows keeps the mapped file locked for that whole time -- so a
     * user could not delete or re-export the spectra file they had just queried. Deleting is
     * always permitted on POSIX, so this only bites on the Windows leg of the CI matrix; it is
     * cheap enough to run everywhere.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"micro.mgf", "micro.mzML", "micro.mzXML"})
    void mappedFileIsUnlockedOnceClosed(String name, @TempDir Path tmp) throws IOException {
        Path copy = tmp.resolve(name);
        Files.copy(Fixtures.require("fixtures/micro/" + name), copy);

        assertTrue(drain(copy) > 0, name + ": the copy must yield spectra, or nothing was mapped");

        assertDoesNotThrow(
                () -> Files.delete(copy),
                name + ": the spectra file is still locked after the stream was closed");
    }

    private static void assertNoDescriptorGrowth(String what, long before, int opens) {
        long after = openDescriptors();
        if (before < 0 || after < 0) return;

        assertTrue(
                after - before < 50,
                () ->
                        what
                                + ": open descriptors grew from "
                                + before
                                + " to "
                                + after
                                + " across "
                                + opens
                                + " open/close cycles. Even one leaked per open exhausts a long-lived"
                                + " host process, which is where this SDK is expected to live.");
    }

    private static long openDescriptors() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean unix) {
            return unix.getOpenFileDescriptorCount();
        }
        return -1;
    }
}
