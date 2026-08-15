package org.cytoscape.massql.lang;

import java.util.LinkedHashMap;
import java.util.Map;

/** The single, authoritative list of MassQL constructs this version does not support. */
public final class UnsupportedConstructs {
    private UnsupportedConstructs() {}

    /** Construct name → why it is out of scope. */
    private static final Map<String, String> REASONS = new LinkedHashMap<>();

    static {
        REASONS.put("scansum", "only scaninfo is supported in this version");
        REASONS.put("scannum", "only scaninfo is supported in this version");
        REASONS.put("scanmaxint", "only scaninfo is supported in this version");
        REASONS.put("scanmz", "only scaninfo is supported in this version");

        REASONS.put(
                "scanrangesum",
                "only scaninfo is supported in this version; note MassQL's own scanrangesum "
                        + "ignores its TOLERANCE parameter and hardcodes 0.1 m/z bins");

        REASONS.put(
                "<no function>",
                "a query function is required; only scaninfo(MS1DATA) / scaninfo(MS2DATA) "
                        + "are supported, not the bare MS1DATA / MS2DATA form");

        REASONS.put(
                "X",
                "X/Y variables and the candidate enumerator are not supported in this version");
        REASONS.put(
                "Y",
                "X/Y variables and the candidate enumerator are not supported in this version");

        REASONS.put("INTENSITYMATCH", "intensity matching is not supported in this version");
        REASONS.put("INTENSITYMATCHPERCENT", "intensity matching is not supported in this version");
        REASONS.put(
                "INTENSITYMATCHREFERENCE", "intensity matching is not supported in this version");

        REASONS.put("MOBILITY", "ion mobility is not supported in this version");
        REASONS.put(
                "OTHERSCAN", "OTHERSCAN requires a second retained index over pre-filter MS1 data");
        REASONS.put("CARDINALITY", "cardinality constraints are not supported in this version");
        REASONS.put("MATCHCOUNT", "cardinality constraints are not supported in this version");
        REASONS.put("EXCLUDED", "EXCLUDED is not supported in this version");
        REASONS.put("MASSDEFECT", "mass-defect qualifiers are not supported in this version");
        REASONS.put("ANY", "the ANY wildcard is not supported in this version");

        REASONS.put(
                "formula()",
                "formula() requires monoisotopic mass tables and is not supported in this version");
        REASONS.put(
                "aminoaciddelta()",
                "aminoaciddelta() requires mass tables and is not supported in this version");
        REASONS.put(
                "peptide()", "peptide() requires mass tables and is not supported in this version");

        REASONS.put("nested subquery", "nested sub-queries are not supported in this version");
    }

    public static boolean isUnsupported(String construct) {
        return REASONS.containsKey(construct);
    }

    /** The user-facing message for a construct. */
    public static String message(String construct) {
        String reason = REASONS.get(construct);
        if (reason == null) {
            reason = "not supported in this version";
        }
        return "`"
                + construct
                + "`: "
                + reason
                + ". This build implements the MassQL scaninfo subset -- see docs/SDK.md.";
    }
}
