package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.testsupport.Fixtures;
import org.cytoscape.massql.testsupport.Raw;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * MGF rules, all derived from the reference loader rather
 * than from the MGF format documentation — where the two differ, MassQL wins, because the parity gate asserts
 * bit-identity against what MassQL loaded.
 */
class MgfReaderTest {

    private static Path write(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    private record Row(
            int scan,
            double rt,
            double precmz,
            int charge,
            int ms1scan,
            int msLevel,
            int polarity,
            int peaks) {}

    private static List<Row> readAll(Path p) {
        List<Row> out = new ArrayList<>();
        try (SpectraStream s = SpectraFile.open(p)) {
            while (s.hasNext()) {
                ScanView v = s.next();
                out.add(
                        new Row(
                                v.scanId(),
                                v.rt(),
                                Raw.orZero(v.precmz()),
                                Raw.orZero(v.charge()),
                                Raw.orZero(v.ms1scan()),
                                v.msLevel(),
                                Raw.polarity(v.polarity()),
                                v.peaks().rowCount()));
            }
        }
        return out;
    }

    @Test
    void chargeFallsBackToOneOnlyWhenTheFileDeclaresNoneEither(@TempDir Path dir)
            throws IOException {
        // ⚠ MGF charge is NOT "null if absent": the reference loader uses
        // params.get('charge', [1]) with `except: charge = 1`. Since only 0
        // is
        // null-converted (collation), MGF charge is NEVER null -- a genuine 1+ and an absent
        // CHARGE are indistinguishable. 1 is the fallback for a file with no CHARGE anywhere; a
        // file-level header supplies a different default, which is the case below this one.
        Path p =
                write(
                        dir,
                        "a.mgf",
                        """
                BEGIN IONS
                TITLE=no charge here
                PEPMASS=500.5
                100.0 10.0
                END IONS
                """);
        assertEquals(1, readAll(p).get(0).charge());
    }

    @Test
    void theFileLevelChargeHeaderIsTheDefaultForBlocksWithoutTheirOwn(@TempDir Path dir)
            throws IOException {
        // ⛔ -- and it is a real file's behaviour, not a hypothetical:
        // DP00570_F02.mgf declares `CHARGE=2+ and 3+` once, then omits CHARGE= from 583 of its 625
        // blocks. The reference copies the file header into EVERY spectrum's params, so it sees
        // [2, 3] on all of them and takes element 0. Reading only per-block CHARGE= lines gave 1
        // for
        // 93% of that file.
        //
        // The multi-charge form is why the FIRST value is taken rather than the line rejected: the
        // 3
        // is never consulted by the reference.
        Path p =
                write(
                        dir,
                        "a.mgf",
                        """
                COM=converted from something
                CHARGE=2+ and 3+

                BEGIN IONS
                TITLE=inherits the file default
                PEPMASS=500.5
                100.0 10.0
                END IONS
                BEGIN IONS
                TITLE=overrides it
                PEPMASS=600.5
                CHARGE=3+
                100.0 10.0
                END IONS
                BEGIN IONS
                TITLE=inherits it again
                PEPMASS=700.5
                100.0 10.0
                END IONS
                """);
        List<Row> r = readAll(p);
        assertEquals(2, r.get(0).charge(), "no CHARGE= of its own -> the file-level default");
        assertEquals(3, r.get(1).charge(), "its own CHARGE= wins for this block only");
        assertEquals(2, r.get(2).charge(), "and the override does not leak into the next block");
    }

    @Test
    void onlyTheHeaderBeforeTheFirstBlockCounts(@TempDir Path dir) throws IOException {
        // A CHARGE= sitting between two blocks is not a file header -- the reference stops reading
        // the
        // header at the first BEGIN IONS. Treating it as one would silently re-default every
        // following block.
        Path p =
                write(
                        dir,
                        "a.mgf",
                        """
                CHARGE=2+

                BEGIN IONS
                PEPMASS=500.5
                100.0 10.0
                END IONS

                CHARGE=7+

                BEGIN IONS
                PEPMASS=600.5
                100.0 10.0
                END IONS
                """);
        List<Row> r = readAll(p);
        assertEquals(2, r.get(0).charge());
        assertEquals(2, r.get(1).charge(), "the stray CHARGE=7+ after block 1 must not be adopted");
    }

    @Test
    void theRealFixtureCarriesTheFileLevelDefault() {
        // The regression this fix exists for, asserted against the committed file rather than a
        // constructed one. Block 370 has no CHARGE= of its own; the golden says charge 2.
        List<Row> rows = readAll(Fixtures.require("data/DP00570_F02.mgf"));
        assertEquals(
                2, rows.get(369).charge(), "block 370 inherits the file-level CHARGE=2+ and 3+");
        assertEquals(2, rows.get(597).charge(), "and so does block 598");

        // The whole-file distribution, taken from the oracle itself:
        //     the reference loader -> {2: 583, 1: 42}
        // The 42 are the blocks carrying their own `CHARGE=1+`; the 583 inherit the header. Before
        // This once read {1: 625} and nothing noticed, because the loader-parity dumps did not
        // record
        // charge at all.
        Map<Integer, Long> byCharge =
                rows.stream().collect(Collectors.groupingBy(Row::charge, Collectors.counting()));
        assertEquals(
                Map.of(2, 583L, 1, 42L), byCharge, "charge distribution must match the oracle");
    }

    @Test
    void chargeStripsTrailingSign(@TempDir Path dir) throws IOException {
        Path p =
                write(
                        dir,
                        "a.mgf",
                        """
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
        Path p =
                write(
                        dir,
                        "a.mgf",
                        """
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
        assertEquals(
                r.get(1).precmz(), r.get(0).precmz(), "the trailing token must not change precmz");
    }

    @Test
    void retentionTimeIsSecondsOver60AndAbsentMeansZeroNotNull(@TempDir Path dir)
            throws IOException {
        // 0.0 is a REAL value here, not a missing one. plusrise_results.json has rt: 0.0 on all 664
        // records, so an over-eager null conversion would fail 664 rows at once (collation).
        Path p =
                write(
                        dir,
                        "a.mgf",
                        """
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
        // Getting this wrong shifts every row's identity and makes the differential
        // differential fail in a way that looks like a filtering bug.
        Path withScans =
                write(
                        dir,
                        "s.mgf",
                        """
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

        Path without =
                write(
                        dir,
                        "n.mgf",
                        """
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
    void everyScanIsMs2WithMs1scanZeroAndPositivePolarity(@TempDir Path dir) throws IOException {
        Path p =
                write(
                        dir,
                        "a.mgf",
                        """
                BEGIN IONS
                PEPMASS=500.5
                100.0 10.0
                END IONS
                """);
        Row r = readAll(p).get(0);
        assertEquals(2, r.msLevel(), "MGF is an MS2-only peak list");
        assertEquals(0, r.ms1scan(), "hardcoded 0 in the reference -> null downstream");

        // ⚠ No MGF header carries polarity, but that does NOT make it 0: both MGF loaders write
        // `"polarity": 1  # Default` into every peak dict
        // hardcoded, so the reference reports POSITIVE for every MGF row. Measured
        // across
        // all three MGF fixtures -- 7 + 107,178 + 758,544 rows -- the distribution is {1: all}, not
        // one 0.
        //
        // Caught by ReaderParityIT, the parity gate. Returning 0 would have failed the differential
        // differential on the polarity column for EVERY MGF row, where it would have looked like a
        // collation bug rather than a reader default.
        assertEquals(1, r.polarity(), "MGF polarity is a hardcoded 1 (positive), not 0");
    }

    @Test
    void toleratesCrlfBlankLinesCommentsAndTabSeparatedPeaks(@TempDir Path dir) throws IOException {
        Path p =
                write(
                        dir,
                        "a.mgf",
                        "# a comment\r\n\r\nBEGIN IONS\r\nPEPMASS=500.5\r\n\r\n"
                                + "100.0\t10.0\r\n; another comment\r\n200.0  20.0\r\nEND IONS\r\n");
        List<Row> r = readAll(p);
        assertEquals(1, r.size());
        assertEquals(2, r.get(0).peaks());
    }

    @Test
    void zeroPeakBlocksAreEmittedAsEmptyScans(@TempDir Path dir) throws IOException {
        // Not hypothetical: 12,571 of PlusRise.mgf's 34,513 blocks have no peak lines. They are
        // real
        // spectra and the reader must yield them; MassQL's dataframe simply has no ROWS for them,
        // which is why it reports 21,942 unique scans. The parity gate must expect that asymmetry.
        Path p =
                write(
                        dir,
                        "a.mgf",
                        """
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
        // Silently dropping a peak would change tic and base_peak and surface at the parity gate as
        // a
        // decoder bug with no obvious cause.
        Path p =
                write(
                        dir,
                        "a.mgf",
                        """
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
        Path p =
                write(
                        dir,
                        "a.mgf",
                        """
                BEGIN IONS
                PEPMASS=500.5
                100.0 10.0
                """);
        MassqlException e = assertThrows(MassqlException.class, () -> readAll(p));
        assertTrue(e.getMessage().contains("END IONS"), e.getMessage());
    }

    @Test
    void materializeProducesAUsableSingleScanTable(@TempDir Path dir) throws IOException {
        Path p =
                write(
                        dir,
                        "a.mgf",
                        """
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
            assertTrue(s.hasNext());
            ScanView v = s.next();
            SpectrumTable t = v.peaks();
            assertEquals(
                    1, t.index().scanCount(), "exactly one scan -- this is the streaming unit");
            assertEquals(7, t.index().scanIdAt(0));
            assertEquals(1.5, t.index().rtOf(0));
            assertEquals(500.5, t.index().precmzOf(0));
            assertEquals(2, t.index().chargeOf(0));
            assertEquals(0, t.index().ms1scanOf(0));
            // the store's builder sorts by ascending m/z, so windows work on the out-of-order
            // input.
            assertEquals(100.0, t.mz(0));
            assertEquals(300.0, t.mz(2));
            assertEquals(10.0, t.intensity(0));
        }
    }
}
