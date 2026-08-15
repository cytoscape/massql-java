package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

class MzxmlSchema20IT {
    @Test
    void theEwingFileParsesCompletely() {
        Path mzxml = Fixtures.require("data/DP00570_F02.mzxml");

        int scans = 0, ms1 = 0, ms2 = 0;
        long peaks = 0;
        int previousId = 0;
        try (SpectraStream s = SpectraFile.open(mzxml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                scans++;
                if (v.msLevel() == 1) ms1++;
                else ms2++;

                assertTrue(
                        v.scanId() > previousId,
                        "scan "
                                + v.scanId()
                                + " came after "
                                + previousId
                                + " -- the nested walk is "
                                + "emitting scans out of document order");
                previousId = v.scanId();

                SpectrumTable t = v.peaks();
                assertEquals(
                        v.peaks().rowCount(),
                        t.rowCount(),
                        "scan "
                                + v.scanId()
                                + ": peaksCount disagrees with the decoded array length");
                peaks += t.rowCount();
            }
        }

        assertEquals(916, scans, "total scans");
        assertEquals(229, ms1, "MS1 scans");
        assertEquals(687, ms2, "MS2 scans");
        assertEquals(916, ms1 + ms2);
        assertTrue(peaks > 100_000, "expected six figures of peaks, got " + peaks);
    }

    @Test
    void theFileReallyIsNestedSchemaTwoPointZero() {
        Path mzxml = Fixtures.require("data/DP00570_F02.mzxml");
        String raw;
        try {
            raw = Files.readString(mzxml, StandardCharsets.ISO_8859_1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertTrue(raw.contains("mzXML_2.0"), "expected a schema 2.0 declaration");

        int depth = 0, max = 0;
        for (int i = 0; i < raw.length() - 6; i++) {
            if (raw.startsWith("</scan>", i)) depth--;
            else if (raw.startsWith("<scan ", i)) {
                depth++;
                max = Math.max(max, depth);
            }
        }
        assertEquals(
                2,
                max,
                "DP00570_F02.mzxml is expected to NEST MS2 inside its parent MS1; a flat file here "
                        + "would leave the nested walk untested by any real input");
    }

    @Test
    void theElevenNearEmptyScansAreReadNotSkipped() {
        Path mzxml = Fixtures.require("data/DP00570_F02.mzxml");
        int threePeak = 0;
        try (SpectraStream s = SpectraFile.open(mzxml)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                if (v.peaks().rowCount() != 3) continue;
                threePeak++;
                SpectrumTable t = v.peaks();
                assertEquals(3, t.rowCount());
                for (int i = 0; i < 3; i++) {
                    assertTrue(t.mz(i) > 0.0, "m/z must be positive");
                    assertTrue(t.intensity(i) >= 0.0, "intensity must be non-negative");
                }

                assertTrue(t.mz(0) <= t.mz(1) && t.mz(1) <= t.mz(2), "peaks must be m/z-ascending");
            }
        }
        assertEquals(11, threePeak, "expected 11 scans with peaksCount=\"3\"");
    }

    @Test
    void nothingIsReportedAsSkipped() {
        Path mzxml = Fixtures.require("data/DP00570_F02.mzxml");
        try (SpectraStream s = SpectraFile.open(mzxml)) {
            while (s.hasNext()) {
                s.next();
            }
            assertEquals(
                    java.util.List.of(),
                    s.diagnostics(),
                    "unexpected diagnostics on a clean file: " + s.diagnostics());
        }
    }
}
