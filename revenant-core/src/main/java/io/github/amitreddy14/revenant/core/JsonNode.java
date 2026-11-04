package io.github.amitreddy14.revenant.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed JSON value that also remembers the exact text it came from.
 *
 * <p>The engine treats workflow inputs, activity results and errors as opaque JSON (ADR-0009), so
 * the wire format needs to pull a value out of a message and pass it on unchanged. {@link #raw()}
 * gives that text back, byte for byte, without re-serialising it. This is a small strict parser
 * (RFC 8259) with no dependencies, so the worker SDK and the core stay free of a JSON library.
 */
public final class JsonNode {

    private enum Type { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

    private static final int MAX_DEPTH = 64;

    private final Type type;
    private final String raw;
    private final String text;
    private final Map<String, JsonNode> fields;
    private final List<JsonNode> items;

    private JsonNode(Type type, String raw, String text, Map<String, JsonNode> fields, List<JsonNode> items) {
        this.type = type;
        this.raw = raw;
        this.text = text;
        this.fields = fields;
        this.items = items;
    }

    /**
     * Parses a complete JSON document.
     *
     * @throws IllegalArgumentException if the text is not valid JSON or nests deeper than 64 levels
     */
    public static JsonNode parse(String json) {
        if (json == null) {
            throw new IllegalArgumentException("invalid JSON: null");
        }
        Parser parser = new Parser(json);
        JsonNode value = parser.value(0);
        parser.skipSpace();
        if (parser.pos != json.length()) {
            throw parser.error("unexpected content after the value");
        }
        return value;
    }

    /** The exact text of this value as it appeared in the input (no surrounding whitespace). */
    public String raw() {
        return raw;
    }

    public boolean isNull() {
        return type == Type.NULL;
    }

    public boolean isObject() {
        return type == Type.OBJECT;
    }

    /** The member of an object, or null if it is absent. A member that is JSON null is returned as a null node. */
    public JsonNode field(String name) {
        require(Type.OBJECT);
        return fields.get(name);
    }

    /** The member of an object, which must be present. */
    public JsonNode required(String name) {
        JsonNode node = field(name);
        if (node == null) {
            throw new IllegalArgumentException("missing field \"" + name + "\"");
        }
        return node;
    }

    public List<JsonNode> items() {
        require(Type.ARRAY);
        return items;
    }

    /** The decoded value of a JSON string. */
    public String asText() {
        require(Type.STRING);
        return text;
    }

    public long asLong() {
        require(Type.NUMBER);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a whole number: " + raw);
        }
    }

    public double asDouble() {
        require(Type.NUMBER);
        return Double.parseDouble(raw);
    }

    public boolean asBoolean() {
        require(Type.BOOLEAN);
        return Boolean.parseBoolean(raw);
    }

    /** Text of a member that may be absent or null; returns null in both cases. */
    public String optText(String name) {
        JsonNode node = field(name);
        return node == null || node.isNull() ? null : node.asText();
    }

    /** Whole number member that may be absent or null; returns null in both cases. */
    public Long optLong(String name) {
        JsonNode node = field(name);
        return node == null || node.isNull() ? null : node.asLong();
    }

    private void require(Type expected) {
        if (type != expected) {
            throw new IllegalArgumentException("expected a JSON " + expected.name().toLowerCase() + " but found "
                    + type.name().toLowerCase());
        }
    }

    @Override
    public String toString() {
        return raw;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String message) {
            return new IllegalArgumentException("invalid JSON at position " + pos + ": " + message);
        }

        void skipSpace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    return;
                }
            }
        }

        JsonNode value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nested too deeply");
            }
            skipSpace();
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            int start = pos;
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object(start, depth);
                case '[' -> array(start, depth);
                case '"' -> {
                    String decoded = string();
                    yield new JsonNode(Type.STRING, s.substring(start, pos), decoded, null, null);
                }
                case 't' -> literal("true", Type.BOOLEAN, start);
                case 'f' -> literal("false", Type.BOOLEAN, start);
                case 'n' -> literal("null", Type.NULL, start);
                default -> number(start);
            };
        }

        JsonNode literal(String word, Type type, int start) {
            if (!s.startsWith(word, pos)) {
                throw error("unexpected character");
            }
            pos += word.length();
            return new JsonNode(type, word, null, null, null);
        }

        JsonNode number(int start) {
            int p = pos;
            if (p < s.length() && s.charAt(p) == '-') {
                p++;
            }
            int digitsStart = p;
            while (p < s.length() && Character.isDigit(s.charAt(p)) && s.charAt(p) < 128) {
                p++;
            }
            if (p == digitsStart) {
                throw error("unexpected character");
            }
            if (s.charAt(digitsStart) == '0' && p - digitsStart > 1) {
                throw error("leading zero");
            }
            if (p < s.length() && s.charAt(p) == '.') {
                p++;
                int fraction = p;
                while (p < s.length() && s.charAt(p) >= '0' && s.charAt(p) <= '9') {
                    p++;
                }
                if (p == fraction) {
                    throw error("digits expected after the decimal point");
                }
            }
            if (p < s.length() && (s.charAt(p) == 'e' || s.charAt(p) == 'E')) {
                p++;
                if (p < s.length() && (s.charAt(p) == '+' || s.charAt(p) == '-')) {
                    p++;
                }
                int exponent = p;
                while (p < s.length() && s.charAt(p) >= '0' && s.charAt(p) <= '9') {
                    p++;
                }
                if (p == exponent) {
                    throw error("digits expected in the exponent");
                }
            }
            pos = p;
            return new JsonNode(Type.NUMBER, s.substring(start, p), null, null, null);
        }

        String string() {
            pos++; // opening quote
            StringBuilder out = new StringBuilder();
            while (true) {
                if (pos >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c < 0x20) {
                    throw error("control character in string");
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (pos >= s.length()) {
                    throw error("unterminated escape");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > s.length()) {
                            throw error("short unicode escape");
                        }
                        try {
                            out.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad unicode escape");
                        }
                        pos += 4;
                    }
                    default -> throw error("bad escape");
                }
            }
        }

        JsonNode array(int start, int depth) {
            pos++;
            List<JsonNode> items = new ArrayList<>();
            skipSpace();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return new JsonNode(Type.ARRAY, s.substring(start, pos), null, null, items);
            }
            while (true) {
                items.add(value(depth + 1));
                skipSpace();
                if (pos >= s.length()) {
                    throw error("unterminated array");
                }
                char c = s.charAt(pos++);
                if (c == ']') {
                    return new JsonNode(Type.ARRAY, s.substring(start, pos), null, null, items);
                }
                if (c != ',') {
                    pos--;
                    throw error("expected ',' or ']'");
                }
            }
        }

        JsonNode object(int start, int depth) {
            pos++;
            Map<String, JsonNode> fields = new LinkedHashMap<>();
            skipSpace();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return new JsonNode(Type.OBJECT, s.substring(start, pos), null, fields, null);
            }
            while (true) {
                skipSpace();
                if (pos >= s.length() || s.charAt(pos) != '"') {
                    throw error("expected a field name");
                }
                String name = string();
                skipSpace();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw error("expected ':'");
                }
                pos++;
                fields.put(name, value(depth + 1));
                skipSpace();
                if (pos >= s.length()) {
                    throw error("unterminated object");
                }
                char c = s.charAt(pos++);
                if (c == '}') {
                    return new JsonNode(Type.OBJECT, s.substring(start, pos), null, fields, null);
                }
                if (c != ',') {
                    pos--;
                    throw error("expected ',' or '}'");
                }
            }
        }
    }
}
