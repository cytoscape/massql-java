package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.MassqlOptions;
import org.cytoscape.massql.result.ScanInfoResult;
import org.cytoscape.massql.testsupport.Fixtures;
import org.cytoscape.massql.testsupport.ResultComparator;
import org.junit.jupiter.api.Test;

/**
 * Layer 3 — the same data read through two different readers, compared against <i>each other</i>
 * rather than against a golden.
 *
 * <h2>Why this is worth more than its size suggests</h2>
 *
 * <p>Every golden in {@code DifferentialIT} came through the same loader, so a bug present in
 * both our reader and MassQL's would agree with itself and pass. Comparing two <b>formats</b> of the
 * same data has no such blind spot: a byte-order slip, an interleaving error or an off-by-one in one
 * decoder shows up as a disagreement no per-format golden could reveal
 * Together they are stronger than any single golden.
 *
 * <p>The two pairs test opposite things, and both are needed:
 *
 * <ul>
 *   <li><b>Pair A</b> — the same file converted between formats, so the rows must be <b>identical</b>.
 *   <li><b>Pair B</b> — the same experiment in two formats that genuinely carry different metadata, so
 *       the rows must <b>differ in exactly the predicted way</b>. Asserting "they agree" there would be
 *       wrong; asserting nothing would let a real regression hide behind an expected difference.
 * </ul>
 */
class CrossFormatEquivalenceIT {

    // ================================================================= Pair A — must be identical

    /**
     * {@code small.mzML} vs {@code small.mzXML}: same data, same query, identical rows.
     *
     * <p>Run at <b>60 ppm</b>, not the default 20. At 20 ppm only 2 of the 6 rows populate
     * {@code ms1_i}/{@code ms1_precmz}, so four rows would compare null-against-null and the
     * precursor-lookup path — the part of the pipeline this pair is best placed to check — would be
     * exercised on a third of the data. At 60 ppm all six populate. The 20 ppm case runs below as
     * well, because the tolerance-miss pattern must itself agree across formats.
     *
     * <p>⚠ The sole permitted difference is {@code ms1_precmz} at <b>1e-7</b>: it is a
     * <i>measured</i> m/z read from the MS1 binary array, and the mzXML stores that array at
     * {@code precision="32"}. Measured worst case across this pair is <b>2.929e-8</b>. Every other
     * column — including {@code base_peak_mz} and all intensities — is bit-identical, because those are
     * read from the <i>MS2</i> array whose values round-trip through float32 exactly. That asymmetry is
     * the reason {@link ResultComparator}'s float32 allowance is scoped to one column instead of
     * applied across the board.
     */
    @Test
    void pairAProducesIdenticalRowsAcrossFormats() {
        for (double tolPpm : new double[] {60.0, 20.0}) {
            List<ScanInfoResult> mzml = run("data/small.mzML", "test_mzml", tolPpm);
            List<ScanInfoResult> mzxml = run("data/small.mzXML", "test_mzml", tolPpm);

            assertEquals(6, mzml.size(), "small.mzML at " + tolPpm + " ppm");
            assertEquals(6, mzxml.size(), "small.mzXML at " + tolPpm + " ppm");

            // float32Mz = true: the mzXML side is the one carrying the truncation.
            List<String> diffs =
                    ResultComparator.compare(
                            "small.mzML vs small.mzXML @" + tolPpm + "ppm", mzml, mzxml, true);

            assertTrue(
                    diffs.isEmpty(),
                    () ->
                            "⛔ CROSS-FORMAT DIVERGENCE at "
                                    + tolPpm
                                    + " ppm -- the same data read through"
                                    + " two readers disagrees:\n  "
                                    + String.join("\n  ", diffs)
                                    + "\n\nNo golden can catch this: both goldens came through the same"
                                    + " loader. Suspect the readers, not the tolerances -- unless the mzXML"
                                    + " fixture itself was altered, in which case the cause is the fixture"
                                    + " rather than the code.");
        }
    }

    /**
     * The lookup columns really are populated, so the comparison above is not null-against-null.
     *
     * <p>Without this, a regression that nulled {@code ms1_i} in <i>both</i> readers would leave Pair A
     * green — the two formats would agree perfectly on having lost the same data.
     */
    @Test
    void pairAsMs1ColumnsArePopulatedSoTheComparisonIsNotVacuous() {
        for (String fixture : List.of("data/small.mzML", "data/small.mzXML")) {
            List<ScanInfoResult> rows = run(fixture, "test_mzml", 60.0);
            for (ScanInfoResult r : rows) {
                assertNotNull(r.ms1I(), fixture + " scan " + r.scan() + ": ms1_i at 60 ppm");
                assertNotNull(
                        r.ms1Precmz(), fixture + " scan " + r.scan() + ": ms1_precmz at 60 ppm");
                assertNotNull(
                        r.ms1BasePeakI(), fixture + " scan " + r.scan() + ": ms1_base_peak_i");
            }
        }
    }

    /**
     * {@code ms1scan} is populated on both sides — the conversion did not drop {@code precursorScanNum}.
     *
     * <p>the differential requires that a degraded comparison <b>say so</b> rather than quietly compare
     * fewer columns. This asserts the undegraded state positively, so if a future conversion does drop
     * the attribute the failure names the cause instead of surfacing as a puzzling null mismatch.
     * {@code small.mzXML} carries 34 {@code precursorScanNum} attributes, one per MS2 spectrum.
     */
    @Test
    void pairAsPrecursorLinkSurvivedTheConversion() {
        for (String fixture : List.of("data/small.mzML", "data/small.mzXML")) {
            for (ScanInfoResult r : run(fixture, "test_mzml", 60.0)) {
                assertNotNull(
                        r.ms1scan(),
                        fixture
                                + " scan "
                                + r.scan()
                                + ": ms1scan is null. If this is the mzXML, a fixture missing"
                                + " precursorScanNum degrades this pair to the non-ms1 columns, and that"
                                + " is a fixture property, not a reader bug.");
            }
        }
    }

    // ============================================== Pair B — must differ in exactly the stated way

    /**
     * {@code DP00570_F02.mzxml} vs {@code DP00570_F02.mgf}: same experiment, two formats that carry
     * genuinely different metadata.
     *
     * <p>⚠ <b>Not a row-identity join.</b> The scan ids are <b>disjoint</b> — the MGF has no
     * {@code SCANS=}, so MassQL numbers its blocks by index. Joining on {@code scan} would silently
     * compare unrelated spectra. The assertion is the <b>population pattern per file</b>.
     */
    @Test
    void pairBScanIdsAreDisjointSoNoJoinIsPossible() {
        Set<Integer> mzxml = scanIds(run("data/DP00570_F02.mzxml", "test_dp00570", 20.0));
        Set<Integer> mgf = scanIds(run("data/DP00570_F02.mgf", "test_dp00570", 20.0));

        assertEquals(Set.of(2, 556, 871), mzxml, "mzXML scan ids");
        assertEquals(Set.of(370, 598), mgf, "MGF scan ids -- block index, not a scan number");

        Set<Integer> intersection = new TreeSet<>(mzxml);
        intersection.retainAll(mgf);
        assertTrue(
                intersection.isEmpty(),
                () ->
                        "scan ids overlap "
                                + intersection
                                + " -- if these files ever DO share ids, this"
                                + " test's no-join premise needs revisiting, not deleting.");
    }

    /**
     * The mzXML side: {@code ms1scan} and all three {@code ms1_*} populated on <b>every</b> row.
     *
     * <p>The file has <b>zero</b> {@code precursorScanNum} attributes, so a populated {@code ms1scan} is
     * only possible under the document-order rule. This is that rule observed end to end.
     *
     * <p>⚠ Every row, not "some rows" — a weaker assertion would pass on a reader that linked one
     * spectrum and gave up.
     */
    @Test
    void pairBsMzxmlPopulatesEveryPrecursorColumn() {
        List<ScanInfoResult> rows = run("data/DP00570_F02.mzxml", "test_dp00570", 20.0);
        assertEquals(3, rows.size());
        for (ScanInfoResult r : rows) {
            String at = "mzXML scan " + r.scan() + ": ";
            assertNotNull(
                    r.ms1scan(),
                    at
                            + "ms1scan -- document-order linkage (the mzXML reader), zero"
                            + " precursorScanNum attributes in this file");
            assertNotNull(r.ms1I(), at + "ms1_i");
            assertNotNull(r.ms1Precmz(), at + "ms1_precmz");
            assertNotNull(r.ms1BasePeakI(), at + "ms1_base_peak_i");
            assertTrue(r.ms1scan() < r.scan(), at + "the linked MS1 must precede its MS2");
        }
    }

    /**
     * The MGF side: the same columns null on <b>every</b> row, and {@code rt} exactly {@code 0.0}.
     *
     * <p>MGF carries no MS1 survey scans at all, so there is nothing to link to and nothing to look up.
     * {@code rt} is {@code 0.0} rather than null because this file has no {@code RTINSECONDS=} — a real
     * value MassQL defaults, not a missing one.
     */
    @Test
    void pairBsMgfNullsEveryPrecursorColumn() {
        List<ScanInfoResult> rows = run("data/DP00570_F02.mgf", "test_dp00570", 20.0);
        assertEquals(2, rows.size());
        for (ScanInfoResult r : rows) {
            String at = "MGF scan " + r.scan() + ": ";
            assertNull(r.ms1scan(), at + "MGF has no MS1 scans to link to");
            assertNull(r.ms1I(), at + "ms1_i");
            assertNull(r.ms1Precmz(), at + "ms1_precmz");
            assertNull(r.ms1BasePeakI(), at + "ms1_base_peak_i");
            assertEquals(0.0, r.rt(), 0.0, at + "rt is a defaulted 0.0, not a null");
        }
    }

    /**
     * ⛔ {@code charge} is a <b>predicted difference</b>, not a shared column.
     *
     * <p>The mzXML has zero {@code precursorCharge} attributes, so every raw charge is {@code 0}, which
     * the collation maps to <b>null</b>. The MGF carries real charge data, and an absent {@code CHARGE=}
     * becomes <b>1</b> rather than 0 — so MGF charge is <b>never</b> null.
     *
     * <p>A test that listed {@code charge} among the columns expected to agree would fail for an
     * entirely correct reason. Three formats have three different charge defaults,
     * surfacing where it is easiest to mistake for a bug.
     */
    @Test
    void pairBsChargeDiffersExactlyAsPredicted() {
        for (ScanInfoResult r : run("data/DP00570_F02.mzxml", "test_dp00570", 20.0)) {
            assertNull(
                    r.charge(),
                    "mzXML scan "
                            + r.scan()
                            + ": charge must be null -- this file has zero"
                            + " precursorCharge attributes, and raw 0 maps to null");
        }
        for (ScanInfoResult r : run("data/DP00570_F02.mgf", "test_dp00570", 20.0)) {
            assertNotNull(
                    r.charge(),
                    "MGF scan "
                            + r.scan()
                            + ": charge is never null in MGF -- an absent CHARGE= is 1,"
                            + " not 0");
            assertTrue(
                    r.charge() == 1 || r.charge() == 2,
                    "MGF scan " + r.scan() + ": charge was " + r.charge() + ", expected 1 or 2");
        }
    }

    /**
     * The columns that <i>do</i> agree in kind are populated on both sides.
     *
     * <p>Pins the other half of the population table: without it, "the ms1 columns are null on the MGF"
     * would also hold for a reader that returned nothing useful at all.
     */
    @Test
    void pairBsSharedColumnsArePopulatedOnBothSides() {
        for (String fixture : List.of("data/DP00570_F02.mzxml", "data/DP00570_F02.mgf")) {
            for (ScanInfoResult r : run(fixture, "test_dp00570", 20.0)) {
                String at = fixture + " scan " + r.scan() + ": ";
                assertNotNull(r.precmz(), at + "precmz");
                assertNotNull(r.rt(), at + "rt");
                assertNotNull(r.tic(), at + "tic");
                assertNotNull(r.basePeakI(), at + "base_peak_i");
                assertNotNull(r.basePeakMz(), at + "base_peak_mz");
                assertEquals(2, r.mslevel(), at + "this query selects MS2DATA");
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Set<Integer> scanIds(List<ScanInfoResult> rows) {
        Set<Integer> out = new LinkedHashSet<>();
        for (ScanInfoResult r : rows) out.add(r.scan());
        return out;
    }

    /** In {@code …massql.io}, so {@code Fixtures.require} is directly reachable — no local lookup. */
    private static List<ScanInfoResult> run(String fixture, String query, double tolPpm) {
        return Massql.run(
                queryText(query),
                Fixtures.require(fixture),
                MassqlOptions.defaults().withPrecursorTolPpm(tolPpm));
    }

    private static String queryText(String name) {
        Path p = Fixtures.require("goldens/queries/" + name + ".massql");
        try {
            return Files.readString(p).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
