package org.cytoscape.massql.io;

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

import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

class Ms1ScanDocumentOrderIT {
    private static String attrValue(String attrs, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(attrs);
        assertTrue(m.find(), "attribute " + name + " missing from: " + attrs);
        return m.group(1);
    }

    @Test
    void everyMs2LinksToTheMostRecentPrecedingMs1() {
        Path mzxml = Fixtures.require("data/DP00570_F02.mzxml");

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
                if (peaks > 0) previousMs1 = num;
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

        assertEquals(9, links.get(10));
        assertEquals(97, links.get(100), "scan 100 links to 97, NOT 99 -- 98 and 99 are MS2 scans");
        assertEquals(101, links.get(102));
        assertEquals(101, links.get(103));
        assertEquals(101, links.get(104));

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

        assertEquals(
                229,
                new java.util.HashSet<>(links.values()).size(),
                "every one of the 229 MS1 scans should be the target of at least one MS2");

        assertTrue(
                links.size() > new java.util.HashSet<>(links.values()).size(),
                "687 MS2 scans over 229 MS1 scans means links must repeat");
    }
}
