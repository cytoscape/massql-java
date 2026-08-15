package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cytoscape.massql.testsupport.Fixtures;
import org.cytoscape.massql.testsupport.Raw;
import org.junit.jupiter.api.Test;

class MultiPrecursorTest {
    private static final double DECOY_MZ = 999.875;

    private static final double DECOY_MZ_2 = 1000.875;

    private static Map<Integer, double[]> ms2PrecursorsOf(String fixture) {
        Map<Integer, double[]> out = new LinkedHashMap<>();
        Path p = Fixtures.require("fixtures/micro/" + fixture);
        try (SpectraStream s = SpectraFile.open(p)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.msLevel() == 2)
                    out.put(
                            v.scanId(),
                            new double[] {Raw.orZero(v.precmz()), Raw.orZero(v.charge())});
            }
        }
        return out;
    }

    @Test
    void mzxmlTakesTheFirstPrecursorNotTheLast() {
        Map<Integer, double[]> got = ms2PrecursorsOf("micro_multiprec.mzXML");
        assertEquals(3, got.size(), "three MS2 scans");

        assertEquals(250.25, got.get(1)[0], "scan 1 precmz");
        assertEquals(500.0, got.get(3)[0], "scan 3 precmz");
        assertEquals(500.0, got.get(5)[0], "scan 5 precmz");

        got.forEach(
                (scan, pc) -> {
                    assertNotEquals(
                            DECOY_MZ,
                            pc[0],
                            "scan "
                                    + scan
                                    + " reported the SECOND <precursorMz> ("
                                    + DECOY_MZ
                                    + "). MassQL "
                                    + "hard-indexes precursorMz[0] -- first wins, not last");
                    assertNotEquals(
                            7.0,
                            pc[1],
                            "scan "
                                    + scan
                                    + " took charge from the second <precursorMz>; MassQL reads "
                                    + "precursorMz[0] for charge too");
                });
    }

    @Test
    void mzmlTakesTheFirstSelectedIonOfTheFirstPrecursor() {
        Map<Integer, double[]> got = ms2PrecursorsOf("micro_multiprec.mzML");
        assertEquals(3, got.size(), "three MS2 scans");

        assertEquals(250.25, got.get(1)[0], "scan 1 precmz");
        assertEquals(500.0, got.get(3)[0], "scan 3 precmz");
        assertEquals(500.0, got.get(5)[0], "scan 5 precmz");

        got.forEach(
                (scan, pc) -> {
                    assertNotEquals(
                            DECOY_MZ,
                            pc[0],
                            "scan "
                                    + scan
                                    + " reported the second <selectedIon> ("
                                    + DECOY_MZ
                                    + "); MassQL "
                                    + "reads selectedIon[0]");
                    assertNotEquals(
                            DECOY_MZ_2,
                            pc[0],
                            "scan "
                                    + scan
                                    + " reported the second <precursor> ("
                                    + DECOY_MZ_2
                                    + "); MassQL "
                                    + "reads precursor[0]");
                    assertNotEquals(7.0, pc[1], "charge came from the second selectedIon");
                    assertNotEquals(8.0, pc[1], "charge came from the second precursor");
                });
    }

    @Test
    void theFixturesReallyDoCarryMultiplePrecursors() {
        String mzxml = read("micro_multiprec.mzXML");
        assertTrue(
                countOf(mzxml, "<precursorMz") >= 6,
                "expected 2 <precursorMz> per MS2 scan across 3 MS2 scans; got "
                        + countOf(mzxml, "<precursorMz"));

        String mzml = read("micro_multiprec.mzML");
        assertTrue(countOf(mzml, "<selectedIon>") >= 6, "expected 2 selectedIon per MS2 scan");
        assertTrue(countOf(mzml, "<precursor>") >= 6, "expected 2 precursor per MS2 scan");
        assertTrue(mzml.contains(String.valueOf(DECOY_MZ)), "the mzML decoy value is missing");
        assertTrue(mzml.contains(String.valueOf(DECOY_MZ_2)), "the second mzML decoy is missing");
    }

    @Test
    void everythingElseMatchesTheSinglePrecursorBaseline() {
        record Row(int scan, int level, int ms1scan, double rt, int polarity, int peaks) {}
        java.util.function.Function<String, java.util.List<Row>> read =
                name -> {
                    java.util.List<Row> out = new java.util.ArrayList<>();
                    try (SpectraStream s =
                            SpectraFile.open(Fixtures.require("fixtures/micro/" + name))) {
                        while (s.hasNext()) {
                            ScanView v = s.next();
                            out.add(
                                    new Row(
                                            v.scanId(),
                                            v.msLevel(),
                                            Raw.orZero(v.ms1scan()),
                                            v.rt(),
                                            Raw.polarity(v.polarity()),
                                            v.peaks().rowCount()));
                        }
                    }
                    return out;
                };
        assertEquals(
                read.apply("micro.mzXML"),
                read.apply("micro_multiprec.mzXML"),
                "extra precursors changed something other than the precursor fields (mzXML)");
        assertEquals(
                read.apply("micro.mzML"),
                read.apply("micro_multiprec.mzML"),
                "extra precursors changed something other than the precursor fields (mzML)");
    }

    private static String read(String fixture) {
        try {
            return new String(
                    java.nio.file.Files.readAllBytes(Fixtures.require("fixtures/micro/" + fixture)),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static int countOf(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }
}
