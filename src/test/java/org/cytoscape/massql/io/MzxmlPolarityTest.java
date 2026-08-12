package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * mzXML polarity, with the parity claims and the non-parity ones deliberately kept apart.
 *
 * <p><b>Why the split matters.</b> the mzXML reader originally specified
 * {@code "+"} → 1, {@code "-"} → 2, <i>absent</i> → 0 as one rule. But
 * {@code _determine_scan_polarity_mzXML} reads {@code spec["polarity"]} <b>unguarded</b>
 * (`msql_fileloading.py:517-523`), so an absent attribute raises {@code KeyError} and MassQL produces no
 * output at all — verified by running its loader over {@code micro_nopolarity.mzXML}:
 *
 * <pre>
 *   KeyError: 'polarity'
 * </pre>
 *
 * <p>So there is <b>no golden</b> for the absent case, and a test asserting 0 there is pinning
 * <i>our</i> contract, not agreement with MassQL. What the {@code polarity = 0} initialiser genuinely
 * covers is <i>present but neither {@code +} nor {@code -}</i>. Lumping the two together would let a
 * green suite imply parity we do not have, which is why they are separate nested classes here.
 *
 * <p>The reason this is easy to get wrong: {@code empty_msLevel_tag.mzXML} contains 8 scans with no
 * {@code polarity} attribute and MassQL reads it without error — but only because those same scans carry
 * {@code msLevel=""} and are dropped <i>before</i> the polarity call. Change the ms level and it raises.
 */
class MzxmlPolarityTest {

    @Nested
    class Parity {

        @Test
        void plusIsOneAndMinusIsTwo() {
            assertEquals(1, MzxmlReader.polarityOf("+"));
            assertEquals(2, MzxmlReader.polarityOf("-"));
        }

        @Test
        void presentButNeitherPlusNorMinusIsZero() {
            // This IS parity: MassQL initialises polarity = 0 and only overwrites on "+" or "-",
            // so any other present value falls through to 0 without raising.
            assertEquals(0, MzxmlReader.polarityOf("any"));
            assertEquals(0, MzxmlReader.polarityOf(""));
            assertEquals(0, MzxmlReader.polarityOf("0"));
            assertEquals(0, MzxmlReader.polarityOf("+-"));
        }

        @Test
        void surroundingWhitespaceIsTolerated() {
            assertEquals(1, MzxmlReader.polarityOf(" + "));
            assertEquals(2, MzxmlReader.polarityOf("\t-\n"));
        }

        @Test
        void theRealFixtureIsAllPositive() {
            // small.mzXML declares polarity="+" on all 48 scans, so every scan must report 1. If
            // this
            // ever reports 0, the attribute is not being read at all -- and because absent also
            // maps
            // to 0, that failure would otherwise be invisible.
            int scans = 0;
            try (SpectraStream s = SpectraFile.open(Fixtures.require("data/small.mzXML"))) {
                while (s.hasNext()) {
                    ScanView v = s.next();
                    assertEquals(
                            1,
                            v.polarity(),
                            "scan " + v.scanId() + ": small.mzXML is polarity=\"+\" throughout");
                    scans++;
                }
            }
            assertEquals(48, scans);
        }
    }

    @Nested
    class OurContractNotParity {

        @Test
        void absentPolarityIsZeroAndMassqlWouldHaveRaised() {
            // NO GOLDEN EXISTS for this. MassQL raises KeyError: 'polarity' on
            // micro_nopolarity.mzXML
            // (verified). We choose 0 -- the same "not recorded" value the format's own absent case
            // gets in mzML -- because crashing on a legal optional attribute is worse behaviour
            // than
            // the reference, not better.
            assertEquals(
                    0,
                    MzxmlReader.polarityOf(null),
                    "our defensive default; MassQL raises KeyError here, so this is not parity");
        }

        @Test
        void aFileWithNoPolarityAttributeReadsCleanly() {
            // End to end, not just the helper: micro_nopolarity.mzXML omits the attribute on every
            // scan. The reader must produce all five scans with polarity 0 rather than throwing.
            int scans = 0;
            try (SpectraStream s =
                    SpectraFile.open(Fixtures.require("fixtures/micro/micro_nopolarity.mzXML"))) {
                while (s.hasNext()) {
                    ScanView v = s.next();
                    assertEquals(0, v.polarity());
                    scans++;
                }
            }
            assertEquals(
                    5,
                    scans,
                    "all five scans must still be read; MassQL cannot read this file at all");
        }

        @Test
        void everythingElseAboutThatFileIsUnchanged() {
            // The absent attribute must not perturb anything else -- otherwise "polarity 0" could
            // be
            // masking a broken parse. Compare against the baseline fixture, ignoring polarity.
            record Row(
                    int scan,
                    int level,
                    int ms1scan,
                    double rt,
                    double precmz,
                    int charge,
                    int peaks) {}
            java.util.function.Function<String, java.util.List<Row>> read =
                    name -> {
                        java.util.List<Row> out = new java.util.ArrayList<>();
                        try (SpectraStream s =
                                SpectraFile.open(Fixtures.require("fixtures/micro/" + name))) {
                            while (s.hasNext()) {
                                ScanView v = s.next();
                                out.add(
                                        new Row(
                                                v.scanId(),
                                                v.msLevel(),
                                                v.ms1scan(),
                                                v.rt(),
                                                v.precmz(),
                                                v.charge(),
                                                v.materialize().rowCount()));
                            }
                        }
                        return out;
                    };
            assertEquals(
                    read.apply("micro.mzXML"),
                    read.apply("micro_nopolarity.mzXML"),
                    "omitting polarity changed something other than polarity");
        }
    }
}
