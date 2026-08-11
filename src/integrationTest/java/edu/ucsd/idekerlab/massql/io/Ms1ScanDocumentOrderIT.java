package edu.ucsd.idekerlab.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * <b>The decisive assertion of the whole {@code ms1scan} rule.</b>
 *
 * <p>MassQL never reads a file's own precursor linkage — {@code spectrumRef} (mzML) and
 * {@code precursorScanNum} (mzXML) appear <b>zero times</b> in all 892 lines of
 * {@code msql_fileloading.py}. It tracks {@code previous_ms1_scan} in document order instead. A reader
 * that "correctly" resolves the declared linkage disagrees with MassQL whenever the reference is not the
 * immediately preceding MS1 scan.
 *
 * <p><b>Only this fixture can tell the two implementations apart.</b> For simple DDA they coincide, which
 * is why {@code small.mzML} and {@code small.mzXML} cannot detect the difference — {@code small.mzXML}
 * even carries 34 {@code precursorScanNum} attributes that happen to agree. {@code DP00570_F02.mzxml} has
 * <b>zero</b> {@code precursorScanNum} attributes (asserted below, so this test cannot go vacuous), so a
 * {@code precursorScanNum}-resolving reader has nothing to resolve and fails loudly.
 *
 * <p>The linkage is also non-trivial here, which is what makes it a real test: scan 100 links to
 * <b>97</b>, not 99, because 98 and 99 are themselves MS2 scans. A reader that guessed "scan id minus
 * one" would pass on a naive fixture and fail on this one.
 *
 * <p><b>This test must never skip.</b> The fixture is gitignored for licence reasons only;
 * {@code Fixtures.require} fails with the fetch command when it is absent, CI runs
 * {@code scripts/fetch-fixtures.sh} and caches the result, and CI asserts the skipped-test count is 0
 * ( — previously this skipped silently in CI, which is how the gate came to prove nothing).
 */
class Ms1ScanDocumentOrderIT {

    /** Reads one attribute out of a raw {@code <scan ...>} attribute string. */
    private static String attrValue(String attrs, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(attrs);
        assertTrue(m.find(), "attribute " + name + " missing from: " + attrs);
        return m.group(1);
    }

    @Test
    void everyMs2LinksToTheMostRecentPrecedingMs1() {
        Path mzxml = Fixtures.require("data/DP00570_F02.mzxml");

        // Guard the premise FIRST. If the fixture ever gained precursorScanNum attributes, this
        // test
        // would silently stop distinguishing the two implementations.
        String raw;
        try {
            raw = Files.readString(mzxml, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertEquals(
                0,
                raw.split("precursorScanNum", -1).length - 1,
                "DP00570_F02.mzxml must carry ZERO precursorScanNum attributes -- that absence is the "
                        + "only reason this fixture can distinguish document order from declared linkage");

        // Expected linkage derived INDEPENDENTLY of the reader, by scanning the raw XML for <scan>
        // attributes in the order they appear. A regex over the bytes shares no code with the
        // streaming walk, so agreement means both got document order right rather than both sharing
        // one bug.
        //
        // NOT from the loader-parity dump: that is built from ms1_df then ms2_df, so its `scans`
        // list
        // is GROUPED BY LEVEL (229 MS1 entries, then 687 MS2) and cannot express document order at
        // all. Deriving the chain from it yields 913 -- the last MS1 -- for every MS2. Step 8 needs
        // to know this about the dump too.
        Map<Integer, Integer> expected = new LinkedHashMap<>();
        Matcher scanTag = Pattern.compile("<scan\\s([^>]*)>").matcher(raw);
        int previousMs1 = 0;
        int seenMs1 = 0, seenMs2 = 0;
        while (scanTag.find()) {
            String attrs = scanTag.group(1);
            int num = Integer.parseInt(attrValue(attrs, "num"));
            int level = Integer.parseInt(attrValue(attrs, "msLevel"));
            int peaks = Integer.parseInt(attrValue(attrs, "peaksCount"));
            if (level == 1) {
                seenMs1++;
                if (peaks > 0) previousMs1 = num; // an empty MS1 is not a link
            } else if (level == 2) {
                seenMs2++;
                expected.put(num, previousMs1);
            }
        }
        assertEquals(229, seenMs1, "the raw XML should hold 229 MS1 scans");
        assertEquals(687, seenMs2, "the raw XML should hold 687 MS2 scans");

        Map<Integer, Integer> actual = new LinkedHashMap<>();
        int ms1 = 0;
        try (SpectraStream s = SpectraFile.open(mzxml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.msLevel() == 1) ms1++;
                else actual.put(v.scanId(), v.ms1scan());
            }
        }

        assertEquals(229, ms1, "MS1 scan count");
        assertEquals(687, actual.size(), "MS2 scan count");

        // Report the FIRST disagreement rather than dumping 687 pairs into the failure message --
        // a 700-entry map diff is unreadable and buries the one scan that matters.
        for (Map.Entry<Integer, Integer> e : expected.entrySet()) {
            assertEquals(
                    e.getValue(),
                    actual.get(e.getKey()),
                    "ms1scan for MS2 scan "
                            + e.getKey()
                            + " must be the most recent preceding MS1 by "
                            + "DOCUMENT ORDER (expected "
                            + e.getValue()
                            + ", got "
                            + actual.get(e.getKey())
                            + "). "
                            + expected.size()
                            + " MS2 scans compared.");
        }
        assertEquals(expected, actual, "the two maps must agree exactly");
    }

    @Test
    void theLinkageIsNonTrivialAndNonZero() {
        Path mzxml = Fixtures.require("data/DP00570_F02.mzxml");
        Map<Integer, Integer> links = new LinkedHashMap<>();
        try (SpectraStream s = SpectraFile.open(mzxml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.msLevel() == 2) links.put(v.scanId(), v.ms1scan());
            }
        }

        // Spot values taken from MassQL's own loader, not from our reader.
        assertEquals(9, links.get(10));
        assertEquals(97, links.get(100), "scan 100 links to 97, NOT 99 -- 98 and 99 are MS2 scans");
        assertEquals(101, links.get(102));
        assertEquals(101, links.get(103));
        assertEquals(101, links.get(104));

        // This file has no leading MS2, so every link must be a real scan. A
        // precursorScanNum-resolving
        // reader produces 0/null throughout and fails here even without the map comparison above.
        links.forEach(
                (ms2, linked) ->
                        assertTrue(
                                linked > 0,
                                "scan "
                                        + ms2
                                        + " has ms1scan 0; a precursorScanNum-resolving reader gives exactly this"));
        links.forEach(
                (ms2, linked) ->
                        assertTrue(
                                linked < ms2,
                                "scan "
                                        + ms2
                                        + " links to "
                                        + linked
                                        + ", which does not precede it"));

        // All 229 MS1 scans are referenced, so no whole region of the file is being mis-linked.
        assertEquals(
                229,
                new java.util.HashSet<>(links.values()).size(),
                "every one of the 229 MS1 scans should be the target of at least one MS2");

        // Consecutive MS2 scans sharing a precursor are the norm here; if every link were distinct,
        // the reader would be inventing a 1:1 mapping.
        assertTrue(
                links.size() > new java.util.HashSet<>(links.values()).size(),
                "687 MS2 scans over 229 MS1 scans means links must repeat");
    }
}
