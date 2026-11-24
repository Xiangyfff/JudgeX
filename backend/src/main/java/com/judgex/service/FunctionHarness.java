package com.judgex.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Function-style judging: the submission only contains the solution class, and this class
 * turns it into a complete program — one line of arguments on stdin, the method call, the
 * return value on stdout.
 *
 * <p>Only a small, boring set of types is supported on purpose. The judge container has no
 * JSON library, and pulling one in for four scalar types would cost more than it buys.
 */
final class FunctionHarness {

    private static final Logger log = LoggerFactory.getLogger(FunctionHarness.class);

    /** `int[] twoSum(int[] nums, int target)` */
    private static final Pattern SIGNATURE = Pattern.compile(
            "^\\s*([A-Za-z_][\\w\\[\\]]*)\\s+([A-Za-z_]\\w*)\\s*\\((.*)\\)\\s*$");

    private static final Map<String, String> JAVA_CONVERT = Map.of(
            "int", "toInt",
            "long", "toLong",
            "String", "toStr",
            "boolean", "toBool",
            "int[]", "toInts",
            "long[]", "toLongs",
            "String[]", "toStrs",
            "int[][]", "toMatrix");

    private static final List<String> JAVA_TYPES = List.of(
            "int", "long", "String", "boolean", "int[]", "long[]", "String[]", "int[][]");

    private FunctionHarness() {
    }

    /**
     * @return a complete program, or null when the language or the signature is unsupported
     * (the caller turns that into a SYSTEM_ERROR instead of a misleading compile error).
     */
    static String wrap(String language, String signature, String source) {
        Signature spec = Signature.parse(signature);
        if (spec == null) {
            log.warn("unsupported function signature: {}", signature);
            return null;
        }
        String template = template(language);
        if (template == null) {
            return null;
        }
        return switch (language) {
            case "java" -> java(template, spec, source);
            case "python" -> python(template, spec, source);
            default -> null;
        };
    }

    private static String java(String template, Signature spec, String source) {
        StringBuilder call = new StringBuilder();
        if ("void".equals(spec.returnType())) {
            call.append("sol.").append(spec.name()).append('(');
        } else {
            call.append(spec.returnType()).append(" result = sol.").append(spec.name()).append('(');
        }
        for (int i = 0; i < spec.params().size(); i++) {
            Param p = spec.params().get(i);
            String convert = JAVA_CONVERT.get(p.type());
            if (convert == null) {
                log.warn("unsupported parameter type: {}", p.type());
                return null;
            }
            if (i > 0) {
                call.append(", ");
            }
            call.append(convert).append("(argv.get(").append(i).append("))");
        }
        call.append(");");
        String print = "void".equals(spec.returnType())
                ? ""
                : "System.out.print(fmt(result));";
        return template
                .replace("//__SOLUTION__", source.strip())
                .replace("//__CALL__", call.toString())
                .replace("//__PRINT__", print);
    }

    private static String python(String template, Signature spec, String source) {
        return template
                .replace("#__SOLUTION__", source.strip())
                .replace("//__METHOD__", spec.name());
    }

    private static String template(String language) {
        String resource = switch (language) {
            case "java" -> "/harness/Main.java";
            case "python" -> "/harness/main.py";
            default -> null;
        };
        if (resource == null) {
            return null;
        }
        try (InputStream in = FunctionHarness.class.getResourceAsStream(resource)) {
            if (in == null) {
                log.error("harness resource {} is missing from the jar", resource);
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("could not read harness resource {}: {}", resource, e.toString());
            return null;
        }
    }

    record Param(String type, String name) {
    }

    record Signature(String returnType, String name, List<Param> params) {

        static Signature parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            Matcher m = SIGNATURE.matcher(raw.trim());
            if (!m.matches()) {
                return null;
            }
            String returnType = m.group(1);
            if (!"void".equals(returnType) && !JAVA_TYPES.contains(returnType)) {
                return null;
            }
            List<Param> params = new ArrayList<>();
            String inner = m.group(3).trim();
            if (!inner.isEmpty()) {
                for (String part : inner.split(",")) {
                    String[] bits = part.trim().split("\\s+");
                    if (bits.length != 2 || !JAVA_TYPES.contains(bits[0])) {
                        return null;
                    }
                    params.add(new Param(bits[0], bits[1]));
                }
            }
            return new Signature(returnType, m.group(2), params);
        }
    }
}
