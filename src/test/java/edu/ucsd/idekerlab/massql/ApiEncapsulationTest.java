package edu.ucsd.idekerlab.massql;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import edu.ucsd.idekerlab.massql.io.ScanView;
import edu.ucsd.idekerlab.massql.io.SpectraFile;
import edu.ucsd.idekerlab.massql.io.SpectraStream;
import edu.ucsd.idekerlab.massql.lang.ast.MassqlQuery;
import edu.ucsd.idekerlab.massql.result.ResultJson;
import edu.ucsd.idekerlab.massql.result.ScanInfoResult;

/**
 * Every type on the public surface is either a JDK type or one of ours — <b>and is itself public</b>.
 *
 * <p>This is the check that keeps the parser and the readers swappable. ANTLR could be replaced with
 * a hand-written parser, or the vendored MSDK decoders re-synced, without any caller noticing —
 * but only while nothing from those layers has leaked into a signature.
 *
 * <h2>Why an allowlist, when {@code AstEncapsulationTest} already uses a blocklist</h2>
 *
 * <p>They are complements, and the difference is what they catch. A blocklist of known offenders
 * ({@code org.antlr.}, {@code io.github.msdk.}, …) cannot catch a <i>new</i> third-party type that
 * nobody thought to add to it. This test inverts that: anything that is not a JDK type or one of
 * ours is a violation by default, so a future dependency leaking out fails here without anyone
 * having to predict it.
 *
 * <h2>The second rule, and why it exists</h2>
 *
 * <p>"One of ours" is not sufficient on its own. {@code Format} lives in {@code massql.io} and is
 * therefore ours, but Correction <b>C42</b> narrowed it to <b>package-private</b> precisely so it
 * appears in no public signature — a consumer cannot name a type they cannot see, so exposing one is
 * a compile error waiting to happen for them and an unusable API for everyone. Asserting that every
 * type in a public signature is itself {@code public} catches that, and catches the next one.
 */
class ApiEncapsulationTest {

    /**
     * The published surface — what a consumer, including the CLI, actually codes against.
     *
     * <p>{@code exec.*} is deliberately absent: those classes are public for the CLI project to
     * reach across the module boundary, not because a consumer should call them. {@code docs/SDK.md}
     * documents this list as the contract.
     */
    private static final List<Class<?>> PUBLIC_API =
            List.of(
                    Massql.class,
                    MassqlOptions.class,
                    MassqlException.class,
                    MassqlParseException.class,
                    ExecutionResult.class,
                    ScanInfoResult.class,
                    ResultJson.class,
                    SpectraFile.class,
                    SpectraStream.class,
                    ScanView.class,
                    MassqlQuery.class);

    @Test
    void everyTypeOnThePublicSurfaceIsAJdkTypeOrOneOfOurs() {
        List<String> violations = new ArrayList<>();
        for (Class<?> c : PUBLIC_API) {
            for (Method m : c.getMethods()) {
                if (m.getDeclaringClass() == Object.class) continue;
                String where = c.getSimpleName() + "#" + m.getName();
                check(violations, where + " return", m.getGenericReturnType());
                for (Type p : m.getGenericParameterTypes()) {
                    check(violations, where + " param", p);
                }
            }
            for (Constructor<?> k : c.getConstructors()) {
                for (Type p : k.getGenericParameterTypes()) {
                    check(violations, c.getSimpleName() + " ctor param", p);
                }
            }
            for (Field f : c.getFields()) {
                check(violations, c.getSimpleName() + "#" + f.getName(), f.getGenericType());
            }
        }
        assertTrue(
                violations.isEmpty(),
                () ->
                        "the public API exposes types it must not:\n  "
                                + String.join("\n  ", violations));
    }

    @Test
    void formatStaysInvisible() {
        // The specific case C42 created, asserted by name so the reason survives: SpectraStream
        // used
        // to carry a format() accessor whose ONLY caller was a test. Removing it let Format become
        // package-private, so it now appears in no public signature at all. This fails loudly if
        // someone re-widens it "for convenience".
        Class<?> format;
        try {
            format = Class.forName("edu.ucsd.idekerlab.massql.io.Format");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Format has been renamed or removed; update this test", e);
        }
        assertTrue(
                !Modifier.isPublic(format.getModifiers()),
                "Format must stay package-private (C42) -- it is an implementation detail of which "
                        + "reader was chosen, and a public API cannot mention a type callers cannot see");
    }

    /** Recurses through generics, so {@code List<SomeLeakedType>} cannot hide inside a container. */
    private static void check(List<String> out, String where, Type t) {
        if (t instanceof ParameterizedType p) {
            check(out, where, p.getRawType());
            for (Type arg : p.getActualTypeArguments()) {
                check(out, where, arg);
            }
            return;
        }
        if (!(t instanceof Class<?> c)) return; // type variables and wildcards carry no package
        while (c.isArray()) c = c.getComponentType();
        if (c.isPrimitive()) return;

        String name = c.getName();
        boolean jdk = name.startsWith("java.") || name.startsWith("javax.");
        boolean ours = name.startsWith("edu.ucsd.idekerlab.massql.");
        boolean vendored = name.startsWith("edu.ucsd.idekerlab.massql.io.vendor.");

        if (vendored) {
            out.add(where + " -> " + name + " (vendored MSDK internals must not escape)");
        } else if (!jdk && !ours) {
            out.add(where + " -> " + name + " (neither a JDK type nor one of ours)");
        } else if (ours && !Modifier.isPublic(c.getModifiers())) {
            out.add(where + " -> " + name + " (ours, but not public -- callers cannot name it)");
        }
    }
}
