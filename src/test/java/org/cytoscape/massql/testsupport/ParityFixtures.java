package org.cytoscape.massql.testsupport;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ParityFixtures {
    private ParityFixtures() {}

    public static final Map<String, Integer> FIXTURES_WITH_DUMPS = new LinkedHashMap<>();

    static {
        FIXTURES_WITH_DUMPS.put("small.mzML", 0);
        FIXTURES_WITH_DUMPS.put("small.mzXML", 0);
        FIXTURES_WITH_DUMPS.put("DP00570_F02.mzxml", 0);
        FIXTURES_WITH_DUMPS.put("DP00570_F02.mgf", 0);

        FIXTURES_WITH_DUMPS.put("PlusRise.mgf", 12_571);

        FIXTURES_WITH_DUMPS.put("micro.mzML", 1);
        FIXTURES_WITH_DUMPS.put("micro_rtseconds.mzML", 1);
        FIXTURES_WITH_DUMPS.put("micro.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_p64.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_zlib.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_p64_zlib.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_nested.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro_multiprec.mzXML", 1);
        FIXTURES_WITH_DUMPS.put("micro.mgf", 0);

        FIXTURES_WITH_DUMPS.put("micro_zeroint.mgf", 1);

        FIXTURES_WITH_DUMPS.put("micro_ms1var.mzML", 0);
    }

    public static List<String> fixtures() {
        return List.copyOf(FIXTURES_WITH_DUMPS.keySet());
    }

    public static Path fixturePath(String name) {
        return Fixtures.require(
                name.startsWith("micro") ? "fixtures/micro/" + name : "data/" + name);
    }
}
