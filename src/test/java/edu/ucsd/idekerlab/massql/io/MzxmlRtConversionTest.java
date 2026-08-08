package edu.ucsd.idekerlab.massql.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import edu.ucsd.idekerlab.massql.MassqlException;

/**
 * mzXML retention time: an ISO-8601 duration, <b>always</b> converted to minutes.
 *
 * <p><b>This is the 60x trap, and mzXML is the format that catches it in the opposite direction.</b>
 * mzML's conversion is <i>conditional</i> on the declared unit ({@code MzmlRtUnitTest}); mzXML's is
 * unconditional; MGF divides {@code RTINSECONDS} by 60 with an absent value defaulting to 0.0. Three
 * formats, three rules — so a reader that shares one code path passes two of the three suites and is
 * silently 60x wrong on the third.
 *
 * <p>Every expectation below was produced by running pyteomics' own
 * {@code XMLValueConverter.duration_str_to_float}, not derived by hand. Its arithmetic is
 * {@code minutes = M; minutes += H*60.; minutes += S/60.} (`pyteomics/xml.py:136-141`) and the ORDER
 * matters for the last bit — MassQL then uses the value as-is.
 */
class MzxmlRtConversionTest {

    private static void rt(String duration, double expected) {
        assertEquals(
                expected,
                MzxmlReader.retentionTimeMinutes(duration),
                "retentionTime=\"" + duration + "\"");
    }

    @Test
    void theFourFormsFromTheSpec() {
        rt("PT90S", 1.5);
        rt("PT1.38S", 0.023);
        rt("PT1M30S", 1.5);
        rt("PT1H", 60.0);
    }

    @Test
    void pt138sIsBitExact() {
        // 1.38/60 happens to land exactly on the double nearest 0.023, but that is a fact to verify
        // rather than assume: the differential compares rt bit-for-bit, so a reordering of the
        // arithmetic that shifted one bit would fail there and be hard to trace back to here.
        assertEquals(
                Double.doubleToLongBits(0.023),
                Double.doubleToLongBits(MzxmlReader.retentionTimeMinutes("PT1.38S")),
                "PT1.38S must be the same double pyteomics produces, to the bit");
    }

    @Test
    void combinedComponentsAccumulateInPyteomicsOrder() {
        rt("PT1H30M45S", 90.75);
        rt("PT0.5M", 0.5);
        rt("PT0S", 0.0);
    }

    @Test
    void yearsMonthsAndDaysAreParsedThenIgNored() {
        // Quirk 1, verified against pyteomics: only H/M/S after the T contribute. A reader that
        // "correctly" honoured the day component would report 1500 minutes where MassQL reports 60.
        rt("P1DT1H", 60.0);
        rt("P1Y2M3DT1H", 60.0);
    }

    @Test
    void mBeforeTIsMonthsAndAfterTIsMinutes() {
        // Quirk 2: the same letter means different things either side of the T, and the months
        // value
        // is discarded. Getting this backwards makes every P<n>M file read as n minutes.
        rt("P1M", 0.0);
        rt("PT1M", 1.0);
    }

    @Test
    void degenerateFormsAreZeroNotAnError() {
        // pyteomics' regex has every group optional and uses search(), so these match emptily.
        rt("P", 0.0);
        rt("PT", 0.0);
        // Trailing garbage after a complete match is ignored: search() stops at the first match.
        rt("PT1M1M", 1.0);
    }

    @Test
    void aBareNumberIsUsedAsIs() {
        // pyteomics: not starting with 'P' -> unitfloat(s). MassQL then treats it as minutes.
        rt("1.5", 1.5);
        rt("0", 0.0);
    }

    @Test
    void absentOrEmptyIsZero() {
        rt(null, 0.0);
        rt("", 0.0);
        rt("   ", 0.0);
    }

    @Test
    void aNegativeDurationIsRefusedRatherThanInvented() {
        // Quirk 3, and a DOCUMENTED DEVIATION. "-PT90S" does not start with 'P', so pyteomics falls
        // through to float(), fails, and returns the raw STRING -- MassQL would then carry a
        // non-numeric rt. Verified: duration_str_to_float("-PT90S") == '-PT90S'.
        //
        // There is no numeric golden to match, so we refuse instead of guessing. Returning +1.5
        // (ignoring the sign, as the regex does) or -1.5 would both be inventions.
        MassqlException e =
                assertThrows(
                        MassqlException.class, () -> MzxmlReader.retentionTimeMinutes("-PT90S"));
        assertTrue(e.getMessage().contains("retentionTime"), e.getMessage());
        assertTrue(
                e.getMessage().contains("string"),
                "the message should explain that pyteomics yields a string here: "
                        + e.getMessage());
    }

    @Test
    void theFixtureRoundTripsThroughTheReader() {
        // Not just the parser in isolation: the micro table's RTs are 0.0/0.5/1.0/1.5/2.0 minutes,
        // written as PT{sec}S, so the reader must give those minutes back.
        double[] want = {0.0, 0.5, 1.0, 1.5, 2.0};
        int i = 0;
        try (SpectraStream s = SpectraFile.open(Fixtures.require("fixtures/micro/micro.mzXML"))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                assertEquals(want[i], v.rt(), 1e-12, "scan " + v.scanId() + " rt");
                i++;
            }
        }
        assertEquals(5, i);
    }

    @Test
    void mzxmlAndMzmlDoNotShareTheRule() {
        // The cross-format guard. micro.mzML declares unitName="minute" so its RT is NOT converted;
        // micro.mzXML writes the same times as PT{sec}S and IS converted. Both must land on the
        // same
        // minutes. If someone unifies the two code paths, one of these two reads goes 60x wrong and
        // this test says which.
        double[] fromMzml = rtsOf("fixtures/micro/micro.mzML");
        double[] fromMzxml = rtsOf("fixtures/micro/micro.mzXML");
        assertArrayEquals(
                fromMzml,
                fromMzxml,
                1e-12,
                "mzML (conditional, unit=minute -> unchanged) and mzXML (always /60) must agree");
        assertArrayEquals(new double[] {0.0, 0.5, 1.0, 1.5, 2.0}, fromMzxml, 1e-12);
    }

    private static double[] rtsOf(String fixture) {
        java.util.List<Double> out = new java.util.ArrayList<>();
        try (SpectraStream s = SpectraFile.open(Fixtures.require(fixture))) {
            while (s.hasNext()) {
                ScanView v = s.next();
                out.add(v.rt());
            }
        }
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
