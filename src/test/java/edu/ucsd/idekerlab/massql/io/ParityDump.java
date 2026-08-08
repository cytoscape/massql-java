package edu.ucsd.idekerlab.massql.io;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Reads a Step 2 loader-parity dump: MassQL's own loaded tables, per scan, with floats as hex.
 *
 * <p>Promoted to a shared helper because three tests now need the <b>full</b> per-scan record, and the
 * ad-hoc regex in {@code MzmlReaderTest} / {@code Ms1ScanDocumentOrderIT} only extracted three fields.
 *
 * <p><b>Regex rather than a JSON library, deliberately.</b> Jackson discovers modules via
 * {@code ServiceLoader}, banned by `DEPENDENCY_POLICY.md` constraint 1 — and a test-scoped dependency
 * would still be one more thing that can drift from the shipping closure. The dumps are machine-generated
 * with a fixed field order, so a regex is sufficient and adds nothing to the build.
 *
 * <h2>Two properties of these dumps that are easy to get wrong</h2>
 *
 * <p><b>1. Keys are {@code (mslevel, scan)}, never {@code scan} alone</b> (Correction C32a). MassQL
 * synthesises an all-zero MS1 placeholder for MGF, and its scan id <b>collides with a real MS2 id</b> in
 * {@code micro.mgf} (id 3) and {@code DP00570_F02.mgf} (id 625). Keying by scan id silently compares a real
 * spectrum against a row of zeros.
 *
 * <p><b>2. Entries are grouped by ms level, NOT document order</b> (Correction C28).
 * {@code dump_loader_parity.py} builds its list from {@code ms1_df} then {@code ms2_df} — 229 MS1 entries
 * then 687 MS2 on the Ewing file. Never infer sequence from a dump; re-derive order from the file.
 */
final class ParityDump {

    /**
     * One scan's worth of MassQL's loaded state. Hex fields are kept raw and parsed on demand.
     *
     * <p>The last three are <b>MS2 only</b> and are null on an MS1 record — the generator emits them
     * under {@code if level == "2"}, because {@code ms1_df} has no such columns. That is a real
     * property of the reference's two-dataframe shape, not a gap: an MS1 survey scan has no
     * precursor to describe.
     */
    record Scan(
            int scan,
            int mslevel,
            int peakCount,
            String iSumHex,
            String iSha256,
            String mzSha256,
            List<String> iHexFirst8,
            List<String> mzHexFirst8,
            String rtHex,
            int polarity,
            String precmzHex,
            Integer ms1scan,
            Integer charge) {

        /** MassQL's precursor m/z for this scan. MS2 only. */
        double precmz() {
            return parseHex(precmzHex);
        }

        /** The composite key. See the class note: scan id alone is unsafe. */
        Key key() {
            return new Key(mslevel, scan);
        }

        double rt() {
            return parseHex(rtHex);
        }

        double iSum() {
            return parseHex(iSumHex);
        }
    }

    record Key(int mslevel, int scan) {
        @Override
        public String toString() {
            return "MS" + mslevel + " scan " + scan;
        }
    }

    private final String fixture;
    private final int ms1ScanCount;
    private final int ms2ScanCount;
    private final long peakRows;
    private final Map<Key, Scan> scans;

    private ParityDump(String fixture, int ms1, int ms2, long peakRows, Map<Key, Scan> scans) {
        this.fixture = fixture;
        this.ms1ScanCount = ms1;
        this.ms2ScanCount = ms2;
        this.peakRows = peakRows;
        this.scans = scans;
    }

    String fixture() {
        return fixture;
    }

    int ms1ScanCount() {
        return ms1ScanCount;
    }

    int ms2ScanCount() {
        return ms2ScanCount;
    }

    long peakRows() {
        return peakRows;
    }

    Map<Key, Scan> scans() {
        return scans;
    }

    /** Loads the dump for a fixture name, e.g. {@code "small.mzML"}. Fails if absent (C26). */
    static ParityDump of(String fixtureName) {
        Path gz = Fixtures.require("goldens/loader-parity/" + fixtureName + ".json.gz");
        String json;
        try (var in = new GZIPInputStream(Files.newInputStream(gz))) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read parity dump " + gz, e);
        }

        Map<Key, Scan> byKey = new LinkedHashMap<>();
        Matcher m = SCAN.matcher(json);
        while (m.find()) {
            Scan s =
                    new Scan(
                            Integer.parseInt(m.group(1)),
                            Integer.parseInt(m.group(2)),
                            Integer.parseInt(m.group(3)),
                            m.group(4),
                            m.group(5),
                            m.group(6),
                            hexList(m.group(7)),
                            hexList(m.group(8)),
                            m.group(9),
                            Integer.parseInt(m.group(10)),
                            m.group(11),
                            m.group(12) == null ? null : Integer.valueOf(m.group(12)),
                            m.group(13) == null ? null : Integer.valueOf(m.group(13)));
            Scan clash = byKey.put(s.key(), s);
            // A duplicate key would mean the dump itself is malformed, or that our key is too weak
            // --
            // the failure mode C32a is about. Better to know here than to silently drop half the
            // file.
            assertNull(clash, "duplicate key " + s.key() + " in dump " + fixtureName);
        }

        // A dump that failed to parse must FAIL, not yield an empty map that every later assertion
        // vacuously satisfies. This is the "vacuous pass" trap in Tech_Step8's Known traps.
        assertFalse(
                byKey.isEmpty(),
                "no scans parsed from "
                        + gz
                        + " -- the dump format changed and every parity assertion "
                        + "built on it would now pass vacuously");

        return new ParityDump(
                fixtureName,
                intField(json, "ms1_scan_count"),
                intField(json, "ms2_scan_count"),
                (long) intField(json, "ms1_peak_rows") + intField(json, "ms2_peak_rows"),
                byKey);
    }

    /**
     * Parses one of the dump's hex floats.
     *
     * <p><b>Never route these through a decimal string.</b> {@code Double.parseDouble} accepts Java's
     * hexadecimal literal form ({@code 0x1.5c8f2ap+20}) and reproduces the exact bits; a decimal
     * round-trip can lose or fabricate low bits and turn a real decoder bug into a passing test.
     */
    static double parseHex(String hex) {
        return Double.parseDouble(hex);
    }

    /**
     * The SHA-256 the dump records: every value packed as big-endian IEEE754 double, then hashed.
     *
     * <p>This is what makes the comparison <b>stronger than a multiset</b> — it pins the array's
     * <b>order</b> as well as every bit. See {@code PeakOrderPreconditionTest} for the precondition that
     * makes order-sensitivity correct rather than brittle.
     */
    static String sha256Of(double[] values) {
        ByteBuffer b = ByteBuffer.allocate(8 * values.length).order(ByteOrder.BIG_ENDIAN);
        for (double v : values) b.putDouble(v);
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b.array());
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d)
                sb.append(Character.forDigit((x >> 4) & 0xF, 16))
                        .append(Character.forDigit(x & 0xF, 16));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }

    // ------------------------------------------------------------------ parsing

    /** Field order is fixed by the generator, so one regex covers the whole record. */
    private static final Pattern SCAN =
            Pattern.compile(
                    "\"scan\":\\s*\"?(-?\\d+)\"?,\\s*"
                            + "\"mslevel\":\\s*(\\d+),\\s*"
                            + "\"peak_count\":\\s*(\\d+),\\s*"
                            + "\"i_sum_hex\":\\s*\"([^\"]*)\",\\s*"
                            + "\"i_sha256\":\\s*\"([0-9a-f]*)\",\\s*"
                            + "\"mz_sha256\":\\s*\"([0-9a-f]*)\",\\s*"
                            + "\"i_hex_first8\":\\s*\\[([^\\]]*)\\],\\s*"
                            + "\"mz_hex_first8\":\\s*\\[([^\\]]*)\\],\\s*"
                            + "\"rt_hex\":\\s*\"([^\"]*)\",\\s*"
                            + "\"polarity\":\\s*(-?\\d+)"
                            // MS2 only -- absent on an MS1 record, hence the optional group.
                            + "(?:,\\s*\"precmz\":\\s*\"([^\"]*)\",\\s*"
                            + "\"ms1scan\":\\s*\"?(-?\\d+)\"?,\\s*"
                            + "\"charge\":\\s*\"?(-?\\d+)\"?)?");

    private static List<String> hexList(String body) {
        List<String> out = new ArrayList<>(8);
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(body);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static int intField(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\\s*(-?\\d+)").matcher(json);
        assertTrue(m.find(), "field " + name + " missing from the dump");
        return Integer.parseInt(m.group(1));
    }
}
