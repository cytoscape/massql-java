package org.cytoscape.massql.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.cytoscape.massql.MassqlException;

/** Opens a spectra file as a {@link SpectraStream}, sniffing the format from its content. */
public final class SpectraFile {
    private SpectraFile() {}

    /** How much of the head to inspect. */
    private static final int SNIFF_BYTES = 8192;

    public static SpectraStream open(Path path) {
        if (path == null) throw new MassqlException("path is null");
        if (!Files.exists(path)) throw new MassqlException("no such file: " + path);
        if (Files.isDirectory(path))
            throw new MassqlException("not a file (is a directory): " + path);
        try {
            if (Files.size(path) == 0) throw new MassqlException("file is empty: " + path);
        } catch (IOException e) {
            throw new MassqlException("cannot stat " + path + ": " + e.getMessage(), e);
        }

        Format format = sniff(path);
        return switch (format) {
            case MGF -> new MgfReader(path);
            case MZML -> new MzmlReader(path);
            case MZXML -> new MzxmlReader(path);
        };
    }

    /** Exposed for tests; production callers use {@link #open}. */
    static Format sniff(Path path) {
        String head = head(path);
        String lower = head.toLowerCase(java.util.Locale.ROOT);

        if (lower.contains("begin ions")) return Format.MGF;

        if (lower.contains("<mzxml") || lower.contains("<msrun")) return Format.MZXML;
        if (lower.contains("<mzml") || lower.contains("<indexedmzml")) return Format.MZML;

        if (!head.contains("<")) return Format.MGF;

        throw new MassqlException(
                "cannot determine format of "
                        + path
                        + " -- expected MGF (BEGIN IONS), mzML (<mzML>) or mzXML (<mzXML>/<msRun>); head begins: "
                        + head.substring(0, Math.min(120, head.length()))
                                .replace('\n', ' ')
                                .trim());
    }

    private static String head(Path path) {
        byte[] buf = new byte[SNIFF_BYTES];
        int n;
        try (InputStream in = Files.newInputStream(path)) {
            n = in.readNBytes(buf, 0, buf.length);
        } catch (IOException e) {
            throw new MassqlException("cannot read " + path + ": " + e.getMessage(), e);
        }
        return new String(buf, 0, Math.max(n, 0), StandardCharsets.UTF_8);
    }
}
