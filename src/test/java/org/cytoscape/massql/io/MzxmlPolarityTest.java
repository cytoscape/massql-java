package org.cytoscape.massql.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.cytoscape.massql.testsupport.Fixtures;
import org.cytoscape.massql.testsupport.Raw;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
            int scans = 0;
            try (SpectraStream s = SpectraFile.open(Fixtures.require("data/small.mzXML"))) {
                while (s.hasNext()) {
                    ScanView v = s.next();
                    assertEquals(
                            1,
                            Raw.polarity(v.polarity()),
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
            assertEquals(
                    0,
                    MzxmlReader.polarityOf(null),
                    "our defensive default; MassQL raises KeyError here, so this is not parity");
        }

        @Test
        void aFileWithNoPolarityAttributeReadsCleanly() {
            int scans = 0;
            try (SpectraStream s =
                    SpectraFile.open(Fixtures.require("fixtures/micro/micro_nopolarity.mzXML"))) {
                while (s.hasNext()) {
                    ScanView v = s.next();
                    assertNull(v.polarity());
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
                                                Raw.orZero(v.ms1scan()),
                                                v.rt(),
                                                Raw.orZero(v.precmz()),
                                                Raw.orZero(v.charge()),
                                                v.peaks().rowCount()));
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
