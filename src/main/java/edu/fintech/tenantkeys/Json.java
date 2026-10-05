package edu.fintech.tenantkeys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private Json() {}

    static String stringify(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return '"' + escape(text) + '"';
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>();
            map.forEach((key, item) -> entries.add(stringify(key.toString()) + ":" + stringify(item)));
            return "{" + String.join(",", entries) + "}";
        }
        if (value instanceof Iterable<?> items) {
            List<String> encoded = new ArrayList<>();
            items.forEach(item -> encoded.add(stringify(item)));
            return "[" + String.join(",", encoded) + "]";
        }
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass().getName());
    }

    static Object parse(String source) {
        Parser parser = new Parser(source);
        Object value = parser.value();
        parser.whitespace();
        if (!parser.done()) throw parser.failure("Trailing content");
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("Expected JSON object");
        return (Map<String, Object>) value;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static final class Parser {
        private final String source;
        private int index;

        private Parser(String source) { this.source = source; }
        private boolean done() { return index == source.length(); }
        private IllegalArgumentException failure(String message) {
            return new IllegalArgumentException(message + " at character " + index);
        }
        private void whitespace() {
            while (!done() && Character.isWhitespace(source.charAt(index))) index++;
        }
        private Object value() {
            whitespace();
            if (done()) throw failure("Expected value");
            return switch (source.charAt(index)) {
                case '{' -> objectValue();
                case '[' -> arrayValue();
                case '"' -> stringValue();
                case 't' -> literal("true", true);
                case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> numberValue();
            };
        }
        private Map<String, Object> objectValue() {
            index++;
            Map<String, Object> result = new LinkedHashMap<>();
            whitespace();
            if (take('}')) return result;
            do {
                whitespace();
                String key = stringValue();
                whitespace();
                expect(':');
                result.put(key, value());
                whitespace();
            } while (take(','));
            expect('}');
            return result;
        }
        private List<Object> arrayValue() {
            index++;
            List<Object> result = new ArrayList<>();
            whitespace();
            if (take(']')) return result;
            do {
                result.add(value());
                whitespace();
            } while (take(','));
            expect(']');
            return result;
        }
        private String stringValue() {
            expect('"');
            StringBuilder result = new StringBuilder();
            while (!done()) {
                char current = source.charAt(index++);
                if (current == '"') return result.toString();
                if (current != '\\') { result.append(current); continue; }
                if (done()) throw failure("Unfinished escape");
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (index + 4 > source.length()) throw failure("Unfinished unicode escape");
                        result.append((char) Integer.parseInt(source.substring(index, index + 4), 16));
                        index += 4;
                    }
                    default -> throw failure("Invalid escape");
                }
            }
            throw failure("Unterminated string");
        }
        private Object numberValue() {
            int start = index;
            while (!done() && "-+0123456789.eE".indexOf(source.charAt(index)) >= 0) index++;
            String token = source.substring(start, index);
            try {
                return token.contains(".") || token.contains("e") || token.contains("E")
                        ? Double.parseDouble(token) : Long.parseLong(token);
            } catch (NumberFormatException error) {
                throw failure("Invalid number");
            }
        }
        private Object literal(String token, Object value) {
            if (!source.startsWith(token, index)) throw failure("Invalid literal");
            index += token.length();
            return value;
        }
        private boolean take(char expected) {
            if (!done() && source.charAt(index) == expected) { index++; return true; }
            return false;
        }
        private void expect(char expected) {
            if (!take(expected)) throw failure("Expected " + expected);
        }
    }
}
