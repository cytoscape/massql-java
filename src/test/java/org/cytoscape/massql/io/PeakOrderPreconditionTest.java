package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.testsupport.Fixtures;
import org.cytoscape.massql.testsupport.ParityDump;
import org.cytoscape.massql.testsupport.ParityFixtures;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PeakOrderPreconditionTest {
    static List<String> fixtures() {
        return ParityFixtures.fixtures();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void massqlsOwnPeakOrderIsAscending(String fixture) {
        ParityDump dump = ParityDump.of(fixture);
        for (ParityDump.Scan s : dump.scans().values()) {
            List<String> hex = s.mzHexFirst8();
            for (int i = 1; i < hex.size(); i++) {
                double prev = ParityDump.parseHex(hex.get(i - 1));
                double cur = ParityDump.parseHex(hex.get(i));
                assertTrue(
                        cur >= prev,
                        fixture
                                + " "
                                + s.key()
                                + ": MassQL's m/z array DESCENDS at index "
                                + i
                                + " ("
                                + prev
                                + " -> "
                                + cur
                                + ").\n"
                                + "  SpectrumTableBuilder sorts unsorted scans by m/z, so our array order "
                                + "would no longer match MassQL's file order, and ReaderParityIT's "
                                + "ORDER-SENSITIVE digest comparison is no longer valid for this fixture.\n"
                                + "  Fix the harness by comparing a multiset for this fixture -- do NOT"
                                + " assume the digest failure is a decode bug.");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void ourMaterialisedPeaksAreAscending(String fixture) {
        try (SpectraStream s =
                SpectraFile.open(
                        Fixtures.require(
                                fixture.startsWith("micro")
                                        ? "fixtures/micro/" + fixture
                                        : "data/" + fixture))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                SpectrumTable t = v.peaks();
                for (int i = 1; i < t.rowCount(); i++) {
                    assertTrue(
                            t.mz(i) >= t.mz(i - 1),
                            fixture
                                    + " scan "
                                    + v.scanId()
                                    + ": m/z descends at row "
                                    + i
                                    + " ("
                                    + t.mz(i - 1)
                                    + " -> "
                                    + t.mz(i)
                                    + "); the mz-window binary "
                                    + "search requires ascending order");
                }
            }
        }
    }
}
