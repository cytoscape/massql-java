package org.cytoscape.massql;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

import org.cytoscape.massql.io.ScanView;
import org.cytoscape.massql.io.SpectraFile;
import org.cytoscape.massql.io.SpectraStream;
import org.cytoscape.massql.lang.ast.MassqlQuery;
import org.cytoscape.massql.result.ResultJson;
import org.cytoscape.massql.result.ScanInfoResult;
import org.junit.jupiter.api.Test;

class ApiEncapsulationTest {
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
        Class<?> format;
        try {
            format = Class.forName("org.cytoscape.massql.io.Format");
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Format has been renamed or removed; update this test", e);
        }
        assertTrue(
                !Modifier.isPublic(format.getModifiers()),
                "Format must stay package-private -- it is an implementation detail of which "
                        + "reader was chosen, and a public API cannot mention a type callers cannot see");
    }

    private static void check(List<String> out, String where, Type t) {
        if (t instanceof ParameterizedType p) {
            check(out, where, p.getRawType());
            for (Type arg : p.getActualTypeArguments()) {
                check(out, where, arg);
            }
            return;
        }
        if (!(t instanceof Class<?> c)) return;
        while (c.isArray()) c = c.getComponentType();
        if (c.isPrimitive()) return;

        String name = c.getName();
        boolean jdk = name.startsWith("java.") || name.startsWith("javax.");
        boolean ours = name.startsWith("org.cytoscape.massql.");
        boolean vendored = name.startsWith("org.cytoscape.massql.io.vendor.");

        if (vendored) {
            out.add(where + " -> " + name + " (vendored MSDK internals must not escape)");
        } else if (!jdk && !ours) {
            out.add(where + " -> " + name + " (neither a JDK type nor one of ours)");
        } else if (ours && !Modifier.isPublic(c.getModifiers())) {
            out.add(where + " -> " + name + " (ours, but not public -- callers cannot name it)");
        }
    }
}
