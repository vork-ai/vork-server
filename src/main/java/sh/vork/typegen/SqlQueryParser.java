package sh.vork.typegen;

import sh.vork.database.SearchQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses a simplified SQL WHERE-clause string into a {@link SearchQuery}.
 *
 * <h3>Supported syntax</h3>
 * <ul>
 *   <li><b>Comparisons:</b> {@code =}, {@code !=}, {@code <>}, {@code >},
 *       {@code >=}, {@code <}, {@code <=}</li>
 *   <li><b>Pattern:</b> {@code LIKE 'pattern'} — SQL wildcards {@code %} and
 *       {@code _} are translated to the equivalent regex. Simple {@code %x%}
 *       patterns use {@link SearchQuery#like} (case-insensitive substring).</li>
 *   <li><b>Membership:</b> {@code IN ('a', 'b', ...)}</li>
 *   <li><b>Null check:</b> {@code IS NULL}, {@code IS NOT NULL}
 *       (maps to {@link SearchQuery#exists} false/true)</li>
 *   <li><b>Logical:</b> {@code AND}, {@code OR}, {@code NOT}</li>
 *   <li><b>Negations:</b> {@code NOT LIKE}, {@code NOT IN}</li>
 *   <li><b>Grouping:</b> {@code (...)}</li>
 *   <li><b>Booleans:</b> {@code true} / {@code false} as literal values</li>
 *   <li><b>Dot notation:</b> nested fields, e.g. {@code address.city = 'London'}</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * SearchQuery q = SqlQueryParser.parse("name = 'Alice' AND age > 18");
 * SearchQuery q2 = SqlQueryParser.parse(
 *     "(active = true OR score >= 8.0) AND status NOT IN ('banned', 'deleted')");
 * }</pre>
 *
 * <h3>Notes</h3>
 * <ul>
 *   <li>Keywords ({@code AND}, {@code OR}, {@code NOT}, {@code LIKE}, {@code IN},
 *       {@code IS}, {@code NULL}, {@code TRUE}, {@code FALSE}) are
 *       case-insensitive.</li>
 *   <li>String literals must be single-quoted. Escape a literal single quote by
 *       doubling it: {@code 'it''s fine'}.</li>
 *   <li>The {@code WHERE} keyword itself must <em>not</em> be included — pass
 *       only the predicate expression.</li>
 * </ul>
 */
public class SqlQueryParser {

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Parses {@code sql} (a WHERE-clause expression without the {@code WHERE}
     * keyword) and returns an equivalent {@link SearchQuery}.
     *
     * @throws SqlParseException if the input cannot be parsed
     */
    public static SearchQuery parse(String sql) {
        return new SqlQueryParser(sql.trim()).parseExpression();
    }

    // ── Token types ───────────────────────────────────────────────────────────

    private enum TokenType { WORD, NUMBER, STRING, LPAREN, RPAREN, COMMA, OP, EOF }

    private record Token(TokenType type, String value) {}

    // ── Instance state ────────────────────────────────────────────────────────

    private final String input;
    private int charPos;
    private final List<Token> tokens;
    private int tokenPos;

    private SqlQueryParser(String input) {
        this.input = input;
        this.charPos = 0;
        this.tokens = tokenize();
        this.tokenPos = 0;
    }

    // ── Tokenizer ─────────────────────────────────────────────────────────────

    private List<Token> tokenize() {
        List<Token> list = new ArrayList<>();
        while (charPos < input.length()) {
            char c = input.charAt(charPos);
            if (Character.isWhitespace(c)) { charPos++; continue; }
            switch (c) {
                case '(' -> { list.add(new Token(TokenType.LPAREN, "(")); charPos++; }
                case ')' -> { list.add(new Token(TokenType.RPAREN, ")")); charPos++; }
                case ',' -> { list.add(new Token(TokenType.COMMA,  ",")); charPos++; }
                case '\'' -> list.add(readString());
                case '!', '<', '>', '=' -> list.add(readOp());
                default -> {
                    if (Character.isDigit(c) || (c == '-' && charPos + 1 < input.length()
                            && Character.isDigit(input.charAt(charPos + 1)))) {
                        list.add(readNumber());
                    } else if (Character.isLetter(c) || c == '_') {
                        list.add(readWord());
                    } else {
                        throw new SqlParseException(
                                "Unexpected character '" + c + "' at position " + charPos);
                    }
                }
            }
        }
        list.add(new Token(TokenType.EOF, ""));
        return list;
    }

    private Token readString() {
        charPos++; // skip opening '
        StringBuilder sb = new StringBuilder();
        while (charPos < input.length()) {
            char c = input.charAt(charPos);
            if (c == '\'') {
                if (charPos + 1 < input.length() && input.charAt(charPos + 1) == '\'') {
                    sb.append('\''); // escaped ''
                    charPos += 2;
                } else {
                    charPos++; // closing '
                    break;
                }
            } else {
                sb.append(c);
                charPos++;
            }
        }
        return new Token(TokenType.STRING, sb.toString());
    }

    private Token readNumber() {
        StringBuilder sb = new StringBuilder();
        if (input.charAt(charPos) == '-') sb.append(input.charAt(charPos++));
        while (charPos < input.length()
                && (Character.isDigit(input.charAt(charPos)) || input.charAt(charPos) == '.')) {
            sb.append(input.charAt(charPos++));
        }
        return new Token(TokenType.NUMBER, sb.toString());
    }

    /**
     * Reads a word token. Dot notation (e.g. {@code address.city}) is consumed
     * as a single token so it can be used directly as a field name.
     */
    private Token readWord() {
        StringBuilder sb = new StringBuilder();
        while (charPos < input.length()) {
            char c = input.charAt(charPos);
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
                charPos++;
            } else if (c == '.' && charPos + 1 < input.length()
                    && (Character.isLetter(input.charAt(charPos + 1))
                        || input.charAt(charPos + 1) == '_')) {
                // Dot followed by an identifier char → part of a nested field name
                sb.append(c);
                charPos++;
            } else {
                break;
            }
        }
        return new Token(TokenType.WORD, sb.toString());
    }

    private Token readOp() {
        char c = input.charAt(charPos++);
        return switch (c) {
            case '=' -> new Token(TokenType.OP, "=");
            case '>' -> {
                if (charPos < input.length() && input.charAt(charPos) == '=') {
                    charPos++; yield new Token(TokenType.OP, ">=");
                }
                yield new Token(TokenType.OP, ">");
            }
            case '<' -> {
                if (charPos < input.length() && input.charAt(charPos) == '=') {
                    charPos++; yield new Token(TokenType.OP, "<=");
                }
                if (charPos < input.length() && input.charAt(charPos) == '>') {
                    charPos++; yield new Token(TokenType.OP, "!=");
                }
                yield new Token(TokenType.OP, "<");
            }
            case '!' -> {
                if (charPos < input.length() && input.charAt(charPos) == '=') {
                    charPos++; yield new Token(TokenType.OP, "!=");
                }
                throw new SqlParseException("Expected '=' after '!'");
            }
            default -> throw new SqlParseException("Unexpected operator character: " + c);
        };
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    private Token peek()    { return tokens.get(tokenPos); }
    private Token consume() { return tokens.get(tokenPos++); }

    private boolean peekWord(String keyword) {
        Token t = peek();
        return t.type() == TokenType.WORD && t.value().equalsIgnoreCase(keyword);
    }

    private boolean consumeIfWord(String keyword) {
        if (peekWord(keyword)) { consume(); return true; }
        return false;
    }

    private SearchQuery parseExpression() {
        SearchQuery result = parseOr();
        if (peek().type() != TokenType.EOF) {
            throw new SqlParseException("Unexpected token at end: '" + peek().value() + "'");
        }
        return result;
    }

    private SearchQuery parseOr() {
        SearchQuery left = parseAnd();
        while (consumeIfWord("OR")) {
            left = SearchQuery.or(left, parseAnd());
        }
        return left;
    }

    private SearchQuery parseAnd() {
        SearchQuery left = parseNot();
        while (consumeIfWord("AND")) {
            left = SearchQuery.and(left, parseNot());
        }
        return left;
    }

    private SearchQuery parseNot() {
        if (consumeIfWord("NOT")) {
            return SearchQuery.not(parseNot());
        }
        return parsePrimary();
    }

    private SearchQuery parsePrimary() {
        if (peek().type() == TokenType.LPAREN) {
            consume(); // (
            SearchQuery inner = parseOr(); // re-enter at OR level (not parseExpression to avoid EOF check)
            if (peek().type() != TokenType.RPAREN) {
                throw new SqlParseException("Expected ')' but found: '" + peek().value() + "'");
            }
            consume(); // )
            return inner;
        }
        return parseComparison();
    }

    private SearchQuery parseComparison() {
        Token fieldToken = consume();
        if (fieldToken.type() != TokenType.WORD) {
            throw new SqlParseException(
                    "Expected a field name but found: '" + fieldToken.value() + "'");
        }
        String field = fieldToken.value();

        // IS NULL / IS NOT NULL
        if (consumeIfWord("IS")) {
            boolean notNull = consumeIfWord("NOT");
            if (!consumeIfWord("NULL")) {
                throw new SqlParseException("Expected NULL after IS [NOT]");
            }
            // IS NULL → field must not exist; IS NOT NULL → field must exist
            return SearchQuery.exists(field, notNull);
        }

        // field NOT IN (...) / field NOT LIKE '...'
        if (peekWord("NOT")) {
            consume(); // NOT
            if (consumeIfWord("IN"))   return SearchQuery.not(parseInList(field));
            if (consumeIfWord("LIKE")) return SearchQuery.not(parseLikeQuery(field));
            throw new SqlParseException(
                    "Expected IN or LIKE after NOT, found: '" + peek().value() + "'");
        }

        // field IN (...)
        if (consumeIfWord("IN"))   return parseInList(field);

        // field LIKE '...'
        if (consumeIfWord("LIKE")) return parseLikeQuery(field);

        // Comparison operators
        Token opToken = consume();
        if (opToken.type() != TokenType.OP) {
            throw new SqlParseException(
                    "Expected a comparison operator (=, !=, <, <=, >, >=) but found: '"
                    + opToken.value() + "'");
        }
        Object value = parseValue();
        return switch (opToken.value()) {
            case "="  -> SearchQuery.eq(field, value);
            case "!=" -> SearchQuery.ne(field, value);
            case ">"  -> SearchQuery.gt(field, value);
            case ">=" -> SearchQuery.gte(field, value);
            case "<"  -> SearchQuery.lt(field, value);
            case "<=" -> SearchQuery.lte(field, value);
            default   -> throw new SqlParseException("Unknown operator: " + opToken.value());
        };
    }

    private SearchQuery parseInList(String field) {
        if (peek().type() != TokenType.LPAREN) {
            throw new SqlParseException("Expected '(' after IN");
        }
        consume(); // (
        List<Object> values = new ArrayList<>();
        values.add(parseValue());
        while (peek().type() == TokenType.COMMA) {
            consume(); // ,
            values.add(parseValue());
        }
        if (peek().type() != TokenType.RPAREN) {
            throw new SqlParseException("Expected ')' to close IN list, found: '"
                    + peek().value() + "'");
        }
        consume(); // )
        return SearchQuery.in(field, values);
    }

    /**
     * Reads the string literal after LIKE and converts the SQL wildcard pattern
     * to a {@link SearchQuery}.
     *
     * <ul>
     *   <li>Simple {@code %substring%} → {@link SearchQuery#like} (case-insensitive
     *       substring using {@code $regex} with the {@code i} option).</li>
     *   <li>All other patterns are converted to a case-insensitive regex:
     *       {@code %} → {@code .*}, {@code _} → {@code .},
     *       other characters are quoted with {@link Pattern#quote}.</li>
     * </ul>
     */
    private SearchQuery parseLikeQuery(String field) {
        Token valueToken = consume();
        if (valueToken.type() != TokenType.STRING) {
            throw new SqlParseException(
                    "Expected a string literal after LIKE, found: '" + valueToken.value() + "'");
        }
        return likePatternToQuery(field, valueToken.value());
    }

    static SearchQuery likePatternToQuery(String field, String sqlPattern) {
        // Optimise the common %substring% case to use the dedicated like() predicate.
        // Guard: length >= 2 so that a bare "%" doesn't produce substring(1, 0).
        if (sqlPattern.startsWith("%") && sqlPattern.endsWith("%")) {
            String inner = sqlPattern.length() >= 2
                    ? sqlPattern.substring(1, sqlPattern.length() - 1)
                    : "";
            if (!inner.contains("%") && !inner.contains("_")) {
                return SearchQuery.like(field, inner);
            }
        }

        // General case: translate the SQL LIKE pattern to a regex
        boolean anchorStart = !sqlPattern.startsWith("%");
        boolean anchorEnd   = !sqlPattern.endsWith("%");
        StringBuilder regex = new StringBuilder("(?i)");
        if (anchorStart) regex.append("^");
        for (char c : sqlPattern.toCharArray()) {
            if (c == '%')      regex.append(".*");
            else if (c == '_') regex.append('.');
            else               regex.append(Pattern.quote(String.valueOf(c)));
        }
        if (anchorEnd) regex.append("$");
        return SearchQuery.regex(field, regex.toString());
    }

    private Object parseValue() {
        Token t = peek();
        if (t.type() == TokenType.STRING) {
            consume();
            return t.value();
        }
        if (t.type() == TokenType.NUMBER) {
            consume();
            String v = t.value();
            if (v.contains(".")) return Double.parseDouble(v);
            try   { return Integer.parseInt(v); }
            catch (NumberFormatException e) { return Long.parseLong(v); }
        }
        if (t.type() == TokenType.WORD) {
            String upper = t.value().toUpperCase();
            if (upper.equals("TRUE"))  { consume(); return Boolean.TRUE; }
            if (upper.equals("FALSE")) { consume(); return Boolean.FALSE; }
            if (upper.equals("NULL"))  { consume(); return null; }
        }
        throw new SqlParseException(
                "Expected a value (string literal, number, true, false, null) but found: '"
                + t.value() + "'");
    }
}
