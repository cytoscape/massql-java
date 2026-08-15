package org.cytoscape.massql.io;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.lang.ast.Polarity;
import org.cytoscape.massql.spectra.SpectrumTableBuilder;

final class MgfReader extends AbstractSpectraStream {
    private final BufferedReader reader;
    private final List<String> diagnostics = new ArrayList<>();

    private int blockIndex = 0;
    private final Scan scan = new Scan();
    private ScanView current;

    private int fileDefaultCharge = 1;

    MgfReader(Path path) {
        super(path);
        try {
            this.reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MassqlException("cannot open " + path + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> diagnostics() {
        return Collections.unmodifiableList(diagnostics);
    }

    @Override
    protected ScanView view() {
        return current;
    }

    @Override
    protected boolean advance() {
        try {
            return readBlock();
        } catch (IOException e) {
            throw new MassqlException("error reading " + path + ": " + e.getMessage(), e);
        }
    }

    private boolean readBlock() throws IOException {
        String line;

        while ((line = reader.readLine()) != null) {
            String t = line.trim();
            if (t.equalsIgnoreCase("BEGIN IONS")) break;
            if (blockIndex == 0 && t.regionMatches(true, 0, "CHARGE=", 0, 7)) {
                fileDefaultCharge = firstCharge(t.substring(7), fileDefaultCharge);
            }
        }
        if (line == null) return false;

        blockIndex++;
        scan.reset(fileDefaultCharge);

        double[] mz = new double[64];
        double[] in = new double[64];
        int n = 0;
        boolean sawEnd = false;

        while ((line = reader.readLine()) != null) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith(";") || t.startsWith("!"))
                continue;
            if (t.equalsIgnoreCase("END IONS")) {
                sawEnd = true;
                break;
            }

            int eq = t.indexOf('=');

            if (eq > 0
                    && !Character.isDigit(t.charAt(0))
                    && t.charAt(0) != '.'
                    && t.charAt(0) != '-') {
                applyHeader(
                        t.substring(0, eq).trim().toUpperCase(java.util.Locale.ROOT),
                        t.substring(eq + 1).trim());
                continue;
            }

            if (n == mz.length) {
                mz = Arrays.copyOf(mz, n * 2);
                in = Arrays.copyOf(in, n * 2);
            }
            int sp = firstSeparator(t);
            if (sp < 0) {
                throw new MassqlException(
                        "malformed peak line in "
                                + path
                                + " block "
                                + blockIndex
                                + " (expected 'mz intensity'): "
                                + t);
            }
            double peakMz;
            double peakIntensity;
            try {
                peakMz = Double.parseDouble(t.substring(0, sp));
                peakIntensity = Double.parseDouble(t.substring(sp + 1).trim().split("\\s+")[0]);
            } catch (NumberFormatException e) {
                throw new MassqlException(
                        "unparseable peak in " + path + " block " + blockIndex + ": " + t, e);
            }

            if (peakIntensity == 0.0) continue;

            mz[n] = peakMz;
            in[n] = peakIntensity;
            n++;
        }

        if (!sawEnd) {
            throw new MassqlException(
                    "truncated MGF: block " + blockIndex + " in " + path + " has no END IONS");
        }

        scan.finish(blockIndex, mz, in, n);
        current = scan.toView();
        return true;
    }

    private static int firstSeparator(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') return i;
        }
        return -1;
    }

    private static int firstCharge(String value, int fallback) {
        int i = 0;
        int n = value.length();
        while (i < n && !Character.isDigit(value.charAt(i))) i++;
        int start = i;
        while (i < n && Character.isDigit(value.charAt(i))) i++;
        if (start == i) return fallback;
        try {
            return Integer.parseInt(value.substring(start, i));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void applyHeader(String key, String value) {
        switch (key) {
            case "PEPMASS" -> {
                String first = value.split("\\s+")[0];
                scan.precmz = parseOr(first, 0.0);
            }
            case "CHARGE" -> scan.charge = firstCharge(value, scan.charge);
            case "RTINSECONDS" -> scan.rt = parseOr(value, 0.0) / 60.0;
            case "SCANS" -> {
                try {
                    scan.explicitScanId = Integer.parseInt(value.trim());
                } catch (NumberFormatException ignored) {
                }
            }
            default -> {}
        }
    }

    private static double parseOr(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    protected void releaseResources() {
        try {
            reader.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Scan {
        int explicitScanId;
        int scanId;
        double precmz;
        int charge;
        double rt;
        double[] mz = new double[0];
        double[] in = new double[0];
        int n;

        void reset(int defaultCharge) {
            explicitScanId = 0;
            scanId = 0;
            precmz = 0.0;

            charge = defaultCharge;
            rt = 0.0;
            n = 0;
        }

        void finish(int blockIndex, double[] mz, double[] in, int n) {
            this.scanId = explicitScanId > 0 ? explicitScanId : blockIndex;
            this.mz = mz;
            this.in = in;
            this.n = n;
        }

        ScanView toView() {
            SpectrumTableBuilder b = new SpectrumTableBuilder(2, n);
            b.startScan(scanId, rt, 1, precmz, 0, charge);
            for (int i = 0; i < n; i++) b.addPeak(mz[i], in[i]);

            return new ScanView(
                    scanId,
                    2,
                    rt,
                    Polarity.POSITIVE,
                    precmz == 0.0 ? null : precmz,
                    null,
                    charge,
                    b.build());
        }
    }
}
