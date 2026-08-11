package edu.ucsd.idekerlab.massql.exec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

import edu.ucsd.idekerlab.massql.Massql;
import edu.ucsd.idekerlab.massql.MassqlException;
import edu.ucsd.idekerlab.massql.MassqlOptions;
import edu.ucsd.idekerlab.massql.io.ScanView;
import edu.ucsd.idekerlab.massql.io.SpectraFile;
import edu.ucsd.idekerlab.massql.io.SpectraStream;
import edu.ucsd.idekerlab.massql.result.ScanInfoResult;
import edu.ucsd.idekerlab.massql.spectra.SpectrumTable;
import edu.ucsd.idekerlab.massql.spectra.SpectrumTableBuilder;

/**
 * Collation of the 7 native + 5 computed columns, the sentinel and NaN rules, and their ORDER.
 *
 * <p>Runs both on hand-built tables (where every expected value is arithmetic you can check by eye) and
 * end-to-end over the micro fixtures against their committed goldens.
 */
class ScaninfoCollationTest {

    private static Path resource(String relative) {
        var url = ScaninfoCollationTest.class.getClassLoader().getResource(relative);
        if (url == null) throw new AssertionError("fixture missing: " + relative);
        try {
            return Paths.get(url.toURI());
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    /** Collates a query over a fixture, returning the rows. */
    private static List<ScanInfoResult> run(String queryText, String fixture, MassqlOptions opts) {
        ScaninfoCollation c = new ScaninfoCollation(opts);
        try (SpectraStream s = SpectraFile.open(resource(fixture))) {
            QueryExecutor.execute(Massql.parse(queryText), s, opts, c);
        }
        return c.rows();
    }

    private static SpectrumTable oneScan(
            int scanId,
            int msLevel,
            double rt,
            int polarity,
            double precmz,
            int ms1scan,
            int charge,
            double[] mz,
            double[] i) {
        SpectrumTableBuilder b = new SpectrumTableBuilder(msLevel);
        b.startScan(scanId, rt, polarity, precmz, ms1scan, charge);
        for (int k = 0; k < mz.length; k++) b.addPeak(mz[k], i[k]);
        return b.build();
    }

    /** A minimal ScanView over a hand-built single-scan table, for the unit-level cases. */
    private record View(SpectrumTable t) implements ScanView {
        @Override
        public int scanId() {
            return t.index().scanIdAt(0);
        }

        @Override
        public int msLevel() {
            return t.msLevel();
        }

        @Override
        public double rt() {
            return t.index().rtOf(0);
        }

        @Override
        public int polarity() {
            return t.index().polarityOf(0);
        }

        @Override
        public double precmz() {
            return t.index().precmzOf(0);
        }

        @Override
        public int ms1scan() {
            return t.index().ms1scanOf(0);
        }

        @Override
        public int charge() {
            return t.index().chargeOf(0);
        }

        @Override
        public int peakCount() {
            return t.rowCount();
        }

        @Override
        public SpectrumTable materialize() {
            return t;
        }
    }

    private static ScanInfoResult collateOne(
            SpectrumTable scan, SpectrumTable ms1, MassqlOptions opts) {
        ScaninfoCollation c = new ScaninfoCollation(opts);
        c.accept(new View(scan), scan, ms1);
        return c.rows().get(0);
    }

    // ---------------------------------------------------------------- base peaks (argmax)

    @Test
    void basePeaksComeFromTheArgmaxRowNotFromTwoSeparateMaxima() {
        // If base_peak_mz were computed as max(mz) instead of mz[argmax(i)], this returns 300.0.
        SpectrumTable t =
                oneScan(
                        1,
                        2,
                        1.0,
                        1,
                        500.0,
                        0,
                        0,
                        new double[] {100.0, 200.5, 300.0},
                        new double[] {250.0, 1500.0, 750.0});
        ScanInfoResult r = collateOne(t, null, null);
        assertEquals(1500.0, r.basePeakI());
        assertEquals(200.5, r.basePeakMz(), "the m/z must be read AT the argmax row");
    }

    @Test
    void anIntensityTieResolvesToTheFirstLowestMzRow() {
        // pandas idxmax returns the first occurrence; a last-wins loop gives 300.0 here.
        SpectrumTable t =
                oneScan(
                        1,
                        2,
                        1.0,
                        1,
                        500.0,
                        0,
                        0,
                        new double[] {100.0, 300.0},
                        new double[] {999.0, 999.0});
        assertEquals(100.0, collateOne(t, null, null).basePeakMz());
    }

    @Test
    void aSinglePeakScanIsItsOwnBasePeak() {
        SpectrumTable t =
                oneScan(
                        5,
                        2,
                        2.0,
                        1,
                        500.0,
                        2,
                        2,
                        new double[] {123.456789012345},
                        new double[] {4096.0});
        ScanInfoResult r = collateOne(t, null, null);
        assertEquals(4096.0, r.basePeakI());
        assertEquals(123.456789012345, r.basePeakMz());
        assertEquals(4096.0, r.tic(), "one peak: tic == that peak's intensity");
    }

    // ---------------------------------------------------------------- tic is a SUM

    @Test
    void ticIsTheSumOfPeakIntensitiesNotTheBasePeak() {
        // The distinction massql_query.py:154 guards: `scanmaxint` puts the base peak in `i`,
        // scaninfo
        // puts the sum. 250 + 1500 + 750 = 2500, while the base peak is 1500.
        SpectrumTable t =
                oneScan(
                        1,
                        2,
                        1.0,
                        1,
                        500.0,
                        0,
                        0,
                        new double[] {100.0, 200.5, 300.0},
                        new double[] {250.0, 1500.0, 750.0});
        ScanInfoResult r = collateOne(t, null, null);
        assertEquals(2500.0, r.tic());
        assertNotEquals(r.basePeakI(), r.tic(), "a fixture where sum and max differ, deliberately");
    }

    // ---------------------------------------------------------------- sentinels: exactly three
    // columns

    @Test
    void theThreeZeroSentinelsBecomeNullAndNothingElseDoes() {
        SpectrumTable t =
                oneScan(
                        7,
                        2,
                        0.0,
                        1, /*precmz*/
                        0.0, /*ms1scan*/
                        0, /*charge*/
                        0,
                        new double[] {100.0},
                        new double[] {5.0});
        ScanInfoResult r = collateOne(t, null, null);
        assertNull(r.precmz(), "0 precmz -> null");
        assertNull(r.ms1scan(), "0 ms1scan -> null");
        assertNull(r.charge(), "0 charge -> null");

        // ...and the columns that must NOT be converted:
        assertEquals(0.0, r.rt(), "rt 0.0 is a GENUINE retention time");
        assertNotNull(r.rt(), "explicitly: rt is not null");
        assertEquals(7, r.scan());
        assertEquals(2, r.mslevel());
        assertEquals(5.0, r.tic());
    }

    @Test
    void aGenuineNonZeroChargeSurvives() {
        SpectrumTable t =
                oneScan(5, 2, 2.0, 1, 500.0, 2, 2, new double[] {123.0}, new double[] {4096.0});
        assertEquals(2, collateOne(t, null, null).charge());
    }

    // ---------------------------------------------------------------- order of operations

    @Test
    void sentinelConversionHappensAFTERTheLookupSoMs1scanZeroDoesNotThrow() {
        // The lookup needs the RAW 0 to detect "no linked scan". If sentinels were converted first,
        // the
        // lookup would receive null (or a boxed 0) and either throw or match against scan 0.
        SpectrumTable t =
                oneScan(
                        1,
                        2,
                        0.0,
                        1,
                        250.25, /*ms1scan*/
                        0,
                        0,
                        new double[] {100.0, 200.5},
                        new double[] {250.0, 1500.0});
        ScanInfoResult r = assertDoesNotThrow(() -> collateOne(t, null, null));
        assertNull(r.ms1scan());
        assertNull(r.ms1I());
        assertNull(r.ms1Precmz());
        assertNull(r.ms1BasePeakI());
    }

    // ---------------------------------------------------------------- the all-zero-intensity scan

    @Test
    void anAllZeroIntensityScanCollatesWithoutNaNReachingTheRow() {
        // Reachable in mzML/mzXML via a scan-level-only query: only PEAK-level conditions apply the
        // implicit > 0 floor, so a POLARITY query passes such a scan straight through. mzML retains
        // zero-intensity peaks, so peakCount > 0 and the executor does not skip it.
        SpectrumTable t =
                oneScan(
                        4,
                        2,
                        1.5,
                        1,
                        500.0,
                        2,
                        0,
                        new double[] {100.0, 200.0},
                        new double[] {0.0, 0.0});
        ScanInfoResult r = collateOne(t, null, null);
        assertEquals(0.0, r.tic(), "the TIC of an all-zero scan really is zero");
        assertEquals(
                0.0, r.basePeakI(), "argmax over all-zero picks row 0, whose intensity is 0.0");
        assertEquals(100.0, r.basePeakMz(), "and its m/z, which is a real value");
        // The point: nothing here is NaN. Step 5's iNorm IS NaN for this scan, but i_norm is
        // dropped
        // from the contract entirely and never reaches a row.
        assertFalse(Double.isNaN(r.tic()) || Double.isNaN(r.basePeakI()));
    }

    // ---------------------------------------------------------------- NaN / infinity -> null

    @Test
    void nonFiniteComputedValuesBecomeNullSoTheJsonStaysValid() {
        // Mirrors massql_query.py's clean_nan + allow_nan=False. `NaN` and `Infinity` are not valid
        // JSON
        // tokens, so a non-finite value must become null BEFORE serialization -- ResultJson throws
        // if one
        // reaches it, which is the right place for a belt-and-braces guard but the wrong place to
        // fix it.
        //
        // An infinite intensity is the reachable case: a corrupt binary array can decode to one,
        // and the
        // sum then propagates it into tic.
        SpectrumTable t =
                oneScan(
                        1,
                        2,
                        1.0,
                        1,
                        500.0,
                        0,
                        0,
                        new double[] {100.0, 200.0},
                        new double[] {Double.POSITIVE_INFINITY, 5.0});
        ScanInfoResult r = collateOne(t, null, null);
        assertNull(r.tic(), "an infinite sum must be nulled, not serialized as Infinity");
        assertNull(r.basePeakI(), "argmax selects the infinite peak, so this nulls too");
        assertEquals(100.0, r.basePeakMz(), "but its m/z is finite and survives");

        // And the row serializes to valid JSON rather than throwing.
        assertDoesNotThrow(() -> edu.ucsd.idekerlab.massql.result.ResultJson.write(List.of(r)));
    }

    @Test
    void aNaNIntensityIsAlsoNulled() {
        SpectrumTable t =
                oneScan(1, 2, 1.0, 1, 500.0, 0, 0, new double[] {100.0}, new double[] {Double.NaN});
        ScanInfoResult r = collateOne(t, null, null);
        assertNull(r.tic());
        assertNull(r.basePeakI());
    }

    // ---------------------------------------------------------------- ordering assertion

    @Test
    void scansArrivingOutOfOrderAreRejectedRatherThanSilentlyMisaligned() {
        // Scan-ascending order is a property of the fixtures, not a guarantee -- an MGF with
        // non-monotonic SCANS= would break it, and the differential compares row ORDER before
        // fields, so
        // a
        // silent violation would surface as a confusing field-level diff on misaligned rows.
        ScaninfoCollation c = new ScaninfoCollation(null);
        SpectrumTable hi =
                oneScan(10, 2, 1.0, 1, 500.0, 0, 0, new double[] {1.0}, new double[] {1.0});
        SpectrumTable lo =
                oneScan(2, 2, 1.0, 1, 500.0, 0, 0, new double[] {1.0}, new double[] {1.0});
        c.accept(new View(hi), hi, null);
        MassqlException e =
                assertThrows(MassqlException.class, () -> c.accept(new View(lo), lo, null));
        assertTrue(e.getMessage().contains("out of order"), e.getMessage());
    }

    // ---------------------------------------------------------------- end-to-end against the
    // goldens

    @Test
    void theMicroGoldenIsReproducedRowForRow() {
        // output/micro_mzml_results.json: 2 rows, scans 1 and 3, values hand-computed in
        // EXPECTED.md.
        List<ScanInfoResult> rows =
                run(
                        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.5:TOLERANCEMZ=0.5",
                        "fixtures/micro/micro.mzML",
                        null);
        assertEquals(2, rows.size());
        assertEquals(List.of(1, 3), rows.stream().map(ScanInfoResult::scan).toList());

        ScanInfoResult first = rows.get(0);
        assertEquals(250.25, first.precmz());
        assertNull(first.ms1scan(), "scan 1 precedes any MS1, so the raw 0 sentinel becomes null");
        assertEquals(0.0, first.rt());
        assertNull(first.charge());
        assertEquals(1750.0, first.tic(), "250 + 1500");
        assertEquals(1500.0, first.basePeakI());
        assertEquals(200.5, first.basePeakMz());
        assertNull(first.ms1I(), "no linked MS1 scan");
        assertNull(first.ms1BasePeakI());

        ScanInfoResult second = rows.get(1);
        assertEquals(500.0, second.precmz());
        assertEquals(2, second.ms1scan());
        assertEquals(2600.0, second.tic(), "250 + 1500 + 100 + 750");
        // The precursor-lookup conflict from EXPECTED.md: closest wins over most intense.
        assertEquals(1000.0, second.ms1I());
        assertEquals(499.99609375, second.ms1Precmz());
        assertEquals(9000.0, second.ms1BasePeakI(), "max over the WHOLE MS1 scan, including 600.0");
    }

    @Test
    void anMs1dataQueryProducesTheSameShapeWithRealBasePeaksAndNullPrecursorFields() {
        // ⛔ The union shape, end to end. micro_ms1var.mzML has two MS1 scans with DIFFERENT peaks.
        List<ScanInfoResult> rows =
                run("QUERY scaninfo(MS1DATA)", "fixtures/micro/micro_ms1var.mzML", null);
        assertFalse(rows.isEmpty());
        for (ScanInfoResult r : rows) {
            assertEquals(1, r.mslevel(), "MS1DATA selects MS1 scans");
            // Null because a survey scan has no precursor -- semantics, not an artifact.
            assertNull(r.precmz());
            assertNull(r.ms1scan());
            assertNull(r.charge());
            assertNull(r.ms1I());
            assertNull(r.ms1Precmz());
            assertNull(r.ms1BasePeakI());
            // NOT null: a survey scan plainly has a base peak. This is the half that was a
            // left-join artifact in the reference wrapper.
            assertNotNull(r.basePeakI(), "an MS1 scan has a base peak");
            assertNotNull(r.basePeakMz());
            assertTrue(r.basePeakI() > 0.0);
            assertNotNull(r.tic());
            assertNotNull(r.rt());
        }
    }

    @Test
    void anMgfPopulatesChargeAsOneAndLeavesEveryMs1ColumnNull() {
        // MGF charge is never null -- CHARGE= if present, else 1. And MGF has no
        // survey
        // scans at all, so ms1scan and all three ms1_* are null for every row.
        List<ScanInfoResult> rows =
                run(
                        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.5:TOLERANCEMZ=0.5",
                        "fixtures/micro/micro.mgf",
                        null);
        // micro_mgf_results.json: scans 1 and 2 -- BLOCK indices, since MGF has no MS1 scans to
        // interleave (contrast micro.mzML's 1 and 3 for the same query).
        assertEquals(List.of(1, 2), rows.stream().map(ScanInfoResult::scan).toList());

        for (ScanInfoResult r : rows) {
            assertNotNull(r.charge(), "MGF charge is never null");
            assertEquals(1, r.charge(), "absent CHARGE= defaults to 1, not 0-then-null");
            assertNull(r.ms1scan(), "MGF has no survey scans");
            assertNull(r.ms1I());
            assertNull(r.ms1Precmz());
            assertNull(r.ms1BasePeakI());
            assertNotNull(r.rt(), "rt is never null");
        }

        // rt per row, against the golden. Only block 1 lacks RTINSECONDS -- blocks 2 and 3 carry
        // RTINSECONDS=60.0 and 120.0, which become 1.0 and 2.0 MINUTES. So this fixture exercises
        // both
        // the "absent -> 0.0, not null" rule and the seconds/60 conversion in one file.
        assertEquals(0.0, rows.get(0).rt(), "block 1 has no RTINSECONDS -> 0.0, and NOT null");
        assertEquals(1.0, rows.get(1).rt(), "RTINSECONDS=60.0 -> 1.0 minute");
    }

    @Test
    void thePrecursorToleranceIsHonouredAndIsSeparateFromTheQueryTolerance() {
        // Two runs differing ONLY in precursorTolPpm. At 1 ppm the nearest MS1 peak (3.9e-3 away,
        // ~7.8 ppm) misses, so ms1_i nulls while ms1_base_peak_i survives -- the collation at
        // the
        // collation level.
        String q = "QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.5:TOLERANCEMZ=0.5";
        var wide = run(q, "fixtures/micro/micro.mzML", MassqlOptions.defaults());
        var tight =
                run(
                        q,
                        "fixtures/micro/micro.mzML",
                        MassqlOptions.defaults().withPrecursorTolPpm(1.0));

        assertEquals(
                wide.size(),
                tight.size(),
                "the precursor tolerance must not change WHICH scans match");
        assertEquals(1000.0, wide.get(1).ms1I());
        assertNull(tight.get(1).ms1I(), "1 ppm is too tight for a 7.8 ppm offset");
        assertEquals(9000.0, tight.get(1).ms1BasePeakI(), "but the base peak survives the miss");
    }
}
