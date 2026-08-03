package edu.ucsd.idekerlab.massql.io;

import static org.junit.jupiter.api.Assertions.*;

import edu.ucsd.idekerlab.massql.MassqlException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Format is sniffed from CONTENT, never from the extension.
 *
 * <p>The fixtures disagree on case — msconvert writes {@code small.mzXML}, the Ewing download is
 * {@code DP00570_F02.mzxml} — and SPIKE.md uses both spellings, so an extension-driven reader is a
 * trap. These tests deliberately use misleading extensions.
 */
class FormatSniffTest {

    private static Path write(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    @Test
    void contentWinsOverAMisleadingExtension(@TempDir Path dir) throws IOException {
        Path mgf = write(dir, "actually_mgf.mzML", "BEGIN IONS\nPEPMASS=1.0\n100.0 1.0\nEND IONS\n");
        assertEquals(Format.MGF, SpectraFile.sniff(mgf));

        Path mzml = write(dir, "actually_mzml.mgf",
                "<?xml version=\"1.0\"?>\n<indexedmzML><mzML version=\"1.1.0\"></mzML></indexedmzML>");
        assertEquals(Format.MZML, SpectraFile.sniff(mzml));

        Path mzxml = write(dir, "actually_mzxml.txt",
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
        // Both real MGF fixtures carry a COM=/CHARGE= header before the first BEGIN IONS, so
        // looking only at the first non-blank line would misclassify them.
        Path p = write(dir, "a.dat",
                "COM=Conversion of DP00570_F02.mzXML to mascot generic\nCHARGE=2+ and 3+\n\nBEGIN IONS\n");
        assertEquals(Format.MGF, SpectraFile.sniff(p));
    }

    @Test
    void unknownContentThrowsAndNamesTheFile(@TempDir Path dir) throws IOException {
        Path p = write(dir, "mystery.xml", "<?xml version=\"1.0\"?>\n<somethingElse><a/></somethingElse>");
        MassqlException e = assertThrows(MassqlException.class, () -> SpectraFile.sniff(p));
        assertTrue(e.getMessage().contains("mystery.xml"), e.getMessage());
        assertTrue(e.getMessage().contains("cannot determine format"), e.getMessage());
    }

    @Test
    void missingEmptyAndDirectoryPathsFailClearly(@TempDir Path dir) throws IOException {
        MassqlException missing = assertThrows(MassqlException.class,
                () -> SpectraFile.open(dir.resolve("nope.mzML")));
        assertTrue(missing.getMessage().contains("no such file"), missing.getMessage());

        Path empty = write(dir, "empty.mgf", "");
        assertTrue(assertThrows(MassqlException.class, () -> SpectraFile.open(empty))
                .getMessage().contains("empty"));

        assertTrue(assertThrows(MassqlException.class, () -> SpectraFile.open(dir))
                .getMessage().contains("directory"));

        assertThrows(MassqlException.class, () -> SpectraFile.open(null));
    }

    @Test
    void mzxmlIsSniffedAndReadable(@TempDir Path dir) throws IOException {
        // Step 7 supplied the reader, so this no longer asserts a "not yet implemented" throw.
        // A scanCount="0" msRun is valid and simply yields nothing.
        Path p = write(dir, "a.mzXML", "<mzXML><msRun scanCount=\"0\"></msRun></mzXML>");
        assertEquals(Format.MZXML, SpectraFile.sniff(p));
        try (SpectraStream s = SpectraFile.open(p)) {
            assertEquals(Format.MZXML, s.format());
            assertFalse(s.next(), "an empty msRun yields no scans");
        }
    }

    @Test
    void allThreeFormatsOpen(@TempDir Path dir) {
        // The wiring assertion: every Format now maps to a reader, so a future fourth enum constant
        // fails here rather than at a user's first open().
        for (Format f : Format.values()) {
            Path p = switch (f) {
                case MGF -> Fixtures.require("fixtures/micro/micro.mgf");
                case MZML -> Fixtures.require("fixtures/micro/micro.mzML");
                case MZXML -> Fixtures.require("fixtures/micro/micro.mzXML");
            };
            try (SpectraStream s = SpectraFile.open(p)) {
                assertEquals(f, s.format());
                assertTrue(s.next(), f + " reader yielded no scans");
            }
        }
    }

    @Test
    void realFixturesSniffCorrectly() {
        assertEquals(Format.MGF, SpectraFile.sniff(Fixtures.require("data/PlusRise.mgf")));
        assertEquals(Format.MZML, SpectraFile.sniff(Fixtures.require("data/small.mzML")));
        assertEquals(Format.MZXML, SpectraFile.sniff(Fixtures.require("data/small.mzXML")));
    }
}
