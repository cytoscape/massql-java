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

final class MzmlReader extends AbstractSpectraStream {
    private final ByteBufferInputStream mapped;
    private final XMLStreamReaderImpl xml;
    private final List<String> diagnostics = new ArrayList<>();

    private int skippedHighMsLevel = 0;

    private int previousMs1Scan = 0;

    private final Scan scan = new Scan();
    private ScanView current;

    MzmlReader(Path path) {
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
                if (scan.msLevel > 2) {
                    skippedHighMsLevel++;
                    continue;
                }

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
        int depth = 1;
        boolean inSelectedIon = false;
        boolean inBinaryArray = false;
        Binary bin = null;

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
                } else if (eq(name, "selectedIon")) {
                    inSelectedIon = true;
                } else if (eq(name, "binaryDataArray")) {
                    inBinaryArray = true;
                    bin = new Binary();
                } else if (eq(name, "binary") && inBinaryArray && bin != null) {
                    CharArray text = xml.getElementText();
                    bin.base64 = text == null ? "" : text.toString().trim();
                    depth--;
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                CharArray name = xml.getLocalName();
                if (eq(name, "selectedIon")) {
                    inSelectedIon = false;

                    firstSelectedIonDone = true;
                }
                if (eq(name, "binaryDataArray")) {
                    if (bin != null) scan.accept(bin);
                    inBinaryArray = false;
                    bin = null;
                }
                depth--;
                if (depth == 0) return;
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

    private static final class Binary {
        String arrayType;
        MzMLBitLength bitLength;
        MzMLCompressionType compression;
        String base64 = "";
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

            info.setCompressionType(
                    bin.compression == null ? MzMLCompressionType.NO_COMPRESSION : bin.compression);
            try {
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
