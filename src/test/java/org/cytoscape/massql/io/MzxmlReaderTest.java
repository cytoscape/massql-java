package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.testsupport.Fixtures;
import org.cytoscape.massql.testsupport.Raw;
import org.junit.jupiter.api.Test;

class MzxmlReaderTest {
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
    void smallMzxmlMatchesTheOracleParityDump() {
        Path mzxml = Fixtures.require("data/small.mzXML");
        Path dump = Fixtures.require("goldens/loader-parity/small.mzXML.json.gz");

        Map<Integer, Integer> expected = new LinkedHashMap<>();
        int expMs1 = 0, expMs2 = 0;
        for (DumpScan d : loadDump(dump)) {
            expected.put(d.scan(), d.peakCount());
            if (d.mslevel() == 1) expMs1++;
            else expMs2++;
        }
        assertEquals(48, expected.size(), "sanity: the dump itself should describe 48 scans");

        int ms1 = 0, ms2 = 0;
        Map<Integer, Integer> ms1scanOf = new LinkedHashMap<>();
        try (SpectraStream s = SpectraFile.open(mzxml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.msLevel() == 1) ms1++;
                else {
                    ms2++;
                    ms1scanOf.put(v.scanId(), v.ms1scan());
                }
                SpectrumTable t = v.peaks();
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
    void bothScanLayoutsProduceIdenticalResults() {
        record Row(
                int scan,
                int level,
                int ms1scan,
                double rt,
                double precmz,
                int charge,
                int polarity,
                int peaks,
                double firstMz) {}

        java.util.function.Function<String, List<Row>> read =
                name -> {
                    List<Row> out = new ArrayList<>();
                    try (SpectraStream s =
                            SpectraFile.open(Fixtures.require("fixtures/micro/" + name))) {
                        while (s.hasNext()) {
                            ScanView v = s.next();
                            SpectrumTable t = v.peaks();
                            out.add(
                                    new Row(
                                            v.scanId(),
                                            v.msLevel(),
                                            Raw.orZero(v.ms1scan()),
                                            v.rt(),
                                            Raw.orZero(v.precmz()),
                                            Raw.orZero(v.charge()),
                                            Raw.polarity(v.polarity()),
                                            t.rowCount(),
                                            t.rowCount() == 0 ? Double.NaN : t.mz(0)));
                        }
                    }
                    return out;
                };

        List<Row> flat = read.apply("micro.mzXML");
        List<Row> nested = read.apply("micro_nested.mzXML");

        assertEquals(5, flat.size(), "all five micro scans, flat");
        assertEquals(flat.size(), nested.size(), "nested layout dropped or duplicated a scan");
        assertEquals(flat, nested, "flat and nested layouts must produce identical rows");

        Map<Integer, Integer> links = new LinkedHashMap<>();
        for (Row r : nested) if (r.level() == 2) links.put(r.scan(), r.ms1scan());
        assertEquals(
                Map.of(1, 0, 3, 2, 5, 2),
                links,
                "scan 5 must link to 2 -- scan 4 is a zero-peak MS1 and is invisible to the chain");
    }

    @Test
    void precursorScanNumIsNeverRead() {
        Path mzxml = Fixtures.require("data/small.mzXML");
        String head;
        try {
            head = Files.readString(mzxml, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        int occurrences = head.split("precursorScanNum", -1).length - 1;
        assertEquals(
                34,
                occurrences,
                "fixture no longer carries precursorScanNum; this test would be vacuous");

        int previousMs1 = 0;
        try (SpectraStream s = SpectraFile.open(mzxml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.msLevel() == 1) {
                    if (v.peaks().rowCount() > 0) previousMs1 = v.scanId();
                } else {
                    assertEquals(
                            previousMs1,
                            Raw.orZero(v.ms1scan()),
                            "scan "
                                    + v.scanId()
                                    + " must link by document order, not precursorScanNum");
                }
            }
        }
    }

    @Test
    void chargeAbsentIsNullNotOne() {
        Map<Integer, Integer> charges = new LinkedHashMap<>();
        try (SpectraStream s = SpectraFile.open(Fixtures.require("fixtures/micro/micro.mzXML"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.msLevel() == 2) charges.put(v.scanId(), v.charge());
            }
        }
        assertNull(charges.get(1), "absent precursorCharge -> null in mzXML, unlike MGF's 1");
        assertNull(charges.get(3), "absent precursorCharge -> null");
        assertEquals(2, charges.get(5), "precursorCharge=\"2\" is read");

        Map<Integer, Integer> mgfCharges = new LinkedHashMap<>();
        try (SpectraStream s = SpectraFile.open(Fixtures.require("fixtures/micro/micro.mgf"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                mgfCharges.put(v.scanId(), v.charge());
            }
        }
        assertEquals(1, mgfCharges.get(1), "MGF's absent CHARGE is 1 -- deliberately NOT 0");
    }

    @Test
    void zeroPeakScanIsYieldedAndMaterialisesEmpty() {
        boolean saw = false;
        int scans = 0;
        try (SpectraStream s = SpectraFile.open(Fixtures.require("fixtures/micro/micro.mzXML"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                scans++;
                if (v.scanId() == 4) {
                    saw = true;
                    assertEquals(0, v.peaks().rowCount());
                    assertEquals(
                            0,
                            v.peaks().rowCount(),
                            "an empty scan materialises to an empty table, not an error");
                }
            }
        }
        assertEquals(5, scans);
        assertTrue(saw, "the zero-peak scan was dropped; it must be yielded");
    }
}
