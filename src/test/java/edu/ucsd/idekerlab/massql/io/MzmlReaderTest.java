package edu.ucsd.idekerlab.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import edu.ucsd.idekerlab.massql.MassqlException;
import edu.ucsd.idekerlab.massql.spectra.SpectrumTable;

class MzmlReaderTest {

    // ------------------------------------------------------------------ scan id derivation

    @Test
    void scanIdTakesTheLastScanEqualsSegment() {
        // int(id.replace("scanId=","").split("scan=")[-1])  -- msql_fileloading.py:575.
        // This determines every row's identity; the LAST segment matters, and the rule was
        // missing from the spec entirely until Correction the rewrite.
        assertEquals(1, MzmlReader.scanIdFrom("controllerType=0 controllerNumber=1 scan=1"));
        assertEquals(48, MzmlReader.scanIdFrom("controllerType=0 controllerNumber=1 scan=48"));
        assertEquals(7, MzmlReader.scanIdFrom("scanId=7"));
        assertEquals(9, MzmlReader.scanIdFrom("scan=3 something scan=9"), "the LAST segment wins");
        assertEquals(12, MzmlReader.scanIdFrom("scan=12"));
    }

    @Test
    void anIdWithNoScanNumberFailsByName() {
        // MassQL raises ValueError here; we throw a named MassqlException. Documented deviation --
        // a clean error either way, but ours says which id.
        MassqlException e =
                assertThrows(MassqlException.class, () -> MzmlReader.scanIdFrom("spectrum=abc"));
        assertTrue(e.getMessage().contains("spectrum=abc"), e.getMessage());
    }

    // ------------------------------------------------------------------ the oracle cross-check

    /** One scan's worth of the Step 2 parity dump. */
    private record DumpScan(int scan, int mslevel, int peakCount) {}

    private static List<DumpScan> loadDump(Path gz) {
        String json;
        try (var in = new GZIPInputStream(Files.newInputStream(gz))) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // Light regex extraction rather than a JSON dependency: Jackson finds modules via
        // ServiceLoader, banned by DEPENDENCY_POLICY constraint 1. Step 8 needs the digests too and
        // can invest in a fuller reader; Step 6 only needs counts.
        Pattern p =
                Pattern.compile(
                        "\"scan\":\\s*(\\d+),\\s*\"mslevel\":\\s*(\\d+),\\s*\"peak_count\":\\s*(\\d+)");
        Matcher m = p.matcher(json);
        List<DumpScan> out = new ArrayList<>();
        while (m.find()) {
            out.add(
                    new DumpScan(
                            Integer.parseInt(m.group(1)),
                            Integer.parseInt(m.group(2)),
                            Integer.parseInt(m.group(3))));
        }
        return out;
    }

    /**
     * The check that matters: our reader against MassQL's own loaded tables.
     *
     * <p>Step 8 formalises this across all fixtures with bit-identical digests. Running a counts-level
     * version <b>here</b> is deliberate — a decoder or walk bug found now points at code written
     * minutes ago, whereas the same bug found at Step 8 looks like a query-layer problem.
     */
    @Test
    void smallMzmlMatchesTheOracleParityDump() {
        Path mzml = Fixtures.require("data/small.mzML");
        Path dump = Fixtures.require("goldens/loader-parity/small.mzML.json.gz");

        Map<Integer, Integer> expected = new LinkedHashMap<>(); // scan id -> peak count
        int expMs1 = 0, expMs2 = 0;
        for (DumpScan d : loadDump(dump)) {
            expected.put(d.scan(), d.peakCount());
            if (d.mslevel() == 1) expMs1++;
            else expMs2++;
        }
        assertEquals(48, expected.size(), "sanity: the dump itself should describe 48 scans");

        int ms1 = 0, ms2 = 0;
        long peaks = 0;
        Map<Integer, Integer> ms1scanOf = new LinkedHashMap<>(); // MS2 scan id -> its ms1scan
        try (SpectraStream s = SpectraFile.open(mzml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.msLevel() == 1) ms1++;
                else {
                    ms2++;
                    ms1scanOf.put(v.scanId(), v.ms1scan());
                }
                SpectrumTable t = v.materialize();
                peaks += t.rowCount();
                Integer want = expected.get(v.scanId());
                assertNotNull(
                        want, "reader produced scan " + v.scanId() + ", absent from the dump");
                assertEquals(
                        want.intValue(), t.rowCount(), "peak count differs for scan " + v.scanId());
            }
        }

        assertEquals(expMs1, ms1, "MS1 scan count");
        assertEquals(expMs2, ms2, "MS2 scan count");
        assertEquals(14, ms1);
        assertEquals(34, ms2);
        assertEquals(305_214L, peaks, "total peaks across both levels");

        // The six rows in output/small_mzml_results.json, asserted as an explicit MS2 -> MS1
        // mapping.
        //
        // NOT as "the distinct ms1scan values in the file": those six are only the scans that
        // matched
        // test_mzml.massql, and the file legitimately references others (30, from MS2 scans the
        // query
        // rejected). Asserting the distinct set conflates the golden's filtered subset with the
        // whole
        // file and fails for a reason that has nothing to do with the reader.
        Map<Integer, Integer> goldenLinkage = new LinkedHashMap<>();
        goldenLinkage.put(3, 2);
        goldenLinkage.put(10, 9);
        goldenLinkage.put(17, 16);
        goldenLinkage.put(24, 23);
        goldenLinkage.put(37, 36);
        goldenLinkage.put(44, 43);
        goldenLinkage.forEach(
                (ms2Scan, wantMs1) ->
                        assertEquals(
                                wantMs1,
                                ms1scanOf.get(ms2Scan),
                                "ms1scan for MS2 scan "
                                        + ms2Scan
                                        + " (document order, not spectrumRef)"));

        // Every MS2 scan must link to an MS1 scan that genuinely precedes it.
        ms1scanOf.forEach(
                (ms2Scan, linked) ->
                        assertTrue(
                                linked > 0 && linked < ms2Scan,
                                "scan "
                                        + ms2Scan
                                        + " links to "
                                        + linked
                                        + ", which does not precede it"));
    }

    @Test
    void scanThreeMatchesTheGoldenRecordFieldForField() {
        // output/small_mzml_results.json's first record. rt is asserted BIT-exact: the value does
        // not
        // survive a float round-trip, which is why ScanIndex carries rt as a double (Step 5 §1).
        Path mzml = Fixtures.require("data/small.mzML");
        try (SpectraStream s = SpectraFile.open(mzml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.scanId() != 3) continue;
                assertEquals(2, v.msLevel());
                assertEquals(810.79, v.precmz());
                assertEquals(2, v.ms1scan(), "document order, not spectrumRef");
                assertEquals(0, v.charge(), "not recorded -> 0 sentinel; Step 10 converts to null");
                assertEquals(1, v.polarity());
                assertEquals(
                        Double.doubleToLongBits(0.011218333333333334),
                        Double.doubleToLongBits(v.rt()),
                        "rt must be bit-exact");
                SpectrumTable t = v.materialize();
                assertEquals(485, t.rowCount());
                // First decoded m/z, bit-exact. small.mzML stores m/z as 64-bit, so this value must
                // survive in full precision -- a 32-bit path would give 231.38883972167970's float.
                assertEquals(
                        Double.doubleToLongBits(231.38883972167969),
                        Double.doubleToLongBits(t.mz(0)),
                        "first m/z of scan 3");
                return;
            }
            fail("scan 3 not found");
        }
    }

    // ------------------------------------------------------------------ misc

    @Test
    void materializeIsRepeatableAndIndependent() {
        Path mzml = Fixtures.require("data/small.mzML");
        try (SpectraStream s = SpectraFile.open(mzml)) {
            assertTrue(s.hasNext());
            ScanView v = s.next();
            SpectrumTable a = v.materialize();
            SpectrumTable b = v.materialize();
            assertNotSame(a, b);
            assertEquals(a.rowCount(), b.rowCount());
            assertEquals(a.mz(0), b.mz(0));
        }
    }

    @Test
    void nextPastTheEndThrowsRatherThanReturningStaleData() {
        // Replaces `currentBeforeNextIsAnError`, whose subject -- current() -- no longer exists.
        // Under hasNext()/next() the equivalent hazard is reading past the end, and the equivalent
        // guarantee is that it fails loudly instead of handing back the last scan a second time.
        try (SpectraStream s = SpectraFile.open(Fixtures.require("data/small.mzML"))) {
            while (s.hasNext()) s.next();
            assertThrows(NoSuchElementException.class, s::next);
        }
    }

    @Test
    void usingAClosedStreamIsAnError() {
        SpectraStream s = SpectraFile.open(Fixtures.require("data/small.mzML"));
        s.close();
        assertThrows(MassqlException.class, s::next);
    }

    @Test
    void truncatedMzmlThrowsWithNoPartialResult(@TempDir Path dir) throws IOException {
        // Cut a real file mid-spectrum. A reader that returned the scans it managed to read would
        // be
        // worse than one that fails: 40 of 48 scans looks like a filtering bug downstream.
        byte[] all = Files.readAllBytes(Fixtures.require("data/small.mzML"));
        Path cut = dir.resolve("truncated.mzML");
        Files.write(cut, java.util.Arrays.copyOf(all, all.length / 3));

        assertThrows(
                MassqlException.class,
                () -> {
                    try (SpectraStream s = SpectraFile.open(cut)) {
                        while (s.hasNext()) {
                            ScanView v = s.next();
                            v.materialize();
                        }
                    }
                });
    }
}
