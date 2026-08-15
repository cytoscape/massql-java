package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.testsupport.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FormatSniffTest {
    private static Path write(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void contentWinsOverAMisleadingExtension(@TempDir Path dir) throws IOException {
        Path mgf =
                write(dir, "actually_mgf.mzML", "BEGIN IONS\nPEPMASS=1.0\n100.0 1.0\nEND IONS\n");
        assertEquals(Format.MGF, SpectraFile.sniff(mgf));

        Path mzml =
                write(
                        dir,
                        "actually_mzml.mgf",
                        "<?xml version=\"1.0\"?>\n<indexedmzML><mzML version=\"1.1.0\"></mzML></indexedmzML>");
        assertEquals(Format.MZML, SpectraFile.sniff(mzml));

        Path mzxml =
                write(
                        dir,
                        "actually_mzxml.txt",
                        "<?xml version=\"1.0\"?>\n<mzXML xmlns=\"http://sashimi.sourceforge.net/schema_revision/mzXML_2.0\">");
        assertEquals(Format.MZXML, SpectraFile.sniff(mzxml));
    }

    @Test
    void bothMzxmlSpellingsAndTheMsRunRootAreRecognised(@TempDir Path dir) throws IOException {
        assertEquals(Format.MZXML, SpectraFile.sniff(write(dir, "a.mzXML", "<mzXML>")));
        assertEquals(Format.MZXML, SpectraFile.sniff(write(dir, "b.mzxml", "<MZXML>")));
        assertEquals(Format.MZXML, SpectraFile.sniff(write(dir, "c.x", "<msRun scanCount=\"1\">")));
    }

    @Test
    void mgfPreambleBeforeTheFirstBlockIsTolerated(@TempDir Path dir) throws IOException {
        Path p =
                write(
                        dir,
                        "a.dat",
                        "COM=Conversion of DP00570_F02.mzXML to mascot generic\nCHARGE=2+ and 3+\n\nBEGIN IONS\n");
        assertEquals(Format.MGF, SpectraFile.sniff(p));
    }

    @Test
    void textWithNoMarkupIsTreatedAsAPeakList(@TempDir Path dir) throws IOException {
        Path p =
                write(dir, "headerless.dat", "COM=a very long preamble\nCHARGE=2+\n123.4 5678.9\n");
        assertEquals(Format.MGF, SpectraFile.sniff(p));
    }

    @Test
    void unknownContentThrowsAndNamesTheFile(@TempDir Path dir) throws IOException {
        Path p =
                write(
                        dir,
                        "mystery.xml",
                        "<?xml version=\"1.0\"?>\n<somethingElse><a/></somethingElse>");
        MassqlException e = assertThrows(MassqlException.class, () -> SpectraFile.sniff(p));
        assertTrue(e.getMessage().contains("mystery.xml"), e.getMessage());
        assertTrue(e.getMessage().contains("cannot determine format"), e.getMessage());
    }

    @Test
    void missingEmptyAndDirectoryPathsFailClearly(@TempDir Path dir) throws IOException {
        MassqlException missing =
                assertThrows(
                        MassqlException.class, () -> SpectraFile.open(dir.resolve("nope.mzML")));
        assertTrue(missing.getMessage().contains("no such file"), missing.getMessage());

        Path empty = write(dir, "empty.mgf", "");
        assertTrue(
                assertThrows(MassqlException.class, () -> SpectraFile.open(empty))
                        .getMessage()
                        .contains("empty"));

        assertTrue(
                assertThrows(MassqlException.class, () -> SpectraFile.open(dir))
                        .getMessage()
                        .contains("directory"));

        assertThrows(MassqlException.class, () -> SpectraFile.open(null));
    }

    @Test
    void mzxmlIsSniffedAndReadable(@TempDir Path dir) throws IOException {
        Path p = write(dir, "a.mzXML", "<mzXML><msRun scanCount=\"0\"></msRun></mzXML>");
        assertEquals(Format.MZXML, SpectraFile.sniff(p));
        try (SpectraStream s = SpectraFile.open(p)) {
            assertEquals(Format.MZXML, formatOf(s));
            assertFalse(s.hasNext(), "an empty msRun yields no scans");
        }
    }

    @Test
    void allThreeFormatsOpen(@TempDir Path dir) {
        for (Format f : Format.values()) {
            Path p =
                    switch (f) {
                        case MGF -> Fixtures.require("fixtures/micro/micro.mgf");
                        case MZML -> Fixtures.require("fixtures/micro/micro.mzML");
                        case MZXML -> Fixtures.require("fixtures/micro/micro.mzXML");
                    };
            try (SpectraStream s = SpectraFile.open(p)) {
                assertEquals(f, formatOf(s), "open() chose the wrong reader for " + f);
                assertTrue(s.hasNext(), f + " reader yielded no scans");
            }
        }
    }

    private static Format formatOf(SpectraStream s) {
        if (s instanceof MgfReader) return Format.MGF;
        if (s instanceof MzmlReader) return Format.MZML;
        if (s instanceof MzxmlReader) return Format.MZXML;
        throw new AssertionError(
                "unrecognised reader "
                        + s.getClass().getName()
                        + " -- a fourth Format was added without extending this mapping");
    }

    @Test
    void realFixturesSniffCorrectly() {
        assertEquals(Format.MGF, SpectraFile.sniff(Fixtures.require("data/PlusRise.mgf")));
        assertEquals(Format.MZML, SpectraFile.sniff(Fixtures.require("data/small.mzML")));
        assertEquals(Format.MZXML, SpectraFile.sniff(Fixtures.require("data/small.mzXML")));
    }
}
