package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

/**
 * When an MS2 scan declares more than one precursor, the <b>FIRST</b> one wins.
 *
 * <p><b>Both readers had this wrong.</b> MassQL hard-indexes {@code [0]} at every level —
 * {@code spectrum["precursorList"]["precursor"][0]["selectedIonList"]["selectedIon"][0]} for mzML
 * and the first {@code precursorMz} for mzXML. Both
 * readers instead <b>overwrote</b> {@code precmz}/{@code charge} on every occurrence, so the <i>last</i>
 * declared precursor won.
 *
 * <p><b>Why nothing caught it.</b> Every fixture in the project is single-precursor — measured
 * {@code max=1} on {@code small.mzML}, {@code small.mzXML} and the Ewing file — so first-wins and
 * last-wins are indistinguishable across the entire suite. The bug was found by asking whether mzML
 * <i>can</i> carry multiple precursors, not by any failing test. Same recurring shape: a rule
 * with no fixture that could discriminate.
 *
 * <p>Multiple precursors are not pathological. <b>Multiplexed (MSX) acquisition deliberately
 * co-fragments several precursors into one MS2 scan</b>, and DIA/SWATH uses wide isolation windows with no
 * single selected ion. MassQL simply keeps the first and discards the rest — so these fixtures are
 * <b>parity</b> fixtures, unlike the pair that MassQL cannot load at all.
 *
 * <p>Ground truth, from MassQL's own loader over these two fixtures:
 * <pre>
 *   micro_multiprec.mzXML   {1: (250.25, 0), 3: (500.0, 0), 5: (500.0, 2)}
 *   micro_multiprec.mzML    {1: (250.25, 0), 3: (500.0, 0), 5: (500.0, 2)}
 * </pre>
 * The decoys ({@code 999.875} charge 7, and {@code 1000.875} charge 8) appear nowhere.
 */
class MultiPrecursorTest {

    /** The decoy values the fixtures carry AFTER the real precursor. */
    private static final double DECOY_MZ = 999.875;

    private static final double DECOY_MZ_2 = 1000.875;

    private static Map<Integer, double[]> ms2PrecursorsOf(String fixture) {
        Map<Integer, double[]> out = new LinkedHashMap<>();
        Path p = Fixtures.require("fixtures/micro/" + fixture);
        try (SpectraStream s = SpectraFile.open(p)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.msLevel() == 2) out.put(v.scanId(), new double[] {v.precmz(), v.charge()});
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
                    // The fixture carries BOTH a second <selectedIon> inside the first <precursor>
                    // AND a
                    // whole second <precursor>, so this catches a reader that respects one level
                    // but not the other.
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
        // Guards the premise. If the generator stopped emitting decoys, both tests above would pass
        // vacuously against a last-wins reader -- exactly the hole this came out of.
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
        // The decoys must perturb nothing but the precursor fields. Compare against the plain
        // fixtures
        // on every other column, so "first wins" cannot be masking a broken parse.
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
                                            v.ms1scan(),
                                            v.rt(),
                                            v.polarity(),
                                            v.materialize().rowCount()));
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
