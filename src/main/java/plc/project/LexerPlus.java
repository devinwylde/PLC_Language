package plc.project;

import java.util.ArrayList;
import java.util.List;

/**
 * The lexer works through three main functions:
 *
 *  - {@link #lex()}, which repeatedly calls lexToken() and skips whitespace
 *  - {@link #lexToken()}, which lexes the next token
 *  - {@link CharStream}, which manages the state of the lexer and literals
 *
 * If the lexer fails to parse something (such as an unterminated string) you
 * should throw a {@link ParseException} with an index at the character which is
 * invalid.
 *
 * The {@link #peek(String...)} and {@link #match(String...)} functions are * helpers you need to use, they will make the implementation a lot easier. */
public final class LexerPlus {

    private final CharStream chars;

    public LexerPlus(String input) {
        chars = new CharStream(input);
    }

    /**
     * Repeatedly lexes the input using {@link #lexToken()}, also skipping over
     * whitespace where appropriate.
     */
    public List<Token> lex() {
        List<Token> tokens = new ArrayList<>();
        while (chars.has(0)) {
            if (!chars.checkWS()) tokens.add(lexToken());
        }

        return tokens;
    }

    /**
     * This method determines the type of the next token, delegating to the
     * appropriate lex method. As such, it is best for this method to not change
     * the state of the char stream (thus, use peek not match).
     *
     * The next character should start a valid token since whitespace is handled
     * by {@link #lex()}
     */



    public Token lexToken() {
        if (chars.checkAlpha() || chars.checkChar('@')) return lexIdentifier();
        if (chars.checkNum() || (chars.checkChar('-') && chars.checkNum(1))) return lexNumber();
        if (chars.checkChar('\'')) return lexCharacter();
        if (chars.checkChar('"')) return lexString();
        return lexOperator();
    }

    public Token lexIdentifier() {
        do {
            chars.advance();
        } while (chars.has(0) && (chars.checkAlnum() || chars.checkChar('-') || chars.checkChar('_')));

        return chars.emit(Token.Type.IDENTIFIER);
    }

    public Token lexNumber() {
        boolean decimal = false;
        do {
            chars.advance();
            if (chars.checkChar('.') && !decimal) {
                decimal = true;
                chars.advance();
            }
        } while (chars.has(0) && chars.checkNum());

        return chars.emit(Token.Type.INTEGER);
    }

    public Token lexCharacter() {
        chars.advance();
        chars.advance();
        if (!chars.has(0) || !chars.checkChar('\'')) throw new ParseException("Invalid character: Expected single quote: ['].", chars.index);
        chars.advance();

        return chars.emit(Token.Type.CHARACTER);
    }

    public Token lexString() {
        do {
            chars.advance();
            if (chars.has(0) && chars.checkChar('\\')) lexEscape();
        } while (chars.has(0) && !chars.checkChar('"'));

        if (!chars.has(0)) throw new ParseException("Invalid character: Expected double quote: [\"].", chars.index);

        return chars.emit(Token.Type.STRING);
    }

    public void lexEscape() {
        chars.advance();
        if (!chars.has(0)) throw new ParseException("Invalid character: Expected double quote: [\"].", chars.index);
        if (!match("[bnrt'\"\\\\]")) throw new ParseException("Invalid escape sequence with character" + chars.get(0) + ".", chars.index);
    }

    public Token lexOperator() {
        if (match("[\\+\\-\\*\\/;\\()\\.]")) return chars.emit(Token.Type.OPERATOR);
        if (match("[><!=]")) {
            match("=");
            return chars.emit(Token.Type.OPERATOR);
        }
        if (match("&")) {
            match("&");
            return chars.emit(Token.Type.OPERATOR);
        }
        if (match("|")) {
            match("|");
            return chars.emit(Token.Type.OPERATOR);
        }
        throw new ParseException("Invalid character " + chars.get(0), chars.index);
    }

    /**
     * Returns true if the next sequence of characters match the given patterns,
     * which should be a regex. For example, {@code peek("a", "b", "c")} would
     * return true if the next characters are {@code 'a', 'b', 'c'}.
     */

    public boolean peek(String pattern) {
        if (!chars.has(0)) return false;
        return String.valueOf(chars.get(0)).matches(pattern);
    }

    public boolean peek(String... patterns) {
        for (int i = 0; i < patterns.length; i++) {
            if (!chars.has(i) || !String.valueOf(chars.get(i)).matches(patterns[i])) {
                return false;
            }
        }

        return true;
    }

    /**
     * Returns true in the same way as {@link #peek(String...)}, but also
     * advances the character stream past all matched characters if peek returns
     * true. Hint - it's easiest to have this method simply call peek.
     */

    public boolean match(String pattern) {
        boolean peek = peek(pattern);
        if (peek) chars.advance();
        return peek;
    }

    public boolean match(String... patterns) {
        boolean peek = peek(patterns);
        if (peek) chars.advance(patterns.length);
        return peek;
    }

    /**
     * A helper class maintaining the input string, current index of the char
     * stream, and the current length of the token being matched.
     *
     * You should rely on peek/match for state management in nearly all cases.
     * The only field you need to access is {@link #index} for any {@link
     * ParseException} which is thrown.
     */
    public static final class CharStream {

        private final String input;
        private int index = 0;
        private int length = 0;

        public CharStream(String input) {
            this.input = input;
        }

        public boolean has(int offset) {
            return index + offset < input.length();
        }

        public char get(int offset) {
            return input.charAt(index + offset);
        }

        public void advance() {
            index++;
            length++;
        }
        public void advance(int amount) {
            index += amount;
            length += amount;
        }

        public void skip() {
            length = 0;
        }

        public Token emit(Token.Type type) {
            int start = index - length;
            skip();
            return new Token(type, input.substring(start, index), start);
        }

        public boolean checkNum(int offset) {
            return '0' <= get(offset) && get(offset) <= '9';
        }

        public boolean checkAlpha(int offset) {
            return ('a' <= get(offset) && get(offset) <= 'z') || ('A' <= get(offset) && get(offset) <= 'Z');
        }

        public boolean checkChar(char c, int offset) {
            return c == get(offset);
        }

        public boolean checkNum() {
            return '0' <= get(0) && get(0) <= '9';
        }

        public boolean checkAlpha() {
            return ('a' <= get(0) && get(0) <= 'z') || ('A' <= get(0) && get(0) <= 'Z');
        }

        public boolean checkAlnum() {
            return checkAlpha() || checkNum();
        }

        public boolean checkChar(char c) {
            return c == get(0);
        }

        public boolean checkWS() {
            return get(0) == ' ' || get(0) == '\t' || get(0) == '\n';
        }
    }

}

