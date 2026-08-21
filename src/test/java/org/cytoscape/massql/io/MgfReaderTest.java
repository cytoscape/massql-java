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

import org.cytoscape.massql.Massql;
import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.testsupport.Fixtures;
import org.cytoscape.massql.testsupport.Raw;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        List<Row> rows = readAll(Fixtures.require("data/DP00570_F02.mgf"));
        assertEquals(
                2, rows.get(369).charge(), "block 370 inherits the file-level CHARGE=2+ and 3+");
        assertEquals(2, rows.get(597).charge(), "and so does block 598");

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

    /**
     * Ordinal numbering is a first-class mode, not a degraded one: GNPS and mzmine both emit MGFs
     * without {@code SCANS=}, and the position-derived ids are what a downstream network's scan
     * column is expected to carry. So the sequence has to be exactly 1..N with no gaps.
     */
    @Test
    void blockIndexNumberingIsDenseAndOneBased(@TempDir Path dir) throws IOException {
        StringBuilder mgf = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            mgf.append("BEGIN IONS\nPEPMASS=").append(i).append(".0\n100.0 10.0\nEND IONS\n");
        }

        List<Row> rows = readAll(write(dir, "ordinal.mgf", mgf.toString()));

        assertEquals(5, rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i + 1, rows.get(i).scan(), "block " + (i + 1) + " is scan " + (i + 1));
        }
    }

    /**
     * {@code SCANS=} is only honoured when it is a positive number, so a file that writes 0 or a
     * negative falls back to the block index rather than emitting a scan id no network could join
     * against.
     */
    @ParameterizedTest(name = "SCANS={0}")
    @ValueSource(strings = {"0", "-5"})
    void aNonPositiveScansHeaderFallsBackToTheBlockIndex(String scans, @TempDir Path dir)
            throws IOException {
        String mgf =
                """
                BEGIN IONS
                PEPMASS=1.0
                SCANS=%s
                100.0 10.0
                END IONS
                BEGIN IONS
                PEPMASS=2.0
                SCANS=%s
                100.0 10.0
                END IONS
                """
                        .formatted(scans, scans);

        List<Row> rows = readAll(write(dir, "nonpositive.mgf", mgf));

        assertEquals(1, rows.get(0).scan());
        assertEquals(2, rows.get(1).scan());
    }

    private static final String MIXED_NUMBERING =
            """
            BEGIN IONS
            PEPMASS=1.0
            SCANS=100
            100.0 10.0
            END IONS
            BEGIN IONS
            PEPMASS=2.0
            100.0 10.0
            END IONS
            """;

    /**
     * The numbering rule is per block, and the reader applies it without looking at its neighbours:
     * an explicit id on one block does not renumber the blocks around it.
     */
    @Test
    void theNumberingRuleIsAppliedPerBlock(@TempDir Path dir) throws IOException {
        List<Row> rows = readAll(write(dir, "mixed.mgf", MIXED_NUMBERING));

        assertEquals(100, rows.get(0).scan(), "the explicit id stands");
        assertEquals(2, rows.get(1).scan(), "and the next block is still numbered by position");
    }

    /**
     * Which means a file mixing the two schemes can descend, and a query over it fails rather than
     * returning rows a caller would join against the wrong nodes. The reader is not the layer that
     * notices -- collation is -- so this only shows up once a query runs.
     */
    @Test
    void aFileMixingBothSchemesFailsTheQueryRatherThanMisalignIt(@TempDir Path dir)
            throws IOException {
        Path p = write(dir, "mixed.mgf", MIXED_NUMBERING);

        MassqlException e =
                assertThrows(
                        MassqlException.class,
                        () -> Massql.run("QUERY scaninfo(MS2DATA)", p, null));
        assertTrue(e.getMessage().contains("out of order"), e.getMessage());
    }

    @Test
    void scanIdIsScansWhenPresentElseTheOneBasedBlockIndex(@TempDir Path dir) throws IOException {
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

            assertEquals(100.0, t.mz(0));
            assertEquals(300.0, t.mz(2));
            assertEquals(10.0, t.intensity(0));
        }
    }
}
