package edu.ucsd.idekerlab.massql.io;

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

import edu.ucsd.idekerlab.massql.MassqlException;
import edu.ucsd.idekerlab.massql.spectra.SpectrumTable;
import edu.ucsd.idekerlab.massql.spectra.SpectrumTableBuilder;

/**
 * Streaming MGF reader — hand-written, one {@code BEGIN IONS}…{@code END IONS} block at a time.
 *
 * <p>Hand-written because the alternative, {@code uk.ac.ebi.pride.tools:mgf-parser} (28 KB), drags
 * fastutil (23 MB), logback and both JAXB stacks — every one forbidden by `DEPENDENCY_POLICY.md`.
 *
 * <p><b>The specification is {@code _load_data_mgf_pyteomics}</b> (`msql_fileloading.py:155-244`), not
 * the MGF format documentation. Where the two differ, MassQL wins, because Step 8 asserts bit-identity
 * against what MassQL loaded.
 *
 * <p>MGF is a text format, so peaks are parsed as the block is read rather than deferred behind an
 * offset the way the binary formats do. Retained memory is still bounded by one scan.
 */
final class MgfReader extends AbstractSpectraStream {

    private final BufferedReader reader;
    private final List<String> diagnostics = new ArrayList<>();

    private int blockIndex = 0; // 1-based once incremented; the scan-id fallback
    private final Scan scan = new Scan();

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
        return scan;
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

        // Skip to BEGIN IONS, tolerating blank lines, comments and file-level headers
        // (PlusRise.mgf and DP00570 both carry a COM= / CHARGE= preamble).
        while ((line = reader.readLine()) != null) {
            String t = line.trim();
            if (t.equalsIgnoreCase("BEGIN IONS")) break;
        }
        if (line == null) return false;

        blockIndex++;
        scan.reset();

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
            // A header line is KEY=VALUE; anything else is a peak. Checking for '=' before
            // attempting a numeric parse keeps an unknown header from being read as a peak.
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
                // A malformed peak line is an error, not something to skip: silently dropping peaks
                // would change tic and base_peak and look like a decoder bug at Step 8.
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

            // Correction C36: MGF drops ZERO-INTENSITY peaks. `_load_data_mgf_pyteomics` opens its
            // peak
            // loop with `if intensity == 0: continue` (msql_fileloading.py), so such a peak never
            // becomes
            // a row and MassQL cannot match it, count it, or sum it.
            //
            // ⚠ MGF ONLY. The mzML and mzXML loaders have no such guard -- small.mzML's parity dump
            // carries eight leading `0x0.0p+0` intensities, retained on both sides. Applying this
            // to the
            // other readers would break the Step 8 gate on every mzML fixture.
            //
            // This was latent: no MGF fixture contained a zero-intensity peak, so the Step 8 gate
            // passed
            // while unable to detect the divergence. micro_zeroint.mgf exists to close that.
            //
            // iNorm/iTicNorm are unaffected: MassQL computes i_max/i_sum from the FULL array
            // *before* the
            // skip, and a zero changes neither a max nor a sum -- so our builder's denominators,
            // computed
            // over the retained peaks, are identical.
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
        return true;
    }

    private static int firstSeparator(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t') return i;
        }
        return -1;
    }

    private void applyHeader(String key, String value) {
        switch (key) {
            case "PEPMASS" -> {
                // "PEPMASS=491.555664 3058030.0000" -- a second token is precursor intensity;
                // ignore it.
                String first = value.split("\\s+")[0];
                scan.precmz = parseOr(first, 0.0);
            }
            case "CHARGE" -> {
                // "2+" / "2-" / "2". Absent is handled in Scan.reset(): default 1, NOT 0.
                String v = value.trim();
                if (!v.isEmpty()) {
                    char last = v.charAt(v.length() - 1);
                    if (last == '+' || last == '-') v = v.substring(0, v.length() - 1);
                    try {
                        scan.charge = Integer.parseInt(v.trim());
                    } catch (NumberFormatException ignored) {
                        scan.charge = 1; // matches the pyteomics loader's `except: charge = 1`
                    }
                }
            }
            case "RTINSECONDS" -> scan.rt = parseOr(value, 0.0) / 60.0;
            case "SCANS" -> {
                try {
                    scan.explicitScanId = Integer.parseInt(value.trim());
                } catch (NumberFormatException ignored) {
                    // Leave unset; the block index is the documented fallback.
                }
            }
            default -> {
                /* TITLE and everything else are not part of the loaded contract */
            }
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

    /** Mutable, reused across blocks — retained memory stays bounded by one scan. */
    private static final class Scan implements ScanView {
        int explicitScanId;
        int scanId;
        double precmz;
        int charge;
        double rt;
        double[] mz = new double[0];
        double[] in = new double[0];
        int n;

        void reset() {
            explicitScanId = 0;
            scanId = 0;
            precmz = 0.0; // 0 sentinel: not recorded
            charge = 1; // ⚠ Correction C6: absent CHARGE is 1, NOT 0 -- so MGF charge is
            // never null downstream, and a genuine 1+ is indistinguishable
            // from an absent one. plusrise golden: {1:653, 2:10, 3:1}, no nulls.
            rt = 0.0; // ⚠ absent RTINSECONDS is 0.0, a REAL value, never null
            n = 0;
        }

        void finish(int blockIndex, double[] mz, double[] in, int n) {
            // Correction C7: SCANS= when present, else the 1-based block index.
            this.scanId = explicitScanId > 0 ? explicitScanId : blockIndex;
            this.mz = mz;
            this.in = in;
            this.n = n;
        }

        @Override
        public int scanId() {
            return scanId;
        }

        @Override
        public int msLevel() {
            return 2;
        } // MGF is an MS2-only peak list

        @Override
        public double rt() {
            return rt;
        }

        /**
         * MGF polarity is a hardcoded <b>1</b>, not 0 — Correction C33.
         *
         * <p>Correction C8 said MGF polarity "is not read on the live path" and inferred 0 from that. The
         * first half is true: no MGF header supplies polarity. The inference was wrong. Both MGF loaders
         * write {@code "polarity": 1  # Default} into every peak dict
         * (`msql_fileloading.py:67` and `:86`), so MassQL reports **positive** for every MGF row.
         *
         * <p>Measured across all three MGF fixtures — {@code micro.mgf} 7 rows, {@code DP00570_F02.mgf}
         * 107,178, {@code PlusRise.mgf} 758,544 — the polarity distribution is {@code {1: all}}. Not one 0.
         *
         * <p>Found by {@code ReaderParityIT}, the Step 8 gate, before any query logic existed. Returning 0
         * here would have failed the Step 12 differential on the polarity column for **every MGF row**, and
         * at that layer it would have looked like a collation bug.
         */
        @Override
        public int polarity() {
            return 1;
        }

        @Override
        public double precmz() {
            return precmz;
        }

        @Override
        public int ms1scan() {
            return 0;
        } // hardcoded 0 (msql_fileloading.py:394)

        @Override
        public int charge() {
            return charge;
        }

        @Override
        public int peakCount() {
            return n;
        }

        @Override
        public SpectrumTable materialize() {
            SpectrumTableBuilder b = new SpectrumTableBuilder(2, n); // exact: peaks already counted
            b.startScan(scanId, rt, 0, precmz, 0, charge);
            for (int i = 0; i < n; i++) b.addPeak(mz[i], in[i]);
            return b.build();
        }
    }
}
