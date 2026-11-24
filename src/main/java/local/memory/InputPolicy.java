package local.memory;

import java.text.Normalizer;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.regex.Pattern;

final class InputPolicy {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern ROUTE = Pattern.compile("/(?:[A-Za-z0-9._~/-]|\\{[A-Za-z0-9_-]+})*");
    private static final Pattern REPORT = Pattern.compile("[A-Za-z0-9._:/-]{1,256}");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9+/_.=-]{8,}"
            + "|\\b(?:authorization|set-cookie|cookie|x-api-key|api[_ -]?key|"
            + "access[_ -]?token|refresh[_ -]?token|password|passwd|secret)[\"']?\\s*[:=]"
            + "|\\b(?:sk-[A-Za-z0-9_-]{12,}|AKIA[A-Z0-9]{16}|gh[pousr]_[A-Za-z0-9]{12,})"
            + "|-----BEGIN [A-Z ]*PRIVATE KEY-----"
            + "|\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");

    private InputPolicy() {}

    static String id(String value, boolean required) {
        return match(value, required, 128, ID.asMatchPredicate());
    }

    static String route(String value, boolean required) {
        return match(value, required, 256, s -> ROUTE.matcher(s).matches()
                && !s.contains("//") && !s.endsWith("/..") && !s.contains("/../")
                && !s.endsWith("/.") && !s.contains("/./"));
    }

    static String report(String value) {
        return match(value, false, 256, s -> REPORT.matcher(s).matches() && !s.contains("://"));
    }

    static String text(String value, boolean required, int maxLength) {
        return match(value, required, maxLength, s -> true);
    }

    private static String match(String value, boolean required, int maxLength, Predicate<String> pattern) {
        if (value == null && !required) {
            return null;
        }
        require(value != null && value.length() <= maxLength * 4);
        String normalized = Normalizer.normalize(Objects.requireNonNull(value), Normalizer.Form.NFC).strip();
        require(!normalized.isBlank() && normalized.codePointCount(0, normalized.length()) <= maxLength);
        if (SENSITIVE.matcher(normalized).find()) {
            throw new MemoryError(ErrorCode.SENSITIVE_INPUT);
        }
        require(normalized.codePoints().noneMatch(c -> Character.isISOControl(c)) && pattern.test(normalized));
        return normalized;
    }

    static void require(boolean condition) {
        if (!condition) {
            throw new MemoryError(ErrorCode.INVALID_INPUT);
        }
    }
}
