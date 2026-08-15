package org.cytoscape.massql.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.io.vendor.ByteBufferInputStream;
import org.cytoscape.massql.io.vendor.FileMemoryMapper;
import org.cytoscape.massql.io.vendor.MzMLBinaryDataInfo;
import org.cytoscape.massql.io.vendor.MzMLBitLength;
import org.cytoscape.massql.io.vendor.MzMLCV;
import org.cytoscape.massql.io.vendor.MzMLCompressionType;
import org.cytoscape.massql.io.vendor.MzMLPeaksDecoder;
import org.cytoscape.massql.lang.ast.Polarity;
import org.cytoscape.massql.spectra.SpectrumTableBuilder;

import javolution.text.CharArray;
import javolution.xml.internal.stream.XMLStreamReaderImpl;
import javolution.xml.stream.XMLStreamConstants;
import javolution.xml.stream.XMLStreamException;

/**
 * Streaming mzML reader: a hand-written XML walk over the vendored decode layer.
 *
 * <p><b>Hand-written because MSDK's parser cannot be vendored</b>:
 * {@code MzMLMsScan}/{@code MzMLFileImportMethod}/{@code MzMLChromatogram} carry 9/17/11
 * {@code msdk-datamodel} imports plus Guava, slf4j and {@code msdk-spectra}. Only the decode layer was
 * clean, and it is what actually justified taking MSDK — {@code MSNumpress} is 44 KB of compression
 * logic we would not want to reimplement.
 *
 * <p><b>{@link XMLStreamReaderImpl} is instantiated DIRECTLY</b>, exactly as upstream does. That is the
 * entire reason javolution is a dependency: the JDK's {@code XMLInputFactory} discovers implementations
 * via {@code ServiceLoader}, which this project does not use because provider lookup fails
 * wherever the thread-context classloader cannot see the caller's classes, and naming the JDK's internal
 * impl would need {@code Class.forName}, also banned.
 *
 * <p><b>Deferred decoding.</b> The walk captures each {@code <binary>} element's base64 text but does
 * not decode it; base64-decode, inflate and the {@code double[]} allocation all happen in
 * {@link ScanView#materialize()}. So a query rejecting a scan on metadata alone — {@code RTMIN},
 * {@code SCANMIN}, {@code POLARITY}, {@code CHARGE}, {@code MS2PREC} — never pays for its peaks.
 *
 * <p>(The plan called for recording byte <i>offsets</i> and seeking back. That is what MSDK does, but
 * it requires the parser's internal buffer position, which javolution does not expose. Capturing the
 * base64 text defers the same expensive work, and retained memory is still bounded by one scan —
 * roughly 1.35x the binary size for the two arrays.)
 */
final class MzmlReader extends AbstractSpectraStream {

    private final ByteBufferInputStream mapped;
    private final XMLStreamReaderImpl xml;
    private final List<String> diagnostics = new ArrayList<>();

    private int skippedHighMsLevel = 0;

    /** Document-order tracking: the id of the most recent MS1 spectrum. Init 0 -- see the sentinel note. */
    private int previousMs1Scan = 0;

    private final Scan scan = new Scan();
    private ScanView current;

    MzmlReader(Path path) {
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
            out.add(
                    "skipped "
                            + skippedHighMsLevel
                            + " spectra with ms level > 2 (out of scope for scaninfo)");
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
            while (readSpectrum()) {
                if (scan.msLevel > 2) { // levels above 2 are out of scope
                    skippedHighMsLevel++;
                    continue;
                }
                // A ZERO-PEAK scan never becomes an ms1scan link.
                // The reference skips any spectrum with an empty intensity array
                // BEFORE previous_ms1_scan is ever assigned, so an empty MS1 is invisible to the
                // chain and the next MS2 links to the MS1 *before* it. Confirmed against MassQL's
                // own loader on micro.mzML: scan 5 -> ms1scan 2, NOT the empty MS1 at scan 4.
                //
                // The scan is still YIELDED (consistent with MGF, where all 34,513 blocks including
                // the 12,571 empty ones are still yielded); only the linkage skips it.
                // peakCount is defaultArrayLength, so this costs no decode.
                //
                // ZeroPeakMs1ChainTest pins it, and it is not a hypothetical: with the guard
                // removed
                // that test reports "Got 4 -- expected 2".
                if (scan.msLevel == 1) {
                    if (scan.peakCount > 0) previousMs1Scan = scan.scanId;
                } else {
                    scan.ms1scan = previousMs1Scan;
                }
                current = scan.toView();
                return true;
            }
            return false;
        } catch (XMLStreamException e) {
            throw new MassqlException("malformed mzML in " + path + ": " + e.getMessage(), e);
        }
    }

    /** Advances to the next {@code <spectrum>} and fills {@link #scan}. False at end of document. */
    private boolean readSpectrum() throws XMLStreamException {
        while (xml.hasNext()) {
            int ev = xml.next();
            if (ev == XMLStreamConstants.END_DOCUMENT) return false;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            if (!eq(xml.getLocalName(), "spectrum")) continue;

            scan.reset();
            scan.scanId = scanIdFrom(attr("id"));
            scan.peakCount = intAttr("defaultArrayLength", 0);
            readSpectrumBody();
            return true;
        }
        return false;
    }

    private void readSpectrumBody() throws XMLStreamException {
        int depth = 1; // we are inside <spectrum>
        boolean inSelectedIon = false;
        boolean inBinaryArray = false;
        Binary bin = null;

        // MassQL hard-indexes
        // precursorList.precursor[0].selectedIonList.selectedIon[0] in the reference, so
        // only
        // the FIRST selectedIon of the FIRST precursor counts. mzML legitimately carries more --
        // multiplexed (MSX) acquisition co-fragments several precursors -- and this reader used to
        // OVERWRITE precmz/charge on every MS:1000744 it saw, i.e. last-wins. Every fixture was
        // single-precursor, so nothing could catch it.
        boolean firstSelectedIonDone = false;

        while (xml.hasNext()) {
            int ev = xml.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                depth++;
                CharArray name = xml.getLocalName();
                if (eq(name, "cvParam")) {
                    if (inBinaryArray && bin != null) binaryCvParam(bin);
                    else if (inSelectedIon && !firstSelectedIonDone) selectedIonCvParam();
                    else if (!inSelectedIon) spectrumCvParam();
                    // else: a cvParam in a later selectedIon -- ignored, per [0] above.
                } else if (eq(name, "selectedIon")) {
                    inSelectedIon = true;
                } else if (eq(name, "binaryDataArray")) {
                    inBinaryArray = true;
                    bin = new Binary();
                } else if (eq(name, "binary") && inBinaryArray && bin != null) {
                    // Capture, do not decode. getElementText() consumes through </binary>,
                    // so depth is back where it was.
                    CharArray text = xml.getElementText();
                    bin.base64 = text == null ? "" : text.toString().trim();
                    depth--;
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                CharArray name = xml.getLocalName();
                if (eq(name, "selectedIon")) {
                    inSelectedIon = false;
                    // The first selectedIon has now closed, so every later one -- whether a
                    // sibling in this precursor or the first of a subsequent <precursor> -- is
                    // ignored. This latch is the whole fix; without it the flag above never trips
                    // and the reader silently reverts to last-wins.
                    firstSelectedIonDone = true;
                }
                if (eq(name, "binaryDataArray")) {
                    if (bin != null) scan.accept(bin);
                    inBinaryArray = false;
                    bin = null;
                }
                depth--;
                if (depth == 0) return; // </spectrum>
            } else if (ev == XMLStreamConstants.END_DOCUMENT) {
                throw new MassqlException("truncated mzML: <spectrum> is not closed in " + path);
            }
        }
        throw new MassqlException("truncated mzML: <spectrum> is not closed in " + path);
    }

    private void spectrumCvParam() {
        CharArray acc = xml.getAttributeValue(null, "accession");
        if (acc == null) return;
        if (eq(acc, MzMLCV.cvMSLevel)) {
            scan.msLevel = parseInt(attr("value"), 0);
        } else if (eq(acc, MzMLCV.MS_RT_SCAN_START)) {
            double rt = parseDouble(attr("value"), 0.0);
            // ⚠ CONDITIONAL on the declared unit. small.mzML says unitName="minute" -> pass
            // through.
            // A blind /60 is a silent 60x error that passes every MGF-only test
            // as the reference does.
            String unitName = str(xml.getAttributeValue(null, "unitName"));
            String unitAcc = str(xml.getAttributeValue(null, "unitAccession"));
            boolean seconds = "second".equalsIgnoreCase(unitName) || "UO:0000010".equals(unitAcc);
            scan.rt = seconds ? rt / 60.0 : rt;
        } else if (eq(acc, MzMLCV.cvPolarityPositive)) {
            scan.polarity = 1;
        } else if (eq(acc, MzMLCV.cvPolarityNegative)) {
            scan.polarity = 2;
        }
    }

    private void selectedIonCvParam() {
        CharArray acc = xml.getAttributeValue(null, "accession");
        if (acc == null) return;
        if (eq(acc, MzMLCV.cvPrecursorMz)) {
            scan.precmz = parseDouble(attr("value"), 0.0);
        } else if (eq(acc, MzMLCV.cvChargeState)) {
            scan.charge = parseInt(attr("value"), 0);
        }
    }

    private void binaryCvParam(Binary bin) {
        CharArray acc = xml.getAttributeValue(null, "accession");
        if (acc == null) return;
        String a = acc.toString();
        if (a.equals(MzMLCV.cvMzArray) || a.equals(MzMLCV.cvIntensityArray)) {
            bin.arrayType = a;
            return;
        }
        // Resolve the accession EXPLICITLY against each enum rather than calling both setters
        // and hoping. MzMLBinaryDataInfo.setBitLength/setCompressionType do NOT ignore an
        // unrecognised accession -- they store null, so calling both for every cvParam makes the
        // compression param clobber the bit length. That is a NullPointerException deep inside the
        // decoder, several frames from the cause.
        for (MzMLBitLength b : MzMLBitLength.values()) {
            if (b.getValue().equals(a)) {
                bin.bitLength = b;
                return;
            }
        }
        for (MzMLCompressionType c : MzMLCompressionType.values()) {
            if (c.getAccession().equals(a)) {
                bin.compression = c;
                return;
            }
        }
    }

    /**
     * mzML spectrum id -> scan number.
     *
     * <p>Take the trailing {@code scan=} token and parse it as an int — strip any
     * {@code scanId=} prefix, split on {@code scan=}, take the <b>LAST</b> segment. This determines every
     * row's identity, so getting it wrong shifts the whole result set.
     *
     * <p>MassQL raises {@code ValueError} on an id with no {@code scan=}; we throw a named
     * {@link MassqlException}. Documented deviation — a clean error either way.
     */
    static int scanIdFrom(String id) {
        if (id == null) throw new MassqlException("mzML <spectrum> has no id attribute");
        String s = id.replace("scanId=", "");
        int i = s.lastIndexOf("scan=");
        String tail = (i >= 0) ? s.substring(i + "scan=".length()) : s;
        int end = 0;
        while (end < tail.length() && Character.isDigit(tail.charAt(end))) end++;
        if (end == 0) {
            throw new MassqlException(
                    "cannot derive a scan number from mzML spectrum id \""
                            + id
                            + "\" (expected a trailing 'scan=<n>'); MassQL raises ValueError here");
        }
        return Integer.parseInt(tail.substring(0, end));
    }

    // ------------------------------------------------------------------ helpers

    private String attr(String name) {
        return str(xml.getAttributeValue(null, name));
    }

    private int intAttr(String name, int fallback) {
        return parseInt(attr(name), fallback);
    }

    private static String str(CharArray c) {
        return c == null ? null : c.toString();
    }

    private static boolean eq(CharArray c, String s) {
        return c != null && c.equals(s);
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String s, double fallback) {
        if (s == null) return fallback;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void closeQuietly() {
        try {
            mapped.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    protected void releaseResources() {
        try {
            xml.close();
        } catch (XMLStreamException ignored) {
        }
        closeQuietly();
    }

    /** One {@code <binaryDataArray>}: its cvParam accessions and its undecoded base64 text. */
    private static final class Binary {
        String arrayType;
        MzMLBitLength bitLength;
        MzMLCompressionType compression;
        String base64 = "";
    }

    /** Mutable, reused across spectra — retained memory stays bounded by one scan. */
    /** Accumulates one spectrum as it is parsed, then builds the immutable view. */
    private final class Scan {
        int scanId;
        int msLevel;
        double rt;
        int polarity;
        double precmz;
        int ms1scan;
        int charge;
        int peakCount;
        Binary mzArray;
        Binary intensityArray;

        void reset() {
            scanId = 0;
            msLevel = 0;
            rt = 0.0;
            polarity = 0;
            precmz = 0.0;
            ms1scan = 0;
            charge = 0;
            peakCount = 0;
            mzArray = null;
            intensityArray = null;
        }

        void accept(Binary b) {
            if (MzMLCV.cvMzArray.equals(b.arrayType)) mzArray = b;
            else if (MzMLCV.cvIntensityArray.equals(b.arrayType)) intensityArray = b;
            // Any other array type (e.g. retention time arrays on chromatograms) is ignored.
        }

        ScanView toView() {
            double[] mz = decode(mzArray, "m/z");
            double[] in = decode(intensityArray, "intensity");
            int n = Math.min(mz.length, in.length);
            if (mz.length != in.length) {
                diagnostics.add(
                        "scan "
                                + scanId
                                + ": m/z array has "
                                + mz.length
                                + " values but intensity array has "
                                + in.length
                                + "; using "
                                + n);
            }
            // defaultArrayLength is exact, so this allocates once and never grows or copies.
            SpectrumTableBuilder b = new SpectrumTableBuilder(msLevel == 1 ? 1 : 2, n);
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

        private double[] decode(Binary bin, String what) {
            if (bin == null || bin.base64.isEmpty()) return new double[0];
            byte[] raw = bin.base64.getBytes(StandardCharsets.US_ASCII);
            if (bin.bitLength == null) {
                throw new MassqlException(
                        "scan "
                                + scanId
                                + " in "
                                + path
                                + ": the "
                                + what
                                + " array declares no bit length (MS:1000521 or MS:1000523)");
            }
            MzMLBinaryDataInfo info = new MzMLBinaryDataInfo(raw.length, peakCount);
            info.setArrayType(bin.arrayType);
            info.setBitLength(bin.bitLength);
            // An absent compression cvParam means uncompressed, matching mzXML's convention.
            info.setCompressionType(
                    bin.compression == null ? MzMLCompressionType.NO_COMPRESSION : bin.compression);
            try {
                // The vendored decoder already implements the 32-bit rule:
                // Float.intBitsToFloat(readInt()) into a double[] == (double)(float)raw.
                return MzMLPeaksDecoder.decodeToDouble(
                        new ByteArrayInputStream(raw), info, new double[peakCount]);
            } catch (Exception e) {
                throw new MassqlException(
                        "cannot decode the "
                                + what
                                + " array of scan "
                                + scanId
                                + " in "
                                + path
                                + ": "
                                + e.getMessage(),
                        e);
            }
        }
    }
}
