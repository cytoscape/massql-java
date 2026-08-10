package edu.ucsd.idekerlab.massql;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import edu.ucsd.idekerlab.massql.lang.ast.MassqlQuery;

/**
 * No third-party type may appear on the public surface.
 *
 * <p>This is what keeps the parser swappable — for a hand-written one, or the remote
 * {@code /parse} escape hatch — without touching the engine or any caller. It is cheap to
 * assert and easy to violate by accident, e.g. by returning an ANTLR {@code ParserRuleContext}
 * or accepting a {@code Token} in a helper that later becomes public.
 *
 * <p>The same check catches an MSDK or vendored reader type leaking out of Tech_Step6/7's readers, which is
 * why the forbidden list is a prefix allowlist rather than a list of the types known today.
 */
class AstEncapsulationTest {

    /** Package prefixes that must never appear in a public signature. */
    private static final List<String> FORBIDDEN =
            List.of(
                    "org.antlr.", // the parser must stay swappable
                    "io.github.msdk.", // vendoring source, never a dependency
                    "edu.ucsd.idekerlab.massql.io.vendor.", // vendored parser internals
                    "com.google.common.", // Guava is deliberately absent
                    "org.slf4j." // the SDK logs nothing
                    );

    private static final List<Class<?>> PUBLIC_API =
            List.of(
                    Massql.class,
                    MassqlOptions.class,
                    MassqlException.class,
                    MassqlParseException.class,
                    MassqlQuery.class);

    @Test
    void noForbiddenTypeAppearsInAnyPublicSignature() {
        List<String> violations = new ArrayList<>();
        for (Class<?> c : PUBLIC_API) {
            for (Method m : c.getMethods()) {
                if (m.getDeclaringClass() == Object.class) continue;
                check(violations, c + "#" + m.getName() + " return", m.getGenericReturnType());
                for (Type p : m.getGenericParameterTypes()) {
                    check(violations, c + "#" + m.getName() + " param", p);
                }
            }
            for (Constructor<?> k : c.getConstructors()) {
                for (Type p : k.getGenericParameterTypes()) {
                    check(violations, c + " ctor param", p);
                }
            }
            for (Field f : c.getFields()) {
                check(violations, c + "#" + f.getName(), f.getGenericType());
            }
        }
        assertTrue(
                violations.isEmpty(),
                () ->
                        "third-party types leaked onto the public API:\n  "
                                + String.join("\n  ", violations));
    }

    private static void check(List<String> out, String where, Type t) {
        String n = t.getTypeName();
        for (String bad : FORBIDDEN) {
            if (n.contains(bad)) out.add(where + " -> " + n);
        }
    }

    @Test
    void theAstPackageItselfIsFreeOfThirdPartyTypes() {
        // Walk the AST reachable from MassqlQuery: its own records must be clean too, not
        // just the entry points.
        Deque<Class<?>> todo = new ArrayDeque<>(List.of(MassqlQuery.class));
        Set<Class<?>> seen = new HashSet<>();
        List<String> violations = new ArrayList<>();
        while (!todo.isEmpty()) {
            Class<?> c = todo.pop();
            if (!seen.add(c) || !c.getName().startsWith("edu.ucsd.idekerlab.massql")) continue;
            for (Method m : c.getMethods()) {
                if (m.getDeclaringClass() == Object.class) continue;
                check(violations, c.getSimpleName() + "#" + m.getName(), m.getGenericReturnType());
                if (m.getReturnType().getName().startsWith("edu.ucsd.idekerlab.massql")) {
                    todo.push(m.getReturnType());
                }
            }
            for (Class<?> nested :
                    c.getPermittedSubclasses() == null
                            ? new Class<?>[0]
                            : c.getPermittedSubclasses()) {
                todo.push(nested);
            }
        }
        assertTrue(
                violations.isEmpty(),
                () ->
                        "third-party types reachable from the AST:\n  "
                                + String.join("\n  ", violations));
    }

    @Test
    void massqlParseExceptionIsCatchableAsMassqlException() {
        // The app catches one type; a parse failure must not require a separate catch block.
        assertTrue(MassqlException.class.isAssignableFrom(MassqlParseException.class));
        assertTrue(RuntimeException.class.isAssignableFrom(MassqlException.class));
    }
}
