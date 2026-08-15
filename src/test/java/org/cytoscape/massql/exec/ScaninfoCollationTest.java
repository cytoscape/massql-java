package org.cytoscape.massql.exec;

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

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.MassqlOptions;
import org.cytoscape.massql.io.ScanView;
import org.cytoscape.massql.io.SpectraFile;
import org.cytoscape.massql.io.SpectraStream;
import org.cytoscape.massql.lang.ast.Polarity;
import org.cytoscape.massql.result.ScanInfoResult;
import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.spectra.SpectrumTableBuilder;
import org.junit.jupiter.api.Test;

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

    private static ScanView view(SpectrumTable t) {
        int polarity = t.index().polarityOf(0);
        double precmz = t.index().precmzOf(0);
        int ms1scan = t.index().ms1scanOf(0);
        int charge = t.index().chargeOf(0);
        return new ScanView(
                t.index().scanIdAt(0),
                t.msLevel(),
                t.index().rtOf(0),
                polarity == 1 ? Polarity.POSITIVE : polarity == 2 ? Polarity.NEGATIVE : null,
                precmz == 0.0 ? null : precmz,
                ms1scan == 0 ? null : ms1scan,
                charge == 0 ? null : charge,
                t);
    }

    private static ScanInfoResult collateOne(
            SpectrumTable scan, SpectrumTable ms1, MassqlOptions opts) {
        ScaninfoCollation c = new ScaninfoCollation(opts);
        c.accept(view(scan), scan, ms1);
        return c.rows().get(0);
    }

    @Test
    void basePeaksComeFromTheArgmaxRowNotFromTwoSeparateMaxima() {
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

    @Test
    void ticIsTheSumOfPeakIntensitiesNotTheBasePeak() {
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

    @Test
    void theThreeZeroSentinelsBecomeNullAndNothingElseDoes() {
        SpectrumTable t =
                oneScan(7, 2, 0.0, 1, 0.0, 0, 0, new double[] {100.0}, new double[] {5.0});
        ScanInfoResult r = collateOne(t, null, null);
        assertNull(r.precmz(), "0 precmz -> null");
        assertNull(r.ms1scan(), "0 ms1scan -> null");
        assertNull(r.charge(), "0 charge -> null");

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

    @Test
    void sentinelConversionHappensAFTERTheLookupSoMs1scanZeroDoesNotThrow() {
        SpectrumTable t =
                oneScan(
                        1,
                        2,
                        0.0,
                        1,
                        250.25,
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

    @Test
    void anAllZeroIntensityScanCollatesWithoutNaNReachingTheRow() {
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

        assertFalse(Double.isNaN(r.tic()) || Double.isNaN(r.basePeakI()));
    }

    @Test
    void nonFiniteComputedValuesBecomeNullSoTheJsonStaysValid() {
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

        assertDoesNotThrow(
                () ->
                        new com.google.gson.GsonBuilder()
                                .serializeNulls()
                                .create()
                                .toJson(new org.cytoscape.massql.result.ResultJson(List.of(r))));
    }

    @Test
    void aNaNIntensityIsAlsoNulled() {
        SpectrumTable t =
                oneScan(1, 2, 1.0, 1, 500.0, 0, 0, new double[] {100.0}, new double[] {Double.NaN});
        ScanInfoResult r = collateOne(t, null, null);
        assertNull(r.tic());
        assertNull(r.basePeakI());
    }

    @Test
    void scansArrivingOutOfOrderAreRejectedRatherThanSilentlyMisaligned() {
        ScaninfoCollation c = new ScaninfoCollation(null);
        SpectrumTable hi =
                oneScan(10, 2, 1.0, 1, 500.0, 0, 0, new double[] {1.0}, new double[] {1.0});
        SpectrumTable lo =
                oneScan(2, 2, 1.0, 1, 500.0, 0, 0, new double[] {1.0}, new double[] {1.0});
        c.accept(view(hi), hi, null);
        MassqlException e = assertThrows(MassqlException.class, () -> c.accept(view(lo), lo, null));
        assertTrue(e.getMessage().contains("out of order"), e.getMessage());
    }

    @Test
    void theMicroGoldenIsReproducedRowForRow() {
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

        assertEquals(1000.0, second.ms1I());
        assertEquals(499.99609375, second.ms1Precmz());
        assertEquals(9000.0, second.ms1BasePeakI(), "max over the WHOLE MS1 scan, including 600.0");
    }

    @Test
    void anMs1dataQueryProducesTheSameShapeWithRealBasePeaksAndNullPrecursorFields() {
        List<ScanInfoResult> rows =
                run("QUERY scaninfo(MS1DATA)", "fixtures/micro/micro_ms1var.mzML", null);
        assertFalse(rows.isEmpty());
        for (ScanInfoResult r : rows) {
            assertEquals(1, r.mslevel(), "MS1DATA selects MS1 scans");

            assertNull(r.precmz());
            assertNull(r.ms1scan());
            assertNull(r.charge());
            assertNull(r.ms1I());
            assertNull(r.ms1Precmz());
            assertNull(r.ms1BasePeakI());

            assertNotNull(r.basePeakI(), "an MS1 scan has a base peak");
            assertNotNull(r.basePeakMz());
            assertTrue(r.basePeakI() > 0.0);
            assertNotNull(r.tic());
            assertNotNull(r.rt());
        }
    }

    @Test
    void anMgfPopulatesChargeAsOneAndLeavesEveryMs1ColumnNull() {
        List<ScanInfoResult> rows =
                run(
                        "QUERY scaninfo(MS2DATA) WHERE MS2PROD=200.5:TOLERANCEMZ=0.5",
                        "fixtures/micro/micro.mgf",
                        null);

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

        assertEquals(0.0, rows.get(0).rt(), "block 1 has no RTINSECONDS -> 0.0, and NOT null");
        assertEquals(1.0, rows.get(1).rt(), "RTINSECONDS=60.0 -> 1.0 minute");
    }

    @Test
    void thePrecursorToleranceIsHonouredAndIsSeparateFromTheQueryTolerance() {
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
