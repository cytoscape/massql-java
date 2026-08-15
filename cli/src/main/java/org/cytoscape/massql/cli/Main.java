package org.cytoscape.massql.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import org.cytoscape.massql.ExecutionResult;
import org.cytoscape.massql.Massql;
import org.cytoscape.massql.MassqlException;
import org.cytoscape.massql.MassqlOptions;
import org.cytoscape.massql.MassqlParseException;
import org.cytoscape.massql.io.SpectraFile;
import org.cytoscape.massql.io.SpectraStream;
import org.cytoscape.massql.result.ResultJson;

import com.google.gson.GsonBuilder;

/**
 * The standalone MassQL command-line tool — a batch filter mirroring the reference tool's
 * interface.
 */
public final class Main {
    /** Matches the reference tool's {@code --precursor-tol-ppm} default. */
    private static final double DEFAULT_TOL_PPM = MassqlOptions.DEFAULT_PRECURSOR_TOL_PPM;

    private static final int OK = 0;
    private static final int ERR_CONTENT = 1;
    private static final int ERR_USAGE = 2;

    private static final String USAGE =
            """
            Usage: massql-java-cli <spectra-file> [<query-file>|-] [options]

              <spectra-file>   .mgf, .mzML or .mzXML (format is sniffed from content, not extension)
              <query-file>     file containing one MassQL query
              -                read the query from stdin

            Options:
              -q, --query <STRING>           the query itself, inline
              --precursor-tol-ppm <double>   tolerance for matching the precursor peak in MS1 \
            (default 20.0)
              --output <FILE|->              write JSON to FILE; '-' means stdout (the default)
              --pretty <true|false>          indent the JSON 2 spaces (default true)
              -h, --help                     this message

            Give the query exactly one way: a file, '-' for stdin, or --query.

            Exit: 0 ok (including no matches)   1 unreadable content   2 usage error""";

    private Main() {}

    /** Entry point. */
    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    /** Runs one invocation and returns its exit code. */
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (UsageException e) {
            return usage(err, e.getMessage());
        }

        if (parsed.help) {
            out.println(USAGE);
            return OK;
        }

        String problem = checkReadable(parsed.spectra, "spectra file");
        if (problem != null) return usage(err, problem);

        if (parsed.query != null) {
            problem = checkReadable(parsed.query, "query file");
            if (problem != null) return usage(err, problem);
        }

        String queryText;
        try {
            queryText = resolveQuery(parsed, in);
        } catch (IOException e) {
            return usage(
                    err, "cannot read query from " + parsed.querySource() + ": " + e.getMessage());
        }
        if (queryText.isEmpty()) {
            return usage(err, "the query is empty: " + parsed.querySource());
        }

        MassqlOptions opts = MassqlOptions.defaults().withPrecursorTolPpm(parsed.tolPpm);
        ExecutionResult result;
        try {
            var q = Massql.parse(queryText);
            try (SpectraStream s = SpectraFile.open(parsed.spectra)) {
                result = Massql.executeWithDiagnostics(q, s, opts);
            }
        } catch (MassqlParseException e) {
            err.println("cannot run query: " + e.getMessage());
            return ERR_USAGE;
        } catch (MassqlException e) {
            err.println("cannot read " + parsed.spectra + ": " + e.getMessage());
            return ERR_CONTENT;
        }

        for (String d : result.diagnostics()) {
            err.println(d);
        }

        GsonBuilder gson = new GsonBuilder().serializeNulls();
        if (parsed.pretty) gson.setPrettyPrinting();
        String json = gson.create().toJson(new ResultJson(result.rows()));
        String payload = json + System.lineSeparator();

        if (parsed.output == null) {
            out.print(payload);
            out.flush();
            return OK;
        }
        return writeAtomically(parsed.output, payload, err);
    }

    /** The query text from whichever single source was given, stripped. */
    private static String resolveQuery(Args parsed, InputStream in) throws IOException {
        if (parsed.queryString != null) return parsed.queryString.strip();
        if (parsed.queryFromStdin) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        return Files.readString(parsed.query, StandardCharsets.UTF_8).strip();
    }

    /** Writes {@code payload} so that no consumer can ever observe a partial file. */
    private static int writeAtomically(Path target, String payload, PrintStream err) {
        Path dir = target.toAbsolutePath().getParent();
        Path tmp = null;
        try {
            tmp = dir.resolve(target.getFileName() + ".tmp");
            Files.writeString(tmp, payload, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            return OK;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            deleteQuietly(tmp);
            return usage(err, "cannot write --output " + target + ": " + e.getMessage());
        }
    }

    private static void deleteQuietly(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    /** The gate that separates exit 2 from exit 1, run before anything is opened. */
    private static String checkReadable(Path p, String label) {
        if (!Files.exists(p)) return "no such " + label + ": " + p;
        if (!Files.isRegularFile(p)) return label + " is not a regular file: " + p;
        if (!Files.isReadable(p)) return label + " is not readable: " + p;
        try {
            if (Files.size(p) == 0) return label + " is empty: " + p;
        } catch (IOException e) {
            return "cannot stat " + label + " " + p + ": " + e.getMessage();
        }
        return null;
    }

    /** Usage errors print the message AND the usage text, both to stderr, and exit 2. */
    private static int usage(PrintStream err, String message) {
        err.println(message);
        err.println();
        err.println(USAGE);
        return ERR_USAGE;
    }

    /** Signals a malformed command line; carries the message the user should see. */
    private static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    /** The parsed command line. */
    private static final class Args {
        private Path spectra;
        private Path query;
        private String queryString;
        private boolean queryFromStdin;
        private Path output;
        private double tolPpm = DEFAULT_TOL_PPM;

        /**
         * Human-readable by default: a person running a query in a terminal is the common case,
         * and {@code --pretty false} is how a caller asks for the machine form.
         */
        private boolean pretty = true;

        private boolean help;

        /** Names the chosen source, so a failure message says which one was empty or unreadable. */
        String querySource() {
            if (queryString != null) return "--query";
            if (queryFromStdin) return "stdin";
            return "query file " + query;
        }

        /** How many query sources the command line supplied — must be exactly 1. */
        private int querySourceCount() {
            return (query != null ? 1 : 0)
                    + (queryString != null ? 1 : 0)
                    + (queryFromStdin ? 1 : 0);
        }

        /** Positional order matches the reference tool: spectra file, then query file. */
        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-h", "--help" -> {
                        a.help = true;
                        return a;
                    }

                    case "-q", "--query" -> a.queryString = value(args, ++i, arg);
                    case "--precursor-tol-ppm" -> a.tolPpm =
                            positiveDouble(arg, value(args, ++i, arg));
                    case "--pretty" -> a.pretty = bool(arg, value(args, ++i, arg));
                    case "--output" -> {
                        String v = value(args, ++i, arg);

                        a.output = "-".equals(v) ? null : path(v, arg);
                    }
                    default -> {
                        if (arg.startsWith("-") && arg.length() > 1) {
                            throw new UsageException("unknown option: " + arg);
                        }
                        if (a.spectra == null) {
                            if ("-".equals(arg)) {
                                throw new UsageException(
                                        "the spectra file cannot be read from stdin: it is memory-mapped"
                                                + " and its format is sniffed from the head, so it must be"
                                                + " a real file. '-' selects stdin for the QUERY only.");
                            }
                            a.spectra = path(arg, "<spectra-file>");
                        } else if ("-".equals(arg)) {
                            a.queryFromStdin = true;
                        } else if (a.query == null) {
                            a.query = path(arg, "<query-file>");
                        } else {
                            throw new UsageException("unexpected extra argument: " + arg);
                        }
                    }
                }
            }
            if (a.spectra == null) {
                throw new UsageException("<spectra-file> is required");
            }

            int sources = a.querySourceCount();
            if (sources == 0) {
                throw new UsageException(
                        "no query given -- supply a <query-file>, '-' to read stdin, or --query <STRING>");
            }
            if (sources > 1) {
                throw new UsageException(
                        "the query was given more than one way ("
                                + a.describeSources()
                                + ") -- choose exactly one");
            }
            return a;
        }

        /** Lists the sources actually supplied, so the rejection names what the user typed. */
        private String describeSources() {
            StringBuilder sb = new StringBuilder();
            if (query != null) sb.append("query file ").append(query);
            if (queryFromStdin) sb.append(sb.length() > 0 ? ", " : "").append("'-' (stdin)");
            if (queryString != null) sb.append(sb.length() > 0 ? ", " : "").append("--query");
            return sb.toString();
        }

        private static String value(String[] args, int i, String flag) {
            if (i >= args.length) throw new UsageException(flag + " requires a value");
            return args[i];
        }

        /**
         * Strictly {@code true} or {@code false}. {@code Boolean.parseBoolean} is deliberately NOT
         * used: it maps every unrecognised string to {@code false}, so {@code --pretty ture} would
         * silently disable formatting instead of telling the caller they mistyped.
         */
        private static boolean bool(String flag, String raw) {
            if ("true".equals(raw)) return true;
            if ("false".equals(raw)) return false;
            throw new UsageException(flag + " expects true or false, got: " + raw);
        }

        private static double positiveDouble(String flag, String raw) {
            double d;
            try {
                d = Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw new UsageException(flag + " expects a number, got: " + raw);
            }

            if (!Double.isFinite(d) || d < 0) {
                throw new UsageException(
                        flag + " must be a finite, non-negative number, got: " + raw);
            }
            return d;
        }

        private static Path path(String raw, String what) {
            try {
                return Paths.get(raw);
            } catch (InvalidPathException e) {
                throw new UsageException("not a usable path for " + what + ": " + raw);
            }
        }
    }
}
