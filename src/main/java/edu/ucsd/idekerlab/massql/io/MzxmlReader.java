package edu.ucsd.idekerlab.massql.io;

import edu.ucsd.idekerlab.massql.MassqlException;
import edu.ucsd.idekerlab.massql.io.vendor.ByteBufferInputStream;
import edu.ucsd.idekerlab.massql.io.vendor.FileMemoryMapper;
import edu.ucsd.idekerlab.massql.spectra.SpectrumTable;
import edu.ucsd.idekerlab.massql.spectra.SpectrumTableBuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import javolution.text.CharArray;
import javolution.xml.internal.stream.XMLStreamReaderImpl;
import javolution.xml.stream.XMLStreamConstants;
import javolution.xml.stream.XMLStreamException;

/**
 * Streaming mzXML reader — hand-written, vendoring nothing new (Correction C23).
 *
 * <p><b>Hand-written because MSDK's `MzXMLFileParser` cannot be vendored:</b> it carries 13 msdk imports
 * including 7 `datamodel` types, and Guava arrives through `SimpleMsScan`, which imports `Preconditions`
 * and `Range`. Vendoring it would mean writing our own scan holder — the surgery that killed the same
 * plan for mzML one spec earlier (C21). mzXML is the simpler format anyway: zlib-or-none rather than zlib
 * plus six Numpress variants, one `precision` attribute rather than per-array cvParams, one interleaved
 * array rather than two.
 *
 * <p>Shape mirrors {@link MzmlReader}: memory-map, walk with {@link XMLStreamReaderImpl} instantiated
 * directly (the JDK's {@code XMLInputFactory} uses {@code ServiceLoader}, banned by
 * `DEPENDENCY_POLICY.md` constraint 1), capture each {@code <peaks>} element's base64 <b>text</b> and
 * decode it lazily in {@link ScanView#materialize()}.
 *
 * <p><b>Nothing is shared with the mzML decode path, deliberately</b> (Correction C21a). mzML is
 * little-endian with separate arrays and Numpress; mzXML is <b>big-endian with interleaved pairs and no
 * Numpress</b>. A shared helper would need a flag for every one of those, and reusing a buffer configured
 * for mzML produces plausible-looking garbage rather than an error.
 *
 * <p><b>{@code </scan>} does not end a spectrum.</b> mzXML 2.0 nests MS2 inside its parent MS1, and the
 * Ewing fixture does exactly that ({@code <scan>} depth 2) while {@code small.mzXML} is flat (depth 1).
 * So this walk emits a scan when it reaches that scan's {@code <peaks>} element — which per the schema
 * precedes any nested child — and never consults {@code </scan>} at all. Flat and nested files then take
 * an identical path, which is why both produce the same {@code ms1scan} links.
 */
final class MzxmlReader extends AbstractSpectraStream {

    private final ByteBufferInputStream mapped;
    private final XMLStreamReaderImpl xml;
    private final List<String> diagnostics = new ArrayList<>();

    private int skippedHighMsLevel = 0;
    private int skippedNoMsLevel = 0;
    private int skippedNotASpectrum = 0;
    private boolean sawRootEnd = false;

    /** Document-order tracking: the id of the most recent MS1 spectrum WITH PEAKS (C27b). */
    private int previousMs1Scan = 0;

    /** True once {@code <scan>} attributes are read, until its {@code <peaks>} is reached. */
    private boolean scanStarted = false;

    private final Scan scan = new Scan();

    MzxmlReader(Path path) {
        super(path);
        // Must precede `new XMLStreamReaderImpl()` below: javolution logs buffer growth to STDOUT,
        // which would corrupt the CLI's JSON payload. See that class.
        JavolutionQuiet.ensure();
        try {
            this.mapped = FileMemoryMapper.mapToMemory(path.toFile());
        } catch (IOException e) {
            throw new MassqlException("cannot map " + path + ": " + e.getMessage(), e);
        }
        this.xml = new XMLStreamReaderImpl();
        try {
            xml.setInput(mapped, "UTF-8");
        } catch (XMLStreamException e) {
            closeQuietly();
            throw new MassqlException("cannot parse " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> diagnostics() {
        List<String> out = new ArrayList<>(diagnostics);
        if (skippedHighMsLevel > 0) {
            out.add("skipped " + skippedHighMsLevel + " scans with ms level > 2 (out of scope for scaninfo)");
        }
        if (skippedNoMsLevel > 0) {
            // C27a: MassQL drops these too -- pyteomics yields msLevel None and neither the ==1 nor
            // the ==2 branch fires. Reported rather than silent, because "48 scans became 40" needs
            // an explanation at the point of use.
            out.add("skipped " + skippedNoMsLevel + " scans with a missing or empty msLevel attribute"
                    + " (MassQL drops these: pyteomics yields None, which matches neither ms level)");
        }
        if (skippedNotASpectrum > 0) {
            out.add("skipped " + skippedNotASpectrum + " <scan> elements with no <peaks> child"
                    + " (msql_fileloading.py:424 treats these as \"not a mass spectrum\")");
        }
        return Collections.unmodifiableList(out);
    }

    @Override protected ScanView view() { return scan; }

    @Override
    protected boolean advance() {
        try {
            while (xml.hasNext()) {
                int ev = xml.next();

                if (ev == XMLStreamConstants.END_DOCUMENT) {
                    endOfDocument();
                    return false;
                }
                if (ev == XMLStreamConstants.END_ELEMENT) {
                    CharArray n = xml.getLocalName();
                    if (eq(n, "msRun") || eq(n, "mzXML")) sawRootEnd = true;
                    continue;                       // </scan> is deliberately NOT a boundary
                }
                if (ev != XMLStreamConstants.START_ELEMENT) continue;

                CharArray name = xml.getLocalName();
                if (eq(name, "scan")) {
                    dropUnfinishedScan();           // a <scan> with no <peaks> of its own
                    startScan();
                } else if (eq(name, "precursorMz") && scanStarted) {
                    readPrecursorMz();
                } else if (eq(name, "peaks") && scanStarted) {
                    readPeaks();
                    scanStarted = false;
                    if (admit()) {                  // false = dropped (bad/absent/high ms level)
                        return true;
                    }
                }
            }
            endOfDocument();
            return false;
        } catch (XMLStreamException e) {
            throw new MassqlException("malformed mzXML in " + path + ": " + e.getMessage(), e);
        }
    }

    /**
     * Applies the ms-level rules and the document-order chain. False if the scan is dropped.
     *
     * <p>Ordering matters and follows {@code _load_data_mzXML} (`msql_fileloading.py:419-470`): the
     * zero-peak guard comes <b>before</b> {@code msLevel} is consulted, and the chain is updated only
     * for a level-1 scan that survived it.
     */
    private boolean admit() {
        if (scan.msLevel == 0) {                    // absent or empty msLevel -- C27a
            skippedNoMsLevel++;
            return false;
        }
        if (scan.msLevel > 2) {
            skippedHighMsLevel++;
            return false;
        }
        // Correction C27(b): a ZERO-PEAK scan never becomes an ms1scan link. MassQL `continue`s on
        // len(intensity array)==0 (:421) BEFORE previous_ms1_scan is assigned, so an empty MS1 is
        // invisible to the chain and the next MS2 links to the MS1 *before* it. Verified against
        // MassQL's own loader: micro.mzXML gives {1:0, 3:2, 5:2} -- scan 5 links to 2, not to the
        // empty MS1 at 4 -- and the nested variant gives the identical map.
        //
        // The scan is still YIELDED; only the linkage skips it. Same split as MGF, where all 34,513
        // blocks including the 12,571 empty ones are yielded and the engine filters (C24b).
        if (scan.msLevel == 1) {
            if (scan.peakCount > 0) previousMs1Scan = scan.scanId;
        } else {
            scan.ms1scan = previousMs1Scan;
        }
        return true;
    }

    /** A {@code <scan>} whose {@code <peaks>} never arrived is "not a mass spectrum" (`:424`). */
    private void dropUnfinishedScan() {
        if (scanStarted) {
            skippedNotASpectrum++;
            scanStarted = false;
        }
    }

    private void endOfDocument() {
        // Distinguish a truncated file from a legitimately peak-less trailing scan: a well-formed
        // document closes </msRun> (or </mzXML>). Without that, the file was cut short, and a
        // partial result is worse than an error -- the shortfall would surface later as an
        // inexplicable filtering bug.
        if (!sawRootEnd) {
            throw new MassqlException("truncated mzXML: " + path
                    + " ends without closing </msRun>; refusing to return a partial result");
        }
        dropUnfinishedScan();
    }

    private void startScan() {
        scan.reset();
        scan.scanId = scanNum(attr("num"));
        scan.msLevel = parseInt(attr("msLevel"), 0);     // absent/empty -> 0 -> dropped in admit()
        scan.declaredPeaks = parseInt(attr("peaksCount"), -1);
        scan.rt = retentionTimeMinutes(attr("retentionTime"));
        scan.polarity = polarityOf(attr("polarity"));
        scanStarted = true;
    }

    private void readPrecursorMz() throws XMLStreamException {
        // precursorCharge is optional; absent -> 0. NOTE this is mzXML's default, unlike MGF where an
        // absent CHARGE is 1 (Correction C6). Three formats, three charge defaults.
        int charge = parseInt(attr("precursorCharge"), 0);
        // getElementText() consumes through </precursorMz>. The VALUE is element text, not an
        // attribute -- which is why a bare <precursorMz> with no attributes, the Step 2 finding that
        // crashes pyteomics/MassQL, costs us nothing.
        CharArray text = xml.getElementText();
        double mz = parseDouble(text == null ? null : text.toString(), 0.0);

        // Correction C31: FIRST wins. MassQL hard-indexes spectrum["precursorMz"][0]
        // (msql_fileloading.py:450), and a scan may legitimately carry several -- multiplexed (MSX)
        // acquisition co-fragments more than one precursor. This method used to overwrite on every
        // occurrence, i.e. last-wins, and no fixture was multi-precursor so nothing caught it.
        if (scan.precursorSeen) return;
        scan.precursorSeen = true;
        scan.precmz = mz;
        scan.charge = charge;
    }

    private void readPeaks() throws XMLStreamException {
        scan.precision = parseInt(attr("precision"), 32);
        String order = attr("byteOrder");
        // "network" is big-endian, and it is the only value real mzXML uses. Anything else is
        // reported rather than silently mis-decoded: a wrong byte order yields plausible garbage.
        scan.bigEndian = order == null || "network".equalsIgnoreCase(order.trim());
        if (!scan.bigEndian) {
            diagnostics.add("scan " + scan.scanId + ": byteOrder=\"" + order
                    + "\" is not \"network\"; decoding as little-endian");
        }
        String comp = attr("compressionType");
        // Upstream's check is `!= null && != "none"`, so an ABSENT attribute means uncompressed --
        // which is what all three primary fixtures rely on. Verified, not assumed.
        scan.zlib = comp != null && !comp.trim().isEmpty()
                && !"none".equalsIgnoreCase(comp.trim());

        CharArray text = xml.getElementText();
        scan.base64 = text == null ? "" : text.toString().trim();
        scan.peakCount = scan.resolvePeakCount();
    }

    // ------------------------------------------------------------------ rules

    /**
     * mzXML {@code num} attribute -> scan id.
     *
     * <p>pyteomics returns {@code spectrum["id"]} as a <b>{@code str}</b> ({@code '1'}), which is the
     * root cause of Correction C12: {@code previous_ms1_scan} then propagates a string into
     * {@code ms1scan} and every downstream {@code ms1_*} lookup misses. Parsing to int here is the fix.
     */
    static int scanNum(String num) {
        if (num == null) throw new MassqlException("mzXML <scan> has no num attribute");
        try {
            return Integer.parseInt(num.trim());
        } catch (NumberFormatException e) {
            throw new MassqlException("mzXML <scan num=\"" + num + "\"> is not an integer", e);
        }
    }

    /** {@code "+"} -> 1, {@code "-"} -> 2, present-but-other -> 0. Absent -> 0 is NON-PARITY. */
    static int polarityOf(String polarity) {
        // _determine_scan_polarity_mzXML (:517-523) initialises 0 and tests "+" then "-", so
        // present-but-other -> 0 IS parity. But it reads spec["polarity"] UNGUARDED, so an ABSENT
        // attribute raises KeyError -- MassQL produces nothing and no golden can exist. Our 0 there
        // is our own contract (Correction C27c); micro_nopolarity.mzXML pins it and MzxmlPolarityTest
        // keeps the two cases apart so a pass cannot imply parity we do not have.
        if (polarity == null) return 0;
        String p = polarity.trim();
        if (p.equals("+")) return 1;
        if (p.equals("-")) return 2;
        return 0;
    }

    /**
     * The pyteomics ISO-8601 duration parser, reproduced including its quirks.
     *
     * <p><b>mzXML retention time is ALWAYS converted to minutes</b> — unlike mzML, whose conversion is
     * conditional on the declared unit ({@link MzmlReader}). Three formats, three RT rules, and a silent
     * 60x error here passes every MGF-only and mzML-only test. That is why this lives in its own method
     * with its own test rather than sharing code with the mzML path.
     *
     * <p>MassQL uses {@code spectrum["retentionTime"]} as-is because pyteomics has already converted it
     * (`pyteomics/xml.py:126-143`). Its arithmetic, in this order:
     * <pre>
     *   minutes  = M
     *   minutes += H * 60.
     *   minutes += S / 60.
     * </pre>
     * Reproduced literally: the order is what makes {@code PT1.38S} come back as exactly the double
     * {@code 0.023}, and {@code PT1H30M45S} as {@code 90.75}.
     *
     * <p><b>Three quirks, all verified against pyteomics rather than inferred:</b>
     * <ul>
     *   <li><b>Years, months and days are parsed and then IGNORED.</b> {@code P1DT1H} -> {@code 60.0},
     *       not 1500. Only H/M/S after the {@code T} contribute.</li>
     *   <li><b>{@code M} before {@code T} is months, after it is minutes.</b> {@code P1M} -> {@code 0.0}
     *       while {@code PT1M} -> {@code 1.0}.</li>
     *   <li><b>The sign is captured and ignored</b> — but a leading {@code -} means the string does not
     *       start with {@code P}, so pyteomics falls through to {@code float()}, fails, and returns the
     *       <i>string</i>. MassQL would then carry a string as {@code rt}. We throw instead: see below.</li>
     * </ul>
     */
    static double retentionTimeMinutes(String rt) {
        if (rt == null) return 0.0;
        String s = rt.trim();
        if (s.isEmpty()) return 0.0;

        if (!s.startsWith("P")) {
            // pyteomics: `unitfloat(s, 'duration')`, else `unitstr(s, 'duration')`. A bare number is
            // used as-is; anything else becomes a STRING that MassQL would carry as rt (verified:
            // "-PT90S" comes back as the literal string). We refuse rather than invent a number --
            // a documented deviation, and a clean error beats a silently wrong retention time.
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                throw new MassqlException("cannot read mzXML retentionTime \"" + rt
                        + "\": not an ISO-8601 duration and not a number. pyteomics returns the raw"
                        + " string here, which MassQL would carry as a non-numeric rt", e);
            }
        }

        Matcher m = DURATION.matcher(s);
        if (!m.find()) return 0.0;                       // pyteomics returns the string; unreachable
        double hours = group(m, 5);
        double minutes = group(m, 6);
        double seconds = group(m, 7);
        // Groups 2/3/4 are years/months/days: matched so that P1M is read as MONTHS, then discarded.
        minutes += hours * 60.0;
        minutes += (seconds / 60.0);
        return minutes;
    }

    /** Mirrors pyteomics' `_duration_parser` exactly, including the unused sign/Y/M/D groups. */
    private static final Pattern DURATION = Pattern.compile(
            "(-?)P(?:(\\d+\\.?\\d*)Y)?(?:(\\d+\\.?\\d*)M)?(?:(\\d+\\.?\\d*)D)?"
                    + "(?:T(?:(\\d+\\.?\\d*)H)?(?:(\\d+\\.?\\d*)M)?(?:(\\d+\\.?\\d*)S)?)?");

    private static double group(Matcher m, int i) {
        String g = m.group(i);
        return (g == null || g.isEmpty()) ? 0.0 : Double.parseDouble(g);
    }

    // ------------------------------------------------------------------ helpers

    private String attr(String name) { return str(xml.getAttributeValue(null, name)); }

    private static String str(CharArray c) { return c == null ? null : c.toString(); }

    private static boolean eq(CharArray c, String s) { return c != null && c.equals(s); }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.trim().isEmpty()) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null || s.trim().isEmpty()) return fallback;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private void closeQuietly() {
        try { mapped.close(); } catch (IOException ignored) { }
    }

    @Override
    protected void releaseResources() {
        try { xml.close(); } catch (XMLStreamException ignored) { }
        closeQuietly();
    }

    /** Mutable, reused across scans — retained memory stays bounded by one scan. */
    private final class Scan implements ScanView {
        int scanId;
        int msLevel;
        double rt;
        int polarity;
        double precmz;
        int ms1scan;
        int charge;
        int peakCount;
        int declaredPeaks;      // the peaksCount attribute, or -1
        int precision = 32;
        boolean bigEndian = true;
        boolean zlib = false;
        String base64 = "";
        boolean precursorSeen;   // C31: only the FIRST <precursorMz> counts

        void reset() {
            scanId = 0; msLevel = 0; rt = 0.0; polarity = 0;
            precmz = 0.0;    // 0 sentinel -- Step 10 converts, not us. Also our non-parity value
                             // for an MS2 with no <precursorMz> at all (C27c).
            ms1scan = 0;     // 0 = no preceding MS1; the origin of the sentinel
            charge = 0;      // mzXML default -- NOT MGF's 1 (C6)
            peakCount = 0;
            declaredPeaks = -1;
            precision = 32; bigEndian = true; zlib = false;
            base64 = "";
            precursorSeen = false;
        }

        /**
         * Peak count without decoding, for the capacity hint and the C27b chain guard.
         *
         * <p>{@code peaksCount} is schema-required and is what we trust. When it is absent we derive
         * the count from the base64 length, which is exact for the uncompressed case — worth doing
         * because a wrongly-zero count here would silently break the {@code ms1scan} chain rather
         * than merely mis-size an array.
         */
        int resolvePeakCount() {
            if (declaredPeaks >= 0) return declaredPeaks;
            if (base64.isEmpty()) return 0;
            if (zlib) return 0;              // cannot know without inflating; capacity hint only
            int bytes = base64Bytes(base64);
            int width = 2 * (precision == 64 ? 8 : 4);     // an interleaved m/z-intensity PAIR
            return width == 0 ? 0 : bytes / width;
        }

        @Override public int scanId()    { return scanId; }
        @Override public int msLevel()   { return msLevel; }
        @Override public double rt()     { return rt; }
        @Override public int polarity()  { return polarity; }
        @Override public double precmz() { return precmz; }
        @Override public int ms1scan()   { return ms1scan; }
        @Override public int charge()    { return charge; }
        @Override public int peakCount() { return peakCount; }

        @Override
        public SpectrumTable materialize() {
            double[][] pair = decode();
            double[] mz = pair[0], in = pair[1];
            int n = mz.length;
            SpectrumTableBuilder b = new SpectrumTableBuilder(msLevel == 1 ? 1 : 2, Math.max(n, 1));
            b.startScan(scanId, rt, polarity, precmz, ms1scan, charge);
            for (int i = 0; i < n; i++) b.addPeak(mz[i], in[i]);
            return b.build();
        }

        /**
         * base64 -> (optional inflate) -> de-interleave, returning {@code {mz[], intensity[]}}.
         *
         * <p><b>The 32-bit widening rule.</b> pyteomics decodes with
         * {@code np.float32 if precision == '32' else np.float64} and Python then widens to double, so
         * the golden values are {@code (double)(float)raw} — <b>not</b> full-precision doubles.
         * {@link ByteBuffer#getFloat()} assigned into a {@code double[]} is exactly that. Reading 8
         * bytes, or reinterpreting the bits as a double, gives values that are <i>nearly</i> right and
         * turns Step 8 into a confusing near-miss. Observable: the Ewing file and {@code micro.mzXML}
         * give {@code 123.456787109375} where {@code micro_p64.mzXML} gives {@code 123.456789012345}.
         */
        private double[][] decode() {
            if (base64.isEmpty()) return new double[][]{new double[0], new double[0]};

            byte[] raw;
            try {
                raw = Base64.getMimeDecoder().decode(base64);   // MIME: tolerates embedded newlines
            } catch (IllegalArgumentException e) {
                throw new MassqlException("scan " + scanId + " in " + path
                        + ": <peaks> is not valid base64: " + e.getMessage(), e);
            }
            if (zlib) raw = inflate(raw);

            int width = precision == 64 ? 8 : 4;
            if (precision != 32 && precision != 64) {
                throw new MassqlException("scan " + scanId + " in " + path
                        + ": unsupported peaks precision=\"" + precision + "\" (expected 32 or 64)");
            }
            ByteBuffer buf = ByteBuffer.wrap(raw)
                    .order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

            int pairs = raw.length / (2 * width);
            if (raw.length % (2 * width) != 0) {
                // An odd tail means the array is not whole m/z-intensity pairs. Report it rather than
                // dropping a value silently, which would change tic and base_peak.
                diagnostics.add("scan " + scanId + ": <peaks> holds " + raw.length
                        + " bytes, not a whole number of " + (2 * width) + "-byte pairs; ignoring the tail");
            }
            double[] mz = new double[pairs];
            double[] in = new double[pairs];
            if (precision == 64) {
                for (int i = 0; i < pairs; i++) { mz[i] = buf.getDouble(); in[i] = buf.getDouble(); }
            } else {
                // getFloat() into a double[] IS the (double)(float) widening. Do not "fix" this.
                for (int i = 0; i < pairs; i++) { mz[i] = buf.getFloat(); in[i] = buf.getFloat(); }
            }
            return new double[][]{mz, in};
        }

        private byte[] inflate(byte[] compressed) {
            // No Numpress, ever -- mzXML has none. zlib or nothing.
            Inflater inf = new Inflater();
            inf.setInput(compressed);
            java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream(Math.max(compressed.length * 2, 64));
            byte[] chunk = new byte[8192];
            try {
                while (!inf.finished()) {
                    int got = inf.inflate(chunk);
                    if (got == 0) {
                        if (inf.needsInput() || inf.needsDictionary()) break;
                    }
                    out.write(chunk, 0, got);
                }
            } catch (DataFormatException e) {
                throw new MassqlException("scan " + scanId + " in " + path
                        + ": <peaks> declares compressionType=\"zlib\" but does not inflate: "
                        + e.getMessage(), e);
            } finally {
                inf.end();
            }
            return out.toByteArray();
        }
    }

    /** Decoded byte length of a base64 string, without decoding it. */
    private static int base64Bytes(String b64) {
        int chars = 0, pad = 0;
        for (int i = 0; i < b64.length(); i++) {
            char c = b64.charAt(i);
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue;
            if (c == '=') pad++;
            chars++;
        }
        return Math.max(0, (chars / 4) * 3 - pad);
    }
}
