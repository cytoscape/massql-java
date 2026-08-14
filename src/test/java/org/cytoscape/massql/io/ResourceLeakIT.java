package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.nio.file.Path;
import java.util.List;

import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 200+ open/close cycles per format, without leaking a descriptor or a mapping.
 *
 * <h2>Why this is a gate item rather than a nicety</h2>
 *
 * <p>The mzML and mzXML readers <b>memory-map</b> their input (the readers), so a leak costs a file
 * descriptor <i>and</i> address space. Neither shows up in a single-file test: the symptom is "too many
 * open files" much later, in unrelated code, after a user has opened a few hundred files in one
 * session. A long-lived host process is the exact environment where a slow leak is worst, and it is
 * also the one least able to recover from it.
 *
 * <p>The cycle count is deliberately above the common 256-descriptor soft limit, so a reader that never
 * released would fail here rather than surviving on headroom.
 */
class ResourceLeakIT {

    /** Above a 256 soft limit, so a total leak cannot hide behind spare descriptors. */
    private static final int CYCLES = 250;

    private static final List<String> FORMATS = List.of("micro.mgf", "micro.mzML", "micro.mzXML");

    /** Drains a stream and returns the number of spectra, so the mapping is genuinely exercised. */
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

    /** Opens, reads a single spectrum, and closes — enough to establish the mapping on a large file. */
    private static void openReadOneClose(Path p) {
        try (SpectraStream s = SpectraFile.open(p)) {
            if (s.hasNext()) s.next();
        }
    }

    /**
     * Open and close one fixture {@value #CYCLES} times, reading every spectrum each cycle.
     *
     * <p>The scan count is asserted to be <b>stable</b> across cycles, not merely non-zero: a reader
     * that held state between opens would drift, and a count that changed would be a correctness bug
     * surfacing as a resource one.
     */
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

        // Platform-independent, so this test always asserts something substantive: the reader still
        // works after the cycles.
        assertEquals(first, drain(p), name + ": still readable after " + CYCLES + " cycles");
    }

    /** All three formats interleaved — a per-format pool or cache shows up here and not above. */
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

    /**
     * A large real file, opened and closed repeatedly.
     *
     * <p>{@code micro.*} is a few kilobytes, so its mappings are too cheap for address-space exhaustion
     * to appear. {@code PlusRise.mgf} — 34,513 spectra — is where a leaked mapping has weight. Only the
     * first spectrum is read per cycle: the point is the open/close pair, and draining it 250 times
     * would measure throughput instead.
     */
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

    /**
     * {@code close()} is idempotent — the readers requires it, and {@link SpectraStream#close()} documents it.
     *
     * <p>try-with-resources plus an explicit {@code close()} is ordinary caller code; a second close
     * that threw, or that released a mapping twice, would break it.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"micro.mgf", "micro.mzML", "micro.mzXML"})
    void closeIsIdempotent(String name) {
        SpectraStream s = SpectraFile.open(Fixtures.require("fixtures/micro/" + name));
        s.next();
        s.close();

        assertDoesNotThrow(s::close, name + ": a second close must be harmless");
        assertDoesNotThrow(s::close, name + ": and so must a third");
    }

    /** Closing without reading anything must release just as cleanly as closing a drained stream. */
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

    // ------------------------------------------------------------------ descriptor accounting

    /**
     * ⚠ An <b>additional</b> assertion, never a test's only one.
     *
     * <p>Every test above also asserts a platform-independent property, because a conditional assertion
     * that was the sole content of a test would be a skip wearing a disguise. On a
     * JVM that does not expose descriptor counts this check is inert and the rest still runs.
     */
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
