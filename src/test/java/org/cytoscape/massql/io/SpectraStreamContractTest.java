package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SpectraStreamContractTest {
    private static final String MGF = "fixtures/micro/micro.mgf";

    private static final String MZML = "fixtures/micro/micro.mzML";
    private static final String MZXML = "fixtures/micro/micro.mzXML";

    private static Path fixture(String name) {
        return Fixtures.require(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {MGF, MZML, MZXML})
    void hasNextIsRepeatableAndConsumesNothing(String f) {
        List<Integer> once = new ArrayList<>();
        try (SpectraStream s = SpectraFile.open(fixture(f))) {
            while (s.hasNext()) once.add(s.next().scanId());
        }

        List<Integer> withExtraPeeks = new ArrayList<>();
        try (SpectraStream s = SpectraFile.open(fixture(f))) {
            while (true) {
                boolean a = s.hasNext();
                for (int i = 0; i < 4; i++) {
                    assertEquals(a, s.hasNext(), "hasNext() changed its answer without an advance");
                }
                if (!a) break;
                withExtraPeeks.add(s.next().scanId());
            }
        }

        assertEquals(once, withExtraPeeks, "peeking dropped or duplicated scans in " + f);
        assertFalse(once.isEmpty(), "fixture " + f + " produced no scans, so this proves nothing");
    }

    @ParameterizedTest
    @ValueSource(strings = {MGF, MZML, MZXML})
    void nextWithoutCallingHasNextFirstStillWorks(String f) {
        try (SpectraStream s = SpectraFile.open(fixture(f))) {
            assertNotNull(s.next());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {MGF, MZML, MZXML})
    void nextPastTheEndThrowsNoSuchElement(String f) {
        try (SpectraStream s = SpectraFile.open(fixture(f))) {
            while (s.hasNext()) s.next();
            assertThrows(
                    NoSuchElementException.class,
                    s::next,
                    "reading past the end must fail, not re-serve the last scan");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {MGF, MZML, MZXML})
    void aDrainedStreamStaysDrained(String f) {
        try (SpectraStream s = SpectraFile.open(fixture(f))) {
            while (s.hasNext()) s.next();
            for (int i = 0; i < 5; i++) {
                assertFalse(s.hasNext(), "a drained stream must not revive on call " + i);
            }
            assertThrows(NoSuchElementException.class, s::next);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {MGF, MZML, MZXML})
    void eachNextReturnsAnIndependentValue(String f) {
        try (SpectraStream s = SpectraFile.open(fixture(f))) {
            assertTrue(s.hasNext());
            ScanView first = s.next();
            if (!s.hasNext()) return;
            ScanView second = s.next();
            assertNotSame(first, second);
            assertNotEquals(first.scanId(), second.scanId());
        }
    }

    @Test
    void aRetainedViewKeepsItsOwnScanAcrossAdvances() {
        try (SpectraStream s = SpectraFile.open(fixture(MZML))) {
            ScanView v = s.next();
            int firstId = v.scanId();
            int firstPeaks = v.peaks().rowCount();
            assertTrue(s.hasNext());
            s.next();
            assertEquals(firstId, v.scanId(), "a retained view is a value, not a cursor");
            assertEquals(firstPeaks, v.peaks().rowCount());
        }
    }

    @Test
    void spectraStreamIsDeliberatelyNotAnIterator() {
        assertFalse(
                Iterator.class.isAssignableFrom(SpectraStream.class),
                "SpectraStream must not be an Iterator -- see the interface javadoc for why");
        assertFalse(
                Iterable.class.isAssignableFrom(SpectraStream.class),
                "and not Iterable either, which would enable for-each and the same collect hazard");
    }

    @ParameterizedTest
    @ValueSource(strings = {MGF, MZML, MZXML})
    void usingAClosedStreamFailsRatherThanReturningNothing(String f) {
        SpectraStream s = SpectraFile.open(fixture(f));
        s.close();
        assertThrows(
                org.cytoscape.massql.MassqlException.class,
                s::hasNext,
                "a closed stream reporting 'no more scans' would read as an empty file");
    }
}
