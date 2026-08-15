package org.cytoscape.massql;

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

import org.cytoscape.massql.lang.ast.MassqlQuery;
import org.junit.jupiter.api.Test;

class AstEncapsulationTest {
    private static final List<String> FORBIDDEN =
            List.of(
                    "org.antlr.",
                    "io.github.msdk.",
                    "org.cytoscape.massql.io.vendor.",
                    "com.google.common.",
                    "org.slf4j.");

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
        Deque<Class<?>> todo = new ArrayDeque<>(List.of(MassqlQuery.class));
        Set<Class<?>> seen = new HashSet<>();
        List<String> violations = new ArrayList<>();
        while (!todo.isEmpty()) {
            Class<?> c = todo.pop();
            if (!seen.add(c) || !c.getName().startsWith("org.cytoscape.massql")) continue;
            for (Method m : c.getMethods()) {
                if (m.getDeclaringClass() == Object.class) continue;
                check(violations, c.getSimpleName() + "#" + m.getName(), m.getGenericReturnType());
                if (m.getReturnType().getName().startsWith("org.cytoscape.massql")) {
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
        assertTrue(MassqlException.class.isAssignableFrom(MassqlParseException.class));
        assertTrue(RuntimeException.class.isAssignableFrom(MassqlException.class));
    }
}
