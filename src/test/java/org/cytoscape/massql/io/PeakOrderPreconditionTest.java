package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.cytoscape.massql.spectra.SpectrumTable;
import org.cytoscape.massql.testsupport.Fixtures;
import org.cytoscape.massql.testsupport.ParityDump;
import org.cytoscape.massql.testsupport.ParityFixtures;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The precondition that makes {@code ReaderParityIT}'s digest comparison valid.
 *
 * <p><b>Why this exists.</b> The parity gate compares SHA-256 over each peak array, which is
 * <b>order-sensitive</b> — strictly stronger than a multiset, and the reason replaced the
 * spec's multiset language. But order-sensitivity is only *correct* if our array order equals MassQL's file
 * order, and {@code SpectrumTableBuilder} <b>sorts a scan by m/z when it is not already ascending</b>
 * ({@code SpectrumTableBuilder:174-176}). If a fixture ever contained descending peaks, that sort would
 * silently reorder our array and the digest would fail — presenting as a decode bug when it is nothing of
 * the kind.
 *
 * <p>Measured today across every fixture: <b>zero scans have descending m/z</b>, so the sort never fires.
 * This test pins that, from both directions:
 *
 * <ul>
 *   <li><b>Our side</b> — the materialised m/z is non-decreasing within every scan.</li>
 *   <li><b>MassQL's side</b> — each dump's {@code mz_hex_first8} is non-decreasing, which is MassQL's own
 *       file-order view. If a future fixture arrives unsorted, this is what fails, and it fails saying
 *       exactly why the digest comparison is no longer valid.</li>
 * </ul>
 *
 * <p>Checking only our side would be circular: the builder sorts, so our arrays are ascending <i>by
 * construction</i>. The dump side is the half that carries information.
 */
class PeakOrderPreconditionTest {

    static List<String> fixtures() {
        return ParityFixtures.fixtures();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void massqlsOwnPeakOrderIsAscending(String fixture) {
        // The informative half. mz_hex_first8 is MassQL's array in FILE order, untouched by our
        // builder.
        // A descending pair here means the fixture stores peaks out of m/z order, our builder would
        // sort
        // it, and ReaderParityIT's digests would no longer be comparing like with like.
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
        // The complement. True by construction today (the builder sorts), so on its own it proves
        // little --
        // but it would catch a future change that emitted peaks unsorted, which would break the
        // mz-window
        // binary search in the store as well as the digests.
        try (SpectraStream s =
                SpectraFile.open(
                        Fixtures.require(
                                fixture.startsWith("micro")
                                        ? "fixtures/micro/" + fixture
                                        : "data/" + fixture))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                SpectrumTable t = v.materialize();
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
