package org.cytoscape.massql.testsupport;

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

public final class ParityDump {
    public record Scan(
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
        public double precmz() {
            return parseHex(precmzHex);
        }

        public Key key() {
            return new Key(mslevel, scan);
        }

        public double rt() {
            return parseHex(rtHex);
        }

        public double iSum() {
            return parseHex(iSumHex);
        }
    }

    public record Key(int mslevel, int scan) {
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

    public int ms1ScanCount() {
        return ms1ScanCount;
    }

    public int ms2ScanCount() {
        return ms2ScanCount;
    }

    public long peakRows() {
        return peakRows;
    }

    public Map<Key, Scan> scans() {
        return scans;
    }

    public static ParityDump of(String fixtureName) {
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
            assertNull(clash, "duplicate key " + s.key() + " in dump " + fixtureName);
        }

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

    public static double parseHex(String hex) {
        return Double.parseDouble(hex);
    }

    public static String sha256Of(double[] values) {
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
