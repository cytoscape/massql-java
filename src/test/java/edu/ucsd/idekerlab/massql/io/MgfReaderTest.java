package edu.ucsd.idekerlab.massql.io;

import static org.junit.jupiter.api.Assertions.*;

import edu.ucsd.idekerlab.massql.MassqlException;
import edu.ucsd.idekerlab.massql.spectra.SpectrumTable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MGF rules, all derived from {@code _load_data_mgf_pyteomics} (`msql_fileloading.py:155-244`) rather
 * than from the MGF format documentation — where the two differ, MassQL wins, because Step 8 asserts
 * bit-identity against what MassQL loaded.
 */
class MgfReaderTest {

    private static Path write(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    private record Row(int scan, double rt, double precmz, int charge, int ms1scan,
                       int msLevel, int polarity, int peaks) { }

    private static List<Row> readAll(Path p) {
        List<Row> out = new ArrayList<>();
        try (SpectraStream s = SpectraFile.open(p)) {
            while (s.next()) {
                ScanView v = s.current();
                out.add(new Row(v.scanId(), v.rt(), v.precmz(), v.charge(), v.ms1scan(),
                                v.msLevel(), v.polarity(), v.materialize().rowCount()));
            }
        }
        return out;
    }

    @Test
    void absentChargeIsOneNotZero(@TempDir Path dir) throws IOException {
        // ⚠ Correction C6. SPIKE.md §3 says MGF charge is "null if absent"; the live pyteomics loader
        // uses params.get('charge', [1]) with `except: charge = 1`. Since only 0 is null-converted
        // (Step 10 §4), MGF charge is NEVER null -- a genuine 1+ and an absent CHARGE are
        // indistinguishable. The golden agrees: {1: 653, 2: 10, 3: 1}, zero nulls.
        Path p = write(dir, "a.mgf", """
                BEGIN IONS
                TITLE=no charge here
                PEPMASS=500.5
                100.0 10.0
                END IONS
                """);
        assertEquals(1, readAll(p).get(0).charge());
    }

    @Test
    void chargeStripsTrailingSign(@TempDir Path dir) throws IOException {
        Path p = write(dir, "a.mgf", """
                BEGIN IONS
                PEPMASS=500.5
                CHARGE=2+
                100.0 10.0
                END IONS
                BEGIN IONS
                PEPMASS=500.5
                CHARGE=3-
                100.0 10.0
                END IONS
                BEGIN IONS
                PEPMASS=500.5
                CHARGE=4
                100.0 10.0
                END IONS
                """);
        List<Row> r = readAll(p);
        assertEquals(2, r.get(0).charge());
        assertEquals(3, r.get(1).charge());
        assertEquals(4, r.get(2).charge());
    }

    @Test
    void pepmassIgnoresATrailingIntensityToken(@TempDir Path dir) throws IOException {
        // Real MGF: "PEPMASS=491.555664 3058030.0000" -- the second token is precursor intensity.
        Path p = write(dir, "a.mgf", """
                BEGIN IONS
                PEPMASS=491.555664 3058030.0000
                100.0 10.0
                END IONS
                BEGIN IONS
                PEPMASS=491.555664
                100.0 10.0
                END IONS
                """);
        List<Row> r = readAll(p);
        assertEquals(491.555664, r.get(0).precmz());
        assertEquals(r.get(1).precmz(), r.get(0).precmz(), "the trailing token must not change precmz");
    }

    @Test
    void retentionTimeIsSecondsOver60AndAbsentMeansZeroNotNull(@TempDir Path dir) throws IOException {
        // 0.0 is a REAL value here, not a missing one. plusrise_results.json has rt: 0.0 on all 664
        // records, so an over-eager null conversion would fail 664 rows at once (Step 10 §4).
        Path p = write(dir, "a.mgf", """
                BEGIN IONS
                PEPMASS=500.5
                RTINSECONDS=90
                100.0 10.0
                END IONS
                BEGIN IONS
                PEPMASS=500.5
                100.0 10.0
                END IONS
                """);
        List<Row> r = readAll(p);
        assertEquals(1.5, r.get(0).rt(), "90 s = 1.5 min");
        assertEquals(0.0, r.get(1).rt(), "absent RTINSECONDS is 0.0");
    }

    @Test
    void scanIdIsScansWhenPresentElseTheOneBasedBlockIndex(@TempDir Path dir) throws IOException {
        // Correction C7. Getting this wrong shifts every row's identity and makes the Step 12
        // differential fail in a way that looks like a filtering bug.
        Path withScans = write(dir, "s.mgf", """
                BEGIN IONS
                PEPMASS=1.0
                SCANS=576
                100.0 10.0
                END IONS
                BEGIN IONS
                PEPMASS=2.0
                SCANS=999
                100.0 10.0
                END IONS
                """);
        assertEquals(576, readAll(withScans).get(0).scan());
        assertEquals(999, readAll(withScans).get(1).scan());

        Path without = write(dir, "n.mgf", """
                BEGIN IONS
                PEPMASS=1.0
                100.0 10.0
                END IONS
                BEGIN IONS
                PEPMASS=2.0
                100.0 10.0
                END IONS
                """);
        assertEquals(1, readAll(without).get(0).scan(), "1-based block index");
        assertEquals(2, readAll(without).get(1).scan());
    }

    @Test
    void everyScanIsMs2WithMs1scanZeroAndUnknownPolarity(@TempDir Path dir) throws IOException {
        Path p = write(dir, "a.mgf", """
                BEGIN IONS
                PEPMASS=500.5
                100.0 10.0
                END IONS
                """);
        Row r = readAll(p).get(0);
        assertEquals(2, r.msLevel(), "MGF is an MS2-only peak list");
        assertEquals(0, r.ms1scan(), "hardcoded 0 (msql_fileloading.py:394) -> null downstream");
        assertEquals(0, r.polarity(), "C8: polarity is not read on the live path");
    }

    @Test
    void toleratesCrlfBlankLinesCommentsAndTabSeparatedPeaks(@TempDir Path dir) throws IOException {
        Path p = write(dir, "a.mgf",
                "# a comment\r\n\r\nBEGIN IONS\r\nPEPMASS=500.5\r\n\r\n"
                + "100.0\t10.0\r\n; another comment\r\n200.0  20.0\r\nEND IONS\r\n");
        List<Row> r = readAll(p);
        assertEquals(1, r.size());
        assertEquals(2, r.get(0).peaks());
    }

    @Test
    void zeroPeakBlocksAreEmittedAsEmptyScans(@TempDir Path dir) throws IOException {
        // Not hypothetical: 12,571 of PlusRise.mgf's 34,513 blocks have no peak lines. They are real
        // spectra and the reader must yield them; MassQL's dataframe simply has no ROWS for them,
        // which is why it reports 21,942 unique scans. Step 8's parity must expect that asymmetry.
        Path p = write(dir, "a.mgf", """
                BEGIN IONS
                PEPMASS=500.5
                END IONS
                BEGIN IONS
                PEPMASS=600.5
                100.0 10.0
                END IONS
                """);
        List<Row> r = readAll(p);
        assertEquals(2, r.size(), "the empty block is still a scan");
        assertEquals(0, r.get(0).peaks());
        assertEquals(1, r.get(1).peaks());
    }

    @Test
    void malformedPeakLineThrowsRatherThanSkipping(@TempDir Path dir) throws IOException {
        // Silently dropping a peak would change tic and base_peak and surface at Step 8 as a
        // decoder bug with no obvious cause.
        Path p = write(dir, "a.mgf", """
                BEGIN IONS
                PEPMASS=500.5
                100.0 10.0
                thisisnotapeak
                END IONS
                """);
        MassqlException e = assertThrows(MassqlException.class, () -> readAll(p));
        assertTrue(e.getMessage().contains("malformed peak line"), e.getMessage());
    }

    @Test
    void truncatedBlockThrowsWithNoPartialResult(@TempDir Path dir) throws IOException {
        Path p = write(dir, "a.mgf", """
                BEGIN IONS
                PEPMASS=500.5
                100.0 10.0
                """);
        MassqlException e = assertThrows(MassqlException.class, () -> readAll(p));
        assertTrue(e.getMessage().contains("END IONS"), e.getMessage());
    }

    @Test
    void materializeProducesAUsableSingleScanTable(@TempDir Path dir) throws IOException {
        Path p = write(dir, "a.mgf", """
                BEGIN IONS
                PEPMASS=500.5
                CHARGE=2+
                RTINSECONDS=90
                SCANS=7
                300.0 30.0
                100.0 10.0
                200.0 20.0
                END IONS
                """);
        try (SpectraStream s = SpectraFile.open(p)) {
            assertTrue(s.next());
            SpectrumTable t = s.current().materialize();
            assertEquals(1, t.index().scanCount(), "exactly one scan -- this is the streaming unit");
            assertEquals(7, t.index().scanIdAt(0));
            assertEquals(1.5, t.index().rtOf(0));
            assertEquals(500.5, t.index().precmzOf(0));
            assertEquals(2, t.index().chargeOf(0));
            assertEquals(0, t.index().ms1scanOf(0));
            // Step 5's builder sorts by ascending m/z, so windows work on the out-of-order input.
            assertEquals(100.0, t.mz(0));
            assertEquals(300.0, t.mz(2));
            assertEquals(10.0, t.intensity(0));
        }
    }
}
