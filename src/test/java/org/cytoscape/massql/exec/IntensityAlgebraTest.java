package org.cytoscape.massql.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.io.ScanView;
import org.cytoscape.massql.io.SpectraFile;
import org.cytoscape.massql.io.SpectraStream;
import org.junit.jupiter.api.Test;

/**
 * The intensity-algebra properties ported from {@code oracle/test_query_py_reference.py}.
 *
 * <p>the condition filters called these *"pure profit — they need no reference data"*. Half right: the properties are
 * self-referential, but <b>the tests as written need two fixtures we do not have</b>
 * ({@code featurelist_pos.mgf}, {@code GNPS00002_A3_p.mzML} — MassQL's own test data, verified absent). So
 * they are reconstructed here on our fixtures rather than copied.
 *
 * <p><b>Two of the three properties have preconditions the spec did not mention</b> (h):
 *
 * <ul>
 *   <li><b>Disjointness of {@code >} and {@code <} is NOT general.</b> Under scan-level semantics a scan may
 *       hold one peak above the threshold and another below it, putting it in <i>both</i> sets — correctly.
 *       The reference test avoids this with {@code TOLERANCEMZ=0.01}, narrow enough that at most one peak per
 *       scan falls in the window. That precondition is constructed explicitly below.</li>
 *   <li><b>There is no "tripartite partition".</b> the condition filters described {@code <} ∪ {@code =} ∪ {@code >} as
 *       "covering everything exactly once". That is impossible: {@code =} means {@code >=}, which contains
 *       {@code >} by construction. The reference test asserts the real relationship — {@code >} ⊆ {@code =} —
 *       and that is what is asserted here.</li>
 * </ul>
 *
 * <p>Monotonicity is general and needs no precondition.
 */
class IntensityAlgebraTest {

    private static Path resource(String relative) {
        var url = IntensityAlgebraTest.class.getClassLoader().getResource(relative);
        if (url == null) throw new AssertionError("fixture missing: " + relative);
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    /** Scan ids qualifying for a query over a fixture. */
    private static Set<Integer> scans(String queryText, String fixture) {
        Set<Integer> out = new LinkedHashSet<>();
        try (SpectraStream s = SpectraFile.open(resource(fixture))) {
            QueryExecutor.execute(
                    Massql.parse(queryText), s, null, (v, scan, ms1) -> out.add(v.scanId()));
        }
        return out;
    }

    /**
     * The narrow-window base query. {@code TOLERANCEMZ=0.01} around 200.5 admits <b>exactly one</b> peak in
     * any micro scan that has one — which is the precondition disjointness needs.
     */
    private static String narrow(String qualifier) {
        return "QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.5:TOLERANCEMZ=0.01" + qualifier;
    }

    private static final String FIXTURE = "fixtures/micro/micro.mzML";

    @Test
    void thePreconditionForDisjointnessActuallyHolds() {
        // Assert the precondition rather than assuming it -- otherwise the disjointness test below
        // could
        // pass or fail for reasons unrelated to the algebra. micro scans 1 and 3 each have exactly
        // one peak
        // at 200.5, and the 0.01 window admits nothing else.
        try (SpectraStream s = SpectraFile.open(resource(FIXTURE))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.msLevel() != 2 || v.peakCount() == 0) continue;
                var t = v.materialize();
                int inWindow = t.mzWindowExclusive(0, 200.5 - 0.01, 200.5 + 0.01).size();
                assertTrue(
                        inWindow <= 1,
                        "scan "
                                + v.scanId()
                                + " has "
                                + inWindow
                                + " peaks in the 0.01 window; "
                                + "disjointness of > and < requires at most one, because a scan with peaks "
                                + "on both sides of the threshold legitimately belongs to BOTH sets");
            }
        }
    }

    @Test
    void greaterThanAndLessThanAreDisjointUnderTheNarrowWindow() {
        Set<Integer> gt = scans(narrow(":INTENSITYVALUE>1000"), FIXTURE);
        Set<Integer> lt = scans(narrow(":INTENSITYVALUE<1000"), FIXTURE);

        Set<Integer> both = new LinkedHashSet<>(gt);
        both.retainAll(lt);
        assertTrue(both.isEmpty(), "> and < at the same threshold must not share a scan: " + both);
        assertFalse(
                gt.isEmpty() && lt.isEmpty(),
                "at least one side should be non-empty, or this is vacuous");
    }

    @Test
    void equalsIsASupersetOfGreaterThanBecauseEqualsMeansGreaterOrEqual() {
        // THE property that directly encodes "= means >=", and it is general -- no precondition.
        // If "=" were true equality, this would fail for any threshold no peak hits exactly.
        for (double threshold : new double[] {100, 1000, 1500, 4096}) {
            Set<Integer> gt = scans(narrow(":INTENSITYVALUE>" + threshold), FIXTURE);
            Set<Integer> eq = scans(narrow(":INTENSITYVALUE=" + threshold), FIXTURE);
            assertTrue(
                    eq.containsAll(gt),
                    "at threshold "
                            + threshold
                            + ": INTENSITYVALUE= (>=) must contain every scan matched "
                            + "by INTENSITYVALUE>. gt="
                            + gt
                            + " eq="
                            + eq);
        }
    }

    @Test
    void raisingAGreaterThanThresholdNeverAddsScans() {
        // Monotonicity, general. A wrong comparator direction shows up here even when the absolute
        // counts
        // look plausible.
        Set<Integer> prev = null;
        for (double threshold : new double[] {0, 100, 1000, 1400, 100_000}) {
            Set<Integer> cur = scans(narrow(":INTENSITYVALUE>" + threshold), FIXTURE);
            if (prev != null) {
                assertTrue(
                        prev.containsAll(cur),
                        "raising the threshold to "
                                + threshold
                                + " ADDED scans: was "
                                + prev
                                + ", now "
                                + cur);
            }
            prev = cur;
        }
        assertEquals(Set.of(), prev, "an absurd threshold must match nothing");
    }

    @Test
    void loweringALessThanCapNeverAddsScans() {
        Set<Integer> prev = null;
        for (double cap : new double[] {100_000, 1600, 1000, 100, 0}) {
            Set<Integer> cur = scans(narrow(":INTENSITYVALUE<" + cap), FIXTURE);
            if (prev != null) {
                assertTrue(
                        prev.containsAll(cur),
                        "lowering the cap to "
                                + cap
                                + " ADDED scans: was "
                                + prev
                                + ", now "
                                + cur);
            }
            prev = cur;
        }
        assertEquals(
                Set.of(),
                prev,
                "a cap of 0 must match nothing -- the implicit floor is > 0 anyway");
    }

    @Test
    void noPercentThresholdCanBeMadeImpossibleByRaisingIt() {
        // An instructive consequence of the 0.99 cap, and one I got wrong when first writing this
        // test:
        // ANY ">" threshold on a percent column is clamped to 0.99, so raising it cannot make the
        // query
        // unsatisfiable. INTENSITYPERCENT>100000 still matches, because the threshold becomes 0.99
        // and
        // iNorm's maximum is exactly 1.0.
        //
        // That is the cap's whole purpose (the source's comment: "if people set it to 100, then
        // they won't
        // get anything"), and it means monotonicity FLATTENS above 99 rather than continuing.
        assertEquals(
                Set.of(1, 3),
                scans(narrow(":INTENSITYPERCENT>100000"), FIXTURE),
                "the cap clamps the threshold to 0.99, so this is satisfiable -- raising a percent "
                        + "threshold is not a way to match nothing");
        assertEquals(
                scans(narrow(":INTENSITYPERCENT>100"), FIXTURE),
                scans(narrow(":INTENSITYPERCENT>100000"), FIXTURE),
                "above the cap every threshold behaves identically");
    }

    @Test
    void anAbsoluteThresholdCanBeMadeImpossible() {
        // INTENSITYVALUE has scale 1.0 and is never capped, so raising it DOES eventually match
        // nothing --
        // the contrast with the percent columns above.
        assertEquals(Set.of(), scans(narrow(":INTENSITYVALUE>1000000000"), FIXTURE));
    }

    @Test
    void aWindowThatContainsNoPeakIsEmptyAndDiagnosed() {
        // A tight tolerance around an EXACT peak still matches -- the peak sits at the window's
        // CENTRE, not
        // its bound. To match nothing the TARGET must miss every peak, which is what this does:
        // 200.6 is
        // 0.1 away from the nearest peak and the window is ~2e-7 wide.
        String q = "QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.6:TOLERANCEPPM=0.001";
        try (SpectraStream s = SpectraFile.open(resource(FIXTURE))) {
            ExecutionSummary sum =
                    QueryExecutor.execute(Massql.parse(q), s, null, (v, scan, ms1) -> {});
            assertEquals(0, sum.qualifyingScans(), "no peak lies within 2e-7 of 200.6");
            assertFalse(
                    sum.diagnostics().isEmpty(), "and it says so rather than returning silently");
        }
    }

    @Test
    void aTightToleranceAroundAnExactPeakStillMatches() {
        // The complement, so the test above cannot pass for the wrong reason: strict bounds exclude
        // the
        // EDGES, not the centre, so an exact hit matches however tight the window.
        String q = "QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.5:TOLERANCEPPM=0.0000001";
        try (SpectraStream s = SpectraFile.open(resource(FIXTURE))) {
            ExecutionSummary sum =
                    QueryExecutor.execute(Massql.parse(q), s, null, (v, scan, ms1) -> {});
            assertEquals(
                    2,
                    sum.qualifyingScans(),
                    "an exact hit matches however tight the window, because the peak is at the centre");
        }
    }
}
