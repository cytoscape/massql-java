package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

/**
 * The {@link SpectraStream} interface contract, asserted against <b>all three</b> readers.
 *
 * <p>They are independent {@code final class}es with no shared base, so the peek state machine is
 * written out three times — which means it can be got wrong three times. Everything here is
 * parameterized over one fixture per format rather than testing whichever reader came to hand.
 *
 * <p>Added with the {@code hasNext()}/{@code next()} refactor. The old shape was
 * {@code next()} returning a boolean plus {@code current()}; the guarantees below either did not exist
 * or could not be stated:
 *
 * <ul>
 *   <li>{@code hasNext()} is <b>repeatable</b> — the classic peek bug is a second call silently
 *       swallowing a scan.</li>
 *   <li>{@code next()} past the end <b>throws</b> instead of handing back the last scan again.</li>
 *   <li>A drained stream <b>stays</b> drained, so reusing one fails loudly rather than looking like a
 *       query that matched nothing.</li>
 * </ul>
 */
class SpectraStreamContractTest {

    /** One fixture per format — the three readers, not just whichever is convenient. */
    private static final String MGF = "fixtures/micro/micro.mgf";

    private static final String MZML = "fixtures/micro/micro.mzML";
    private static final String MZXML = "fixtures/micro/micro.mzXML";

    private static Path fixture(String name) {
        return Fixtures.require(name);
    }

    // ---------------------------------------------------------------- hasNext() is repeatable

    @ParameterizedTest
    @ValueSource(strings = {MGF, MZML, MZXML})
    void hasNextIsRepeatableAndConsumesNothing(String f) {
        // Call hasNext() FIVE times between every advance. If it consumed, the scan list would come
        // back short -- which is exactly how a peek state machine fails, and silently.
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
        // hasNext() is a convenience, not a required handshake -- next() advances on its own.
        try (SpectraStream s = SpectraFile.open(fixture(f))) {
            assertNotNull(s.next());
        }
    }

    // ---------------------------------------------------------------- draining

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
        // The property that makes a reused stream a loud failure rather than a silent empty result:
        // hasNext() is false PERMANENTLY, so a second query over the same stream cannot look like
        // "matched nothing".
        try (SpectraStream s = SpectraFile.open(fixture(f))) {
            while (s.hasNext()) s.next();
            for (int i = 0; i < 5; i++) {
                assertFalse(s.hasNext(), "a drained stream must not revive on call " + i);
            }
            assertThrows(NoSuchElementException.class, s::next);
        }
    }

    // ---------------------------------------------------------------- the returned view is ONE
    // object

    @ParameterizedTest
    @ValueSource(strings = {MGF, MZML, MZXML})
    void nextReturnsTheSameInstanceEveryTime(String f) {
        // Not an accident to be fixed -- it is the design, and it is why retained memory is bounded
        // by
        // the largest single scan. Asserting it here means anyone who "improves" the readers into
        // allocating per scan has to change a test that says why not to.
        try (SpectraStream s = SpectraFile.open(fixture(f))) {
            assertTrue(s.hasNext());
            ScanView first = s.next();
            if (!s.hasNext()) return; // single-scan fixture: nothing to compare
            ScanView second = s.next();
            assertSame(
                    first,
                    second,
                    "the view is a reused cursor; a fresh instance per scan would silently multiply "
                            + "retained memory by the scan count");
        }
    }

    @Test
    void theViewIsRewoundNotCopiedSoStaleReferencesSeeTheNewScan() {
        // The consequence of the above, stated so it cannot surprise anyone: a reference held
        // across
        // an advance reports the NEW scan's data. materialize() is the supported way to retain.
        try (SpectraStream s = SpectraFile.open(fixture(MZML))) {
            ScanView v = s.next();
            int firstId = v.scanId();
            assertTrue(s.hasNext());
            s.next();
            assertNotEquals(
                    firstId,
                    v.scanId(),
                    "the same object must now report the second scan -- this is the aliasing the "
                            + "javadoc warns about, and materialize() is the way around it");
        }
    }

    // ---------------------------------------------------------------- NOT an Iterator

    @Test
    void spectraStreamIsDeliberatelyNotAnIterator() {
        // Naming only. Extending Iterator<ScanView> would make StreamSupport.stream(...).toList() a
        // legal, compiling, silently WRONG way to collect N aliases of one mutable object -- every
        // element reporting the last scan. Keeping the type outside the Iterator hierarchy makes
        // that
        // unreachable rather than merely discouraged.
        assertFalse(
                Iterator.class.isAssignableFrom(SpectraStream.class),
                "SpectraStream must not be an Iterator -- see the interface javadoc for why");
        assertFalse(
                Iterable.class.isAssignableFrom(SpectraStream.class),
                "and not Iterable either, which would enable for-each and the same collect hazard");
    }

    // ---------------------------------------------------------------- closed streams

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
