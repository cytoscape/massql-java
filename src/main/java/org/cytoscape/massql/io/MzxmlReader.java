package org.cytoscape.massql.io;

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

import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.io.vendor.ByteBufferInputStream;
import org.cytoscape.massql.io.vendor.FileMemoryMapper;
import org.cytoscape.massql.lang.ast.Polarity;
import org.cytoscape.massql.spectra.SpectrumTableBuilder;

import javolution.text.CharArray;
import javolution.xml.internal.stream.XMLStreamReaderImpl;
import javolution.xml.stream.XMLStreamConstants;
import javolution.xml.stream.XMLStreamException;

final class MzxmlReader extends AbstractSpectraStream {
    private final ByteBufferInputStream mapped;
    private final XMLStreamReaderImpl xml;
    private final List<String> diagnostics = new ArrayList<>();

    private int skippedHighMsLevel = 0;
    private int skippedNoMsLevel = 0;
    private int skippedNotASpectrum = 0;
    private boolean sawRootEnd = false;

    private int previousMs1Scan = 0;

    private boolean scanStarted = false;

    private final Scan scan = new Scan();
    private ScanView current;

    MzxmlReader(Path path) {
        super(path);

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
            mapped.close();
            throw new MassqlException("cannot parse " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> diagnostics() {
        List<String> out = new ArrayList<>(diagnostics);
        if (skippedHighMsLevel > 0) {
            out.add(
                    "skipped "
                            + skippedHighMsLevel
                            + " scans with ms level > 2 (out of scope for scaninfo)");
        }
        if (skippedNoMsLevel > 0) {
            out.add(
                    "skipped "
                            + skippedNoMsLevel
                            + " scans with a missing or empty msLevel attribute"
                            + " (dropped: a missing msLevel matches neither ms level)");
        }
        if (skippedNotASpectrum > 0) {
            out.add(
                    "skipped "
                            + skippedNotASpectrum
                            + " <scan> elements with no <peaks> child"
                            + " (treated as \"not a mass spectrum\")");
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    protected ScanView view() {
        return current;
    }

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
                    continue;
                }
                if (ev != XMLStreamConstants.START_ELEMENT) continue;

                CharArray name = xml.getLocalName();
                if (eq(name, "scan")) {
                    dropUnfinishedScan();
                    startScan();
                } else if (eq(name, "precursorMz") && scanStarted) {
                    readPrecursorMz();
                } else if (eq(name, "peaks") && scanStarted) {
                    readPeaks();
                    scanStarted = false;
                    if (admit()) {
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

    private boolean admit() {
        if (scan.msLevel == 0) {
            skippedNoMsLevel++;
            return false;
        }
        if (scan.msLevel > 2) {
            skippedHighMsLevel++;
            return false;
        }

        if (scan.msLevel == 1) {
            if (scan.peakCount > 0) previousMs1Scan = scan.scanId;
        } else {
            scan.ms1scan = previousMs1Scan;
        }
        current = scan.toView();
        return true;
    }

    private void dropUnfinishedScan() {
        if (scanStarted) {
            skippedNotASpectrum++;
            scanStarted = false;
        }
    }

    private void endOfDocument() {
        if (!sawRootEnd) {
            throw new MassqlException(
                    "truncated mzXML: "
                            + path
                            + " ends without closing </msRun>; refusing to return a partial result");
        }
        dropUnfinishedScan();
    }

    private void startScan() {
        scan.reset();
        scan.scanId = scanNum(attr("num"));
        scan.msLevel = parseInt(attr("msLevel"), 0);
        scan.declaredPeaks = parseInt(attr("peaksCount"), -1);
        scan.rt = retentionTimeMinutes(attr("retentionTime"));
        scan.polarity = polarityOf(attr("polarity"));
        scanStarted = true;
    }

    private void readPrecursorMz() throws XMLStreamException {
        int charge = parseInt(attr("precursorCharge"), 0);

        CharArray text = xml.getElementText();
        double mz = parseDouble(text == null ? null : text.toString(), 0.0);

        if (scan.precursorSeen) return;
        scan.precursorSeen = true;
        scan.precmz = mz;
        scan.charge = charge;
    }

    private void readPeaks() throws XMLStreamException {
        scan.precision = parseInt(attr("precision"), 32);
        String order = attr("byteOrder");

        scan.bigEndian = order == null || "network".equalsIgnoreCase(order.trim());
        if (!scan.bigEndian) {
            diagnostics.add(
                    "scan "
                            + scan.scanId
                            + ": byteOrder=\""
                            + order
                            + "\" is not \"network\"; decoding as little-endian");
        }
        String comp = attr("compressionType");

        scan.zlib = comp != null && !comp.trim().isEmpty() && !"none".equalsIgnoreCase(comp.trim());

        CharArray text = xml.getElementText();
        scan.base64 = text == null ? "" : text.toString().trim();
        scan.peakCount = scan.resolvePeakCount();
    }

    static int scanNum(String num) {
        if (num == null) throw new MassqlException("mzXML <scan> has no num attribute");
        try {
            return Integer.parseInt(num.trim());
        } catch (NumberFormatException e) {
            throw new MassqlException("mzXML <scan num=\"" + num + "\"> is not an integer", e);
        }
    }

    static int polarityOf(String polarity) {
        if (polarity == null) return 0;
        String p = polarity.trim();
        if (p.equals("+")) return 1;
        if (p.equals("-")) return 2;
        return 0;
    }

    static double retentionTimeMinutes(String rt) {
        if (rt == null) return 0.0;
        String s = rt.trim();
        if (s.isEmpty()) return 0.0;

        if (!s.startsWith("P")) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException e) {
                throw new MassqlException(
                        "cannot read mzXML retentionTime \""
                                + rt
                                + "\": not an ISO-8601 duration and not a number. The reference"
                                + " returns the raw string here, which would carry a non-numeric"
                                + " retention time, so this refuses rather than inventing a value",
                        e);
            }
        }

        Matcher m = DURATION.matcher(s);
        if (!m.find()) return 0.0;
        double hours = group(m, 5);
        double minutes = group(m, 6);
        double seconds = group(m, 7);

        minutes += hours * 60.0;
        minutes += (seconds / 60.0);
        return minutes;
    }

    private static final Pattern DURATION =
            Pattern.compile(
                    "(-?)P(?:(\\d+\\.?\\d*)Y)?(?:(\\d+\\.?\\d*)M)?(?:(\\d+\\.?\\d*)D)?"
                            + "(?:T(?:(\\d+\\.?\\d*)H)?(?:(\\d+\\.?\\d*)M)?(?:(\\d+\\.?\\d*)S)?)?");

    private static double group(Matcher m, int i) {
        String g = m.group(i);
        return (g == null || g.isEmpty()) ? 0.0 : Double.parseDouble(g);
    }

    private String attr(String name) {
        return str(xml.getAttributeValue(null, name));
    }

    private static String str(CharArray c) {
        return c == null ? null : c.toString();
    }

    private static boolean eq(CharArray c, String s) {
        return c != null && c.equals(s);
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.trim().isEmpty()) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null || s.trim().isEmpty()) return fallback;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    protected void releaseResources() {
        try {
            xml.close();
        } catch (XMLStreamException ignored) {
        }
        mapped.close();
    }

    private final class Scan {
        int scanId;
        int msLevel;
        double rt;
        int polarity;
        double precmz;
        int ms1scan;
        int charge;
        int peakCount;
        int declaredPeaks;
        int precision = 32;
        boolean bigEndian = true;
        boolean zlib = false;
        String base64 = "";
        boolean precursorSeen;

        void reset() {
            scanId = 0;
            msLevel = 0;
            rt = 0.0;
            polarity = 0;
            precmz = 0.0;

            ms1scan = 0;
            charge = 0;
            peakCount = 0;
            declaredPeaks = -1;
            precision = 32;
            bigEndian = true;
            zlib = false;
            base64 = "";
            precursorSeen = false;
        }

        int resolvePeakCount() {
            if (declaredPeaks >= 0) return declaredPeaks;
            if (base64.isEmpty()) return 0;
            if (zlib) return 0;
            int bytes = base64Bytes(base64);
            int width = 2 * (precision == 64 ? 8 : 4);
            return width == 0 ? 0 : bytes / width;
        }

        ScanView toView() {
            double[][] pair = decode();
            double[] mz = pair[0], in = pair[1];
            int n = mz.length;
            SpectrumTableBuilder b = new SpectrumTableBuilder(msLevel == 1 ? 1 : 2, Math.max(n, 1));
            b.startScan(scanId, rt, polarity, precmz, ms1scan, charge);
            for (int i = 0; i < n; i++) b.addPeak(mz[i], in[i]);
            return new ScanView(
                    scanId,
                    msLevel,
                    rt,
                    polarity == 1 ? Polarity.POSITIVE : polarity == 2 ? Polarity.NEGATIVE : null,
                    precmz == 0.0 ? null : precmz,
                    ms1scan == 0 ? null : ms1scan,
                    charge == 0 ? null : charge,
                    b.build());
        }

        private double[][] decode() {
            if (base64.isEmpty()) return new double[][] {new double[0], new double[0]};

            byte[] raw;
            try {
                raw = Base64.getMimeDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                throw new MassqlException(
                        "scan "
                                + scanId
                                + " in "
                                + path
                                + ": <peaks> is not valid base64: "
                                + e.getMessage(),
                        e);
            }
            if (zlib) raw = inflate(raw);

            int width = precision == 64 ? 8 : 4;
            if (precision != 32 && precision != 64) {
                throw new MassqlException(
                        "scan "
                                + scanId
                                + " in "
                                + path
                                + ": unsupported peaks precision=\""
                                + precision
                                + "\" (expected 32 or 64)");
            }
            ByteBuffer buf =
                    ByteBuffer.wrap(raw)
                            .order(bigEndian ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);

            int pairs = raw.length / (2 * width);
            if (raw.length % (2 * width) != 0) {
                diagnostics.add(
                        "scan "
                                + scanId
                                + ": <peaks> holds "
                                + raw.length
                                + " bytes, not a whole number of "
                                + (2 * width)
                                + "-byte pairs; ignoring the tail");
            }
            double[] mz = new double[pairs];
            double[] in = new double[pairs];
            if (precision == 64) {
                for (int i = 0; i < pairs; i++) {
                    mz[i] = buf.getDouble();
                    in[i] = buf.getDouble();
                }
            } else {
                for (int i = 0; i < pairs; i++) {
                    mz[i] = buf.getFloat();
                    in[i] = buf.getFloat();
                }
            }
            return new double[][] {mz, in};
        }

        private byte[] inflate(byte[] compressed) {
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
                throw new MassqlException(
                        "scan "
                                + scanId
                                + " in "
                                + path
                                + ": <peaks> declares compressionType=\"zlib\" but does not inflate: "
                                + e.getMessage(),
                        e);
            } finally {
                inf.end();
            }
            return out.toByteArray();
        }
    }

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
