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

/**
 * The standalone MassQL command-line tool — a batch filter mirroring {@code massql_query.py}.
 *
 * <p>Argument order and the {@code --precursor-tol-ppm} default match the Python reference exactly,
 * because the differential invokes both with the same argv shape and compares the results.
 * {@code --output} and the two extra query sources below are deliberate additions.
 *
 * <h2>Where the query comes from</h2>
 *
 * <p><b>Exactly one</b> of three sources, always chosen explicitly:
 *
 * <table border="1">
 *   <caption>Query sources</caption>
 *   <tr><th>Form</th><th>Meaning</th></tr>
 *   <tr><td>{@code <query-file>}</td><td>read that file</td></tr>
 *   <tr><td>{@code -} in the query position</td><td>read <b>stdin</b>, so the tool composes into a
 *       pipeline — symmetric with {@code --output -} meaning stdout</td></tr>
 *   <tr><td>{@code -q}, {@code --query}</td><td>the query <b>inline</b>, for one-liners</td></tr>
 * </table>
 *
 * <p>Supplying none, or more than one, is a usage error. There is deliberately <b>no precedence rule</b>:
 * two sources means the caller is unsure which one runs, and silently picking one hides that. A repeated
 * {@code -q} is an ordinary last-wins override, matching {@code --precursor-tol-ppm}.
 *
 * <p>⚠ {@code -} is <b>not</b> accepted for the spectra file. Readers memory-map their input and the
 * format is sniffed by reading the head before parsing, so a non-seekable stream cannot work — a real
 * constraint rather than an arbitrary restriction.
 *
 * <h2>Stream discipline</h2>
 *
 * <p><b>stdout carries the JSON array and nothing else, ever.</b> Everything else — diagnostics,
 * warnings, errors, usage — goes to stderr, on every output mode. That is the Unix convention for a
 * batch filter, it is what makes {@code | jq} work, and it is what the reference deliberately does.
 *
 * <p>⚠ This is the <b>CLI's</b> contract, not the SDK's. The SDK writes to no stream at all; it
 * returns diagnostics and lets its caller decide. Conflating the two is how a library ends up
 * printing into someone else's application.
 *
 * <h2>Exit codes</h2>
 *
 * <table border="1">
 *   <caption>Exit codes and what distinguishes them</caption>
 *   <tr><th>Code</th><th>Meaning</th></tr>
 *   <tr><td>0</td><td>Success — <b>including a query that matched nothing</b>, which prints
 *       {@code []}. An empty result is a valid answer, not a failure</td></tr>
 *   <tr><td>1</td><td>The file exists and is readable, but its <b>content</b> will not parse</td></tr>
 *   <tr><td>2</td><td>Usage — bad args, missing file, empty query, unsupported query, unwritable
 *       {@code --output}</td></tr>
 * </table>
 *
 * <p><b>1 and 2 are not mechanically distinguishable from the exception type</b>:
 * {@code SpectraFile.open} throws a plain {@code MassqlException} for <i>both</i> "no such file"
 * (which belongs at 2) and "cannot determine format" (which belongs at 1), and matching on message
 * text would be worse than the problem. So this class separates them itself, with
 * {@link #checkReadable} <b>before</b> opening anything. The rule reads the way the table does:
 * <i>could the user have known from the command line alone?</i>
 */
public final class Main {

    /** Matches {@code massql_query.py}'s {@code --precursor-tol-ppm} default. */
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
              -h, --help                     this message

            Give the query exactly one way: a file, '-' for stdin, or --query.

            Exit: 0 ok (including no matches)   1 unreadable content   2 usage error""";

    private Main() {}

    /** Entry point. The only place in this project that calls {@code System.exit}. */
    public static void main(String[] args) {
        // The ONLY System.exit in this project. Everything below returns a code instead: an exit
        // call reachable from library code would take down any application that embedded it.
        System.exit(run(args, System.in, System.out, System.err));
    }

    /**
     * Runs one invocation and <b>returns</b> its exit code.
     *
     * <p>Never calls {@code System.exit}, and never touches {@code System.out}, {@code System.err} or
     * {@code System.in} — all three streams are parameters. That is what lets {@code MainExitCodeTest}
     * assert codes at all, and what lets {@code MainStreamDisciplineTest} and
     * {@code MainQuerySourceTest} drive output and stdin without {@code System.setOut} /
     * {@code System.setIn}, which are global mutable state that makes tests order-dependent.
     *
     * <p>{@code in} is read <b>only</b> when the query source is {@code -}, so an invocation with a
     * query file never touches stdin and therefore cannot block waiting on a terminal.
     */
    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) {
        Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (UsageException e) {
            return usage(err, e.getMessage());
        }

        if (parsed.help) {
            // Asked for deliberately, so it is the program's output: stdout, exit 0. When usage
            // accompanies an ERROR it goes to stderr instead -- see usage() -- because exit 2 must
            // never put non-JSON on stdout.
            out.println(USAGE);
            return OK;
        }

        String problem = checkReadable(parsed.spectra, "spectra file");
        if (problem != null) return usage(err, problem);

        // The query file is checked here rather than in resolveQuery so that a bad PATH is reported
        // by the same gate as a bad spectra path -- the other two sources have no path to check.
        if (parsed.query != null) {
            problem = checkReadable(parsed.query, "query file");
            if (problem != null) return usage(err, problem);
        }

        String queryText;
        try {
            queryText = resolveQuery(parsed, in);
        } catch (IOException e) {
            // Acquiring the query failed. Not a spectra CONTENT failure, so this is 2, not 1 --
            // the same call this method already made for an unreadable query file.
            return usage(
                    err, "cannot read query from " + parsed.querySource() + ": " + e.getMessage());
        }
        if (queryText.isEmpty()) {
            return usage(err, "the query is empty: " + parsed.querySource());
        }

        MassqlOptions opts = MassqlOptions.defaults().withPrecursorTolPpm(parsed.tolPpm);
        ExecutionResult result;
        try {
            // ⚠ MassqlParseException extends MassqlException, so it MUST be caught first. Reversed,
            // an unsupported query would report as exit 1 and lose the construct name that
            // the differential asserts on.
            var q = Massql.parse(queryText);
            try (SpectraStream s = SpectraFile.open(parsed.spectra)) {
                result = Massql.executeWithDiagnostics(q, s, opts);
            }
        } catch (MassqlParseException e) {
            // Name the offending construct: "formula() is not supported in this version" tells the
            // user what to change, where "syntax error" does not.
            err.println("cannot run query: " + e.getMessage());
            return ERR_USAGE;
        } catch (MassqlException e) {
            // Past checkReadable, so this is a CONTENT failure, not a usage one.
            err.println("cannot read " + parsed.spectra + ": " + e.getMessage());
            return ERR_CONTENT;
        }

        // Diagnostics before the payload: if writing the payload then fails, the user still gets
        // the explanation of what the run found.
        for (String d : result.diagnostics()) {
            err.println(d);
        }

        // ONE render, TWO sinks. ResultJson.write returns a single String, so both destinations
        // necessarily receive identical bytes -- there is no second render to drift. The trailing
        // newline is appended HERE, at the single point where the string meets its sink, because it
        // is a console convention rather than part of the JSON document, and because appending it
        // per-sink is exactly how the two modes would come to differ.
        String payload = ResultJson.write(result.rows()) + System.lineSeparator();

        if (parsed.output == null) {
            out.print(payload);
            out.flush();
            return OK;
        }
        return writeAtomically(parsed.output, payload, err);
    }

    /**
     * The query text from whichever single source was given, stripped.
     *
     * <p>All three sources converge here so there is <b>one</b> {@code strip()} and, at the call site,
     * one emptiness check. Splitting those per source is how the forms would come to disagree about
     * what counts as an empty query.
     *
     * <p>{@code .strip()} mirrors {@code massql_query.py}'s {@code .read().strip()}; the committed
     * {@code .massql} files end with a newline, and a shell heredoc adds one too.
     */
    private static String resolveQuery(Args parsed, InputStream in) throws IOException {
        if (parsed.queryString != null) return parsed.queryString.strip();
        if (parsed.queryFromStdin) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        return Files.readString(parsed.query, StandardCharsets.UTF_8).strip();
    }

    /**
     * Writes {@code payload} so that no consumer can ever observe a partial file.
     *
     * <p>Temp file in the <b>same directory</b> as the target, then an atomic rename: same directory
     * so the move stays within one filesystem, since {@code ATOMIC_MOVE} across filesystems throws.
     * On any failure the temp is removed and <b>no output file is left behind</b> — a truncated
     * result that looks complete is worse than no result at all.
     */
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
            // Best effort. Reporting a cleanup failure would bury the real error above it.
        }
    }

    /**
     * The gate that separates exit 2 from exit 1, run <b>before</b> anything is opened.
     *
     * @return a message describing the problem, or null if the file is usable
     */
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

        /**
         * Positional order matches {@code massql_query.py}: spectra file, then query file.
         *
         * <p>Every rejection here is exit 2 by construction — each one is knowable from the command
         * line alone, without opening a thing.
         */
        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "-h", "--help" -> {
                        a.help = true;
                        return a; // Nothing else matters; do not reject a stray arg alongside it.
                    }
                        // Last-wins if repeated, exactly like --precursor-tol-ppm below. Overriding
                        // one
                        // flag is ordinary; mixing two different SOURCES is what gets rejected.
                    case "-q", "--query" -> a.queryString = value(args, ++i, arg);
                    case "--precursor-tol-ppm" -> a.tolPpm =
                            positiveDouble(arg, value(args, ++i, arg));
                    case "--output" -> {
                        String v = value(args, ++i, arg);
                        // '-' is an explicit request for stdout, identical to omitting the flag.
                        a.output = "-".equals(v) ? null : path(v, arg);
                    }
                    default -> {
                        // A lone "-" is length 1, so it falls through this guard on purpose and is
                        // handled as a positional below.
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
                            // Recorded unconditionally, even if a query file was already given: the
                            // exactly-one check below then reports the real problem ("given more
                            // than
                            // one way") instead of the misleading "unexpected extra argument".
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
            // Exactly one source. No precedence rule on purpose: two sources means the caller is
            // unsure which one runs, and quietly choosing for them hides that.
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

        private static double positiveDouble(String flag, String raw) {
            double d;
            try {
                d = Double.parseDouble(raw);
            } catch (NumberFormatException e) {
                throw new UsageException(flag + " expects a number, got: " + raw);
            }
            // A negative or non-finite tolerance is not a narrower window, it is nonsense -- and it
            // would silently match nothing rather than fail.
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
