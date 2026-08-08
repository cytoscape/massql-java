package edu.ucsd.idekerlab.massql.io;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The inventory of fixtures that have a MassQL parity dump, and what each one's reader-only scan
 * count should be.
 *
 * <p>This is <b>fixture data, not test logic</b> — a set of measured facts about files committed to
 * this repository. It lives here, next to {@link Fixtures}, because three separate tests need it and
 * they do not all live in the same source set: {@code ReaderParityIT} consumes it as an integration
 * test, while {@code ReaderParityHarnessTest} and {@code PeakOrderPreconditionTest} are unit tests
 * that assert properties of the inventory itself and of the dumps it names.
 *
 * <p>It previously lived inside {@code ReaderParityIT}, which made the unit suite depend on an IT
 * class. That worked only while both compiled together; once integration tests moved to their own
 * source set the direction became impossible, since {@code integrationTest} already depends on
 * {@code test}.
 */
final class ParityFixtures {

    private ParityFixtures() {}

    /**
     * Fixture → expected number of reader scans with no dump entry (all of which must have zero
     * peaks).
     *
     * <p>Asserting this number is what stops a reader that drops real spectra from passing. The
     * values are facts about the fixtures: MassQL discards a scan whose intensity array is empty, so
     * the delta is exactly the count of empty scans.
     *
     * <p><b>Deliberately absent</b>: {@code micro_nopolarity.mzXML} and {@code
     * micro_noprecursor.mzXML} — MassQL raises {@code KeyError} on both (C27c), so no dump can exist
     * and parity is not available. They pin our own contract in {@code MzxmlPolarityTest} / {@code
     * MzxmlEdgeCaseTest}. Also absent: {@code micro_multiprec.mzML}, whose peak parity the mzXML
     * twin already establishes.
     */
    static final Map<String, Integer> FIXTURES_WITH_DUMPS = new LinkedHashMap<>();

    static {
        // Real fixtures.
        FIXTURES_WITH_DUMPS.put("small.mzML", 0);
        FIXTURES_WITH_DUMPS.put("small.mzXML", 0);
        FIXTURES_WITH_DUMPS.put("DP00570_F02.mzxml", 0);
        FIXTURES_WITH_DUMPS.put("DP00570_F02.mgf", 0);
        // 12,571 of PlusRise's 34,513 blocks carry no peak lines (C24b). MassQL loads 21,942.
        FIXTURES_WITH_DUMPS.put("PlusRise.mgf", 12_571);
        // Micro fixtures: scan 4 is a zero-peak MS1, so each mzML/mzXML variant has exactly one extra.
        // micro.mgf is MS2-only, so its zero-peak MS1 never existed on our side -- hence 0, not 1.
        FIXTURES_WITH_DUMPS.put("micro.mzML", 1);
        FIXTURES_WITH_DUMPS.put("micro_rtseconds.mzML", 1);
        FIXTURES_WITH_DUMPS.put("micro.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_p64.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_zlib.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_p64_zlib.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_nested.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_multiprec.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro.mgf", 0);
        // Correction C36: MGF drops zero-intensity peaks. Block 2 of this fixture is ALL zeros, so
        // MassQL emits no rows for it and it vanishes from the dataframe -- our reader still yields
        // the block, now with zero peaks, hence exactly one reader-only scan.
        FIXTURES_WITH_DUMPS.put("micro_zeroint.mgf", 1);
        // C37: two MS1 scans with DIFFERENT peaks -- the only fixture that can discriminate
        // condition ORDER. Every scan has peaks, so nothing is reader-only.
        FIXTURES_WITH_DUMPS.put("micro_ms1var.mzML", 0);
    }

    /** The inventory's fixture names, in declaration order — a JUnit {@code @MethodSource}. */
    static List<String> fixtures() {
        return List.copyOf(FIXTURES_WITH_DUMPS.keySet());
    }

    /** Where each fixture lives under {@code src/test/resources}. */
    static Path fixturePath(String name) {
        return Fixtures.require(name.startsWith("micro") ? "fixtures/micro/" + name : "data/" + name);
    }
}
