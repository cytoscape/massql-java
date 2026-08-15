package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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

import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.lang.ast.Polarity;
import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.testsupport.Fixtures;
import org.cytoscape.massql.testsupport.Raw;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MzmlReaderTest {
    @Test
    void scanIdTakesTheLastScanEqualsSegment() {
        assertEquals(1, MzmlReader.scanIdFrom("controllerType=0 controllerNumber=1 scan=1"));
        assertEquals(48, MzmlReader.scanIdFrom("controllerType=0 controllerNumber=1 scan=48"));
        assertEquals(7, MzmlReader.scanIdFrom("scanId=7"));
        assertEquals(9, MzmlReader.scanIdFrom("scan=3 something scan=9"), "the LAST segment wins");
        assertEquals(12, MzmlReader.scanIdFrom("scan=12"));
    }

    @Test
    void anIdWithNoScanNumberFailsByName() {
        MassqlException e =
                assertThrows(MassqlException.class, () -> MzmlReader.scanIdFrom("spectrum=abc"));
        assertTrue(e.getMessage().contains("spectrum=abc"), e.getMessage());
    }

    private record DumpScan(int scan, int mslevel, int peakCount) {}

    private static List<DumpScan> loadDump(Path gz) {
        String json;
        try (var in = new GZIPInputStream(Files.newInputStream(gz))) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

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

    @Test
    void smallMzmlMatchesTheOracleParityDump() {
        Path mzml = Fixtures.require("data/small.mzML");
        Path dump = Fixtures.require("goldens/loader-parity/small.mzML.json.gz");

        Map<Integer, Integer> expected = new LinkedHashMap<>();
        int expMs1 = 0, expMs2 = 0;
        for (DumpScan d : loadDump(dump)) {
            expected.put(d.scan(), d.peakCount());
            if (d.mslevel() == 1) expMs1++;
            else expMs2++;
        }
        assertEquals(48, expected.size(), "sanity: the dump itself should describe 48 scans");

        int ms1 = 0, ms2 = 0;
        long peaks = 0;
        Map<Integer, Integer> ms1scanOf = new LinkedHashMap<>();
        try (SpectraStream s = SpectraFile.open(mzml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.msLevel() == 1) ms1++;
                else {
                    ms2++;
                    ms1scanOf.put(v.scanId(), Raw.orZero(v.ms1scan()));
                }
                SpectrumTable t = v.peaks();
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
        Path mzml = Fixtures.require("data/small.mzML");
        try (SpectraStream s = SpectraFile.open(mzml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.scanId() != 3) continue;
                assertEquals(2, v.msLevel());
                assertEquals(810.79, v.precmz());
                assertEquals(2, v.ms1scan(), "document order, not spectrumRef");
                assertNull(v.charge(), "not recorded -> null");
                assertEquals(Polarity.POSITIVE, v.polarity());
                assertEquals(
                        Double.doubleToLongBits(0.011218333333333334),
                        Double.doubleToLongBits(v.rt()),
                        "rt must be bit-exact");
                SpectrumTable t = v.peaks();
                assertEquals(485, t.rowCount());

                assertEquals(
                        Double.doubleToLongBits(231.38883972167969),
                        Double.doubleToLongBits(t.mz(0)),
                        "first m/z of scan 3");
                return;
            }
            fail("scan 3 not found");
        }
    }

    @Test
    void thePeakTableIsTheSameValueOnEveryRead() {
        Path mzml = Fixtures.require("data/small.mzML");
        try (SpectraStream s = SpectraFile.open(mzml)) {
            assertTrue(s.hasNext());
            ScanView v = s.next();
            SpectrumTable a = v.peaks();
            SpectrumTable b = v.peaks();
            assertSame(a, b, "the view is a value; peaks are decoded once");
            assertEquals(a.rowCount(), b.rowCount());
        }
    }

    @Test
    void nextPastTheEndThrowsRatherThanReturningStaleData() {
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
        byte[] all = Files.readAllBytes(Fixtures.require("data/small.mzML"));
        Path cut = dir.resolve("truncated.mzML");
        Files.write(cut, java.util.Arrays.copyOf(all, all.length / 3));

        assertThrows(
                MassqlException.class,
                () -> {
                    try (SpectraStream s = SpectraFile.open(cut)) {
                        while (s.hasNext()) {
                            ScanView v = s.next();
                            v.peaks();
                        }
                    }
                });
    }
}
