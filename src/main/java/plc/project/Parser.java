package plc.project;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;

/**
 * The parser takes the sequence of tokens emitted by the lexer and turns that
 * into a structured representation of the program, called the Abstract Syntax
 * Tree (AST).
 *
 * The parser has a similar architecture to the lexer, just with {@link Token}s
 * instead of characters. As before, {@link #peek(Object...)} and {@link
 * #match(Object...)} are helpers to make the implementation easier.
 *
 * This type of parser is called <em>recursive descent</em>. Each rule in our
 * grammar will have it's own function, and reference to other rules correspond
 * to calling that functions.
 */
public final class Parser {

    private final TokenStream tokens;

    public Parser(List<Token> tokens) {
        this.tokens = new TokenStream(tokens);
    }

    /**
     * Parses the {@code source} rule.
     */
    public Ast.Source parseSource() throws ParseException {
        List<Ast.Global> globals = new ArrayList<>();
        List<Ast.Function> functions = new ArrayList<>();

        while (tokens.has(0)) {
            if (peek("FUN")) functions.add(parseFunction());
            else globals.add(parseGlobal());
        }

        return new Ast.Source(globals, functions);
    }

    /**
     * Parses the {@code global} rule. This method should only be called if the
     * next tokens start a global, aka {@code LIST|VAL|VAR}.
     */
    public Ast.Global parseGlobal() throws ParseException {
        Ast.Global result;
        if (peek("LIST")) result = parseList();
        else if (peek("VAR")) result = parseMutable();
        else if (peek("VAL")) result = parseImmutable();
        else throw new ParseException("Invalid token at global scope", tocLoc());

        if (!match(";")) throw new ParseException("Missing semicolon", tocLoc());
        return result;
    }

    /**
     * Parses the {@code list} rule. This method should only be called if the
     * next token declares a list, aka {@code LIST}.
     */
    public Ast.Global parseList() throws ParseException {
        tokens.advance();
        if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing identifier", tocLoc());
        Token idn = tokens.get(0);
        tokens.advance();
        if (!match(":")) throw new ParseException("Missing colon.", tocLoc());
        if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing type", tocLoc());
        Token type = tokens.get(0);
        tokens.advance();
        if (!match("=", "[")) throw new ParseException("Missing opening bracket", tocLoc());
        if (match("]")) return new Ast.Global(idn.getLiteral(), type.getLiteral(), true, Optional.of(new Ast.Expression.PlcList(new ArrayList<>())));
        List<Ast.Expression> values = new ArrayList<>();
        do {
            values.add(parseExpression());
        } while (match(","));
        if (!match("]")) throw new ParseException("Missing closing bracket", tocLoc());

        Ast.Expression list = new Ast.Expression.PlcList(values);
        return new Ast.Global(idn.getLiteral(), type.getLiteral(), true, Optional.of(list));
    }

    /**
     * Parses the {@code mutable} rule. This method should only be called if the
     * next token declares a mutable global variable, aka {@code VAR}.
     */
    public Ast.Global parseMutable() throws ParseException {
        tokens.advance();
        if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing identifier", tocLoc());
        Token idn = tokens.get(0);
        tokens.advance();
        if (!match(":")) throw new ParseException("Missing colon.", tocLoc());
        if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing type", tocLoc());
        Token type = tokens.get(0);
        tokens.advance();
        if (match("=")) return new Ast.Global(idn.getLiteral(), type.getLiteral(), true, Optional.ofNullable(parseExpression()));
        return new Ast.Global(idn.getLiteral(), type.getLiteral(), true, Optional.empty());
    }

    /**
     * Parses the {@code immutable} rule. This method should only be called if the
     * next token declares an immutable global variable, aka {@code VAL}.
     */
    public Ast.Global parseImmutable() throws ParseException {
        tokens.advance();
        if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing identifier", tocLoc());
        Token idn = tokens.get(0);
        tokens.advance();
        if (!match(":")) throw new ParseException("Missing colon.", tocLoc());
        if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing type", tocLoc());
        Token type = tokens.get(0);
        tokens.advance();
        if (!match("=")) throw new ParseException("Missing equal symbol", tocLoc());
        return new Ast.Global(idn.getLiteral(), type.getLiteral(), false, Optional.ofNullable(parseExpression()));
    }

    /**
     * Parses the {@code function} rule. This method should only be called if the
     * next tokens start a method, aka {@code FUN}.
     */
    public Ast.Function parseFunction() throws ParseException {
        tokens.advance();
        if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing identifier", tocLoc());
        Token idn = tokens.get(0);
        tokens.advance();
        if (!match("(")) throw new ParseException("Missing opening parenthesis", tocLoc());
        List<String> parameters = new ArrayList<>();
        List<String> pTypes = new ArrayList<>();
        if (!match(")")) {
            do {
                if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing identifier", tocLoc());
                parameters.add(tokens.get(0).getLiteral());
                tokens.advance();
                if (!match(":")) throw new ParseException("Missing colon.", tocLoc());
                if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing type", tocLoc());
                pTypes.add(tokens.get(0).getLiteral());
                tokens.advance();
            } while (match(","));
            if (!match(")")) throw new ParseException("Missing closing parenthesis", tocLoc());
        }
        Optional<String> rType = Optional.empty();
        if (match(":")) {
            if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing type", tocLoc());
            rType = Optional.of(tokens.get(0).getLiteral());
            tokens.advance();
        }
        if (!match("DO")) throw new ParseException("Missing 'DO'", tocLoc());
        List<Ast.Statement> statements = parseBlock();
        if (!match("END")) throw new ParseException("Missing END", tocLoc());
        return new Ast.Function(idn.getLiteral(), parameters, pTypes, rType, statements);
    }

    /**
     * Parses the {@code block} rule. This method should only be called if the
     * preceding token indicates the opening a block of statements.
     */
    public List<Ast.Statement> parseBlock() throws ParseException {
        List<Ast.Statement> statements = new ArrayList<>();

        while (tokens.has(0) && !peek("END") && !peek("ELSE") && !peek("CASE") && !peek("DEFAULT")) {
            statements.add(parseStatement());
        }

        return statements;
    }

    /**
     * Parses the {@code statement} rule and delegates to the necessary method.
     * If the next tokens do not start a declaration, if, while, or return
     * statement, then it is an expression/assignment statement.
     */
    public Ast.Statement parseStatement() throws ParseException {
        switch (tokens.get(0).getLiteral()) {
            case "LET": return parseDeclarationStatement();
            case "SWITCH": return parseSwitchStatement();
            case "IF": return parseIfStatement();
            case "WHILE": return parseWhileStatement();
            case "RETURN": return parseReturnStatement();
            default:
                Ast.Expression exp = parseExpression();
                if (match("=")) {
                    Ast.Statement.Assignment a = new Ast.Statement.Assignment(exp, parseExpression());
                    if (!match(";")) throw new ParseException("Missing semicolon", tocLoc());
                    return a;
                }

                if (!match(";")) throw new ParseException("Missing semicolon", tocLoc());
                return new Ast.Statement.Expression(exp);
        }
    }

    /**
     * Parses a declaration statement from the {@code statement} rule. This
     * method should only be called if the next tokens start a declaration
     * statement, aka {@code LET}.
     */
    public Ast.Statement.Declaration parseDeclarationStatement() throws ParseException {
        tokens.advance();
        if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing identifier", tocLoc());
        Token idn = tokens.get(0);
        tokens.advance();
        Optional<String> type = Optional.empty();
        if (match(":")) {
            if (!peek(Token.Type.IDENTIFIER)) throw new ParseException("Missing type", tocLoc());
            type = Optional.of(tokens.get(0).getLiteral());
            tokens.advance();
        }
        if (match("=")) {
            Ast.Statement.Declaration d = new Ast.Statement.Declaration(idn.getLiteral(), Optional.ofNullable(parseExpression()));
            if (!match(";")) throw new ParseException("Missing semicolon", tocLoc());
            return d;
        }
        if (!match(";")) throw new ParseException("Missing semicolon", tocLoc());
        return new Ast.Statement.Declaration(idn.getLiteral(), type, Optional.empty());
    }

    /**
     * Parses an if statement from the {@code statement} rule. This method
     * should only be called if the next tokens start an if statement, aka
     * {@code IF}.
     */
    public Ast.Statement.If parseIfStatement() throws ParseException {
        tokens.advance();
        Ast.Expression exp = parseExpression();
        if (!match("DO")) throw new ParseException("Missing 'DO'", tocLoc());
        List<Ast.Statement> ifBlock = parseBlock();
        if (match("ELSE")) {
            List<Ast.Statement> elseBlock = parseBlock();
            if (!match("END")) throw new ParseException("Missing 'END'", tocLoc());
            return new Ast.Statement.If(exp, ifBlock, elseBlock);
        }

        if (!match("END")) throw new ParseException("Missing 'END'", tocLoc());
        return new Ast.Statement.If(exp, ifBlock, new ArrayList<>());
    }

    /**
     * Parses a switch statement from the {@code statement} rule. This method
     * should only be called if the next tokens start a switch statement, aka
     * {@code SWITCH}.
     */
    public Ast.Statement.Switch parseSwitchStatement() throws ParseException {
        tokens.advance();
        Ast.Expression exp = parseExpression();
        List<Ast.Statement.Case> cases = new ArrayList<>();
        while (peek("CASE")) {
            cases.add(parseCaseStatement());
        }

        if (!peek("DEFAULT")) throw new ParseException("Missing default case", tocLoc());
        cases.add(parseCaseStatement());

        if (!match("END")) throw new ParseException("Missing 'END'", tocLoc());
        return new Ast.Statement.Switch(exp, cases);
    }

    /**
     * Parses a case or default statement block from the {@code switch} rule.
     * This method should only be called if the next tokens start the case or
     * default block of a switch statement, aka {@code CASE} or {@code DEFAULT}.
     */
    public Ast.Statement.Case parseCaseStatement() throws ParseException {
        if (match("CASE")) {
            Ast.Expression exp = parseExpression();
            if (!match(":")) throw new ParseException("Missing colon", tocLoc());
            List<Ast.Statement> block = parseBlock();
            return new Ast.Statement.Case(Optional.ofNullable(exp), block);
        } else {
            tokens.advance();
            List<Ast.Statement> block = parseBlock();
            return new Ast.Statement.Case(Optional.empty(), block);
        }
    }

    /**
     * Parses a while statement from the {@code statement} rule. This method
     * should only be called if the next tokens start a while statement, aka
     * {@code WHILE}.
     */
    public Ast.Statement.While parseWhileStatement() throws ParseException {
        tokens.advance();
        Ast.Expression exp = parseExpression();
        if (!match("DO")) throw new ParseException("Missing 'DO'", tocLoc());
        List<Ast.Statement> block = parseBlock();

        if (!match("END")) throw new ParseException("Missing 'END'", tocLoc());
        return new Ast.Statement.While(exp, block);
    }

    /**
     * Parses a return statement from the {@code statement} rule. This method
     * should only be called if the next tokens start a return statement, aka
     * {@code RETURN}.
     */
    public Ast.Statement.Return parseReturnStatement() throws ParseException {
        tokens.advance();
        Ast.Expression exp = parseExpression();
        if (!match(";"))  throw new ParseException("Missing semicolon", tocLoc());
        return new Ast.Statement.Return(exp);
    }

    /**
     * Parses the {@code expression} rule.
     */
    public Ast.Expression parseExpression() throws ParseException {
        return parseLogicalExpression();
    }

    /**
     * Parses the {@code logical-expression} rule.
     */
    public Ast.Expression parseLogicalExpression() throws ParseException {
        Ast.Expression left = parseComparisonExpression();
        while(peek("&&") || peek("||")) {
            String operator = tokens.get(0).getLiteral();
            tokens.advance();
            Ast.Expression right = parseComparisonExpression();
            left = new Ast.Expression.Binary(operator, left, right);
        }
        return left;
    }

    /**
     * Parses the {@code comparison-expression} rule.
     */
    public Ast.Expression parseComparisonExpression() throws ParseException {
        Ast.Expression left = parseAdditiveExpression();
        Ast.Expression right;
        while (peek("<") || peek(">") || peek("==") || peek("!=")) {
            String operator = tokens.get(0).getLiteral();
            tokens.advance();
            right = parseAdditiveExpression();
            left = new Ast.Expression.Binary(operator, left, right);
        }

        return left;
    }

    /**
     * Parses the {@code additive-expression} rule.
     */
    public Ast.Expression parseAdditiveExpression() throws ParseException {
        Ast.Expression left = parseMultiplicativeExpression();
        while (peek("+") || peek("-")) {
            String operator = tokens.get(0).getLiteral();
            tokens.advance();
            Ast.Expression right = parseMultiplicativeExpression();
            left = new Ast.Expression.Binary(operator, left, right);
        }

        return left;
    }

    /**
     * Parses the {@code multiplicative-expression} rule.
     */
    public Ast.Expression parseMultiplicativeExpression() throws ParseException {
        Ast.Expression left = parsePrimaryExpression();
        while (peek("*") || peek("/") || peek("^")) {
            String operator = tokens.get(0).getLiteral();
            tokens.advance();
            Ast.Expression right = parsePrimaryExpression();
            left = new Ast.Expression.Binary(operator, left, right);
        }

        return left;
    }

    public String strip(String s) {
        return s
                .substring(1, s.length()-1)
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\b", "\b")
                .replace("\\r", "\r")
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    /**
     * Parses the {@code primary-expression} rule. This is the top-level rule
     * for expressions and includes literal values, grouping, variables, and
     * functions. It may be helpful to break these up into other methods but is
     * not strictly necessary.
     */
    public Ast.Expression parsePrimaryExpression() throws ParseException {
        if (match("NIL")) {
            return new Ast.Expression.Literal(null);
        }
        else if (match("TRUE")) {
            return new Ast.Expression.Literal(true);
        }
        else if (match("FALSE")) {
            return new Ast.Expression.Literal(false);
        }
        else if (peek(Token.Type.INTEGER)) {
            BigInteger ii = new BigInteger(tokens.get(0).getLiteral());
            tokens.advance();
            return new Ast.Expression.Literal(ii);
        }
        else if (peek(Token.Type.DECIMAL)) {
            BigDecimal ii = new BigDecimal(tokens.get(0).getLiteral());
            tokens.advance();
            return new Ast.Expression.Literal(ii);
        }
        else if (peek(Token.Type.CHARACTER)) {
            Character c = strip(tokens.get(0).getLiteral()).charAt(0);
            tokens.advance();
            return new Ast.Expression.Literal(c);
        }
        else if (peek(Token.Type.STRING)) {
            String s = strip(tokens.get(0).getLiteral());
            tokens.advance();
            return new Ast.Expression.Literal(s);
        }
        else if (match("(")) {
            Ast.Expression.Group group = new Ast.Expression.Group(parseExpression());
            if (!match(")")) throw new ParseException("Missing closing parenthesis", tocLoc());
            return group;
        }
        else if (peek(Token.Type.IDENTIFIER)) {
            Token idn = tokens.get(0);
            tokens.advance();
            if (match("(")) {
                if (match(")")) return new Ast.Expression.Function(idn.getLiteral(), new ArrayList<>());
                List<Ast.Expression> values = new ArrayList<>();
                do {
                    values.add(parseExpression());
                } while (match(","));
                if (!match(")")) throw new ParseException("Missing closing parenthesis", tocLoc());
                return new Ast.Expression.Function(idn.getLiteral(), values);
            } else if (match("[")) {
                Ast.Expression.Access res = new Ast.Expression.Access(Optional.ofNullable(parseExpression()), idn.getLiteral());
                if (!match("]")) throw new ParseException("Missing closing bracket", tocLoc());
                return res;
            }
            return new Ast.Expression.Access(Optional.empty(), idn.getLiteral());
        }
        else {
            throw new ParseException("Invalid primary expression", tocLoc());
        }
    }

    /**
     * As in the lexer, returns {@code true} if the current sequence of tokens
     * matches the given patterns. Unlike the lexer, the pattern is not a regex;
     * instead it is either a {@link Token.Type}, which matches if the token's
     * type is the same, or a {@link String}, which matches if the token's
     * literal is the same.
     *
     * In other words, {@code Token(IDENTIFIER, "literal")} is matched by both
     * {@code peek(Token.Type.IDENTIFIER)} and {@code peek("literal")}.
     */
    private boolean peek(Object... patterns) {
        for (int i = 0; i < patterns.length; i++){
            if (!tokens.has(i)) {
                return false;
            }
            else if (patterns[i] instanceof Token.Type) {
                if (patterns[i] != tokens.get(i).getType()) {
                    return false;
                }
            }
            else if (patterns[i] instanceof String) {
                if (!patterns[i].equals(tokens.get(i).getLiteral())) {
                    return false;
                }
            }
            else {
                throw new AssertionError("Invalid pattern object: " + patterns[i].getClass());
            }
        }
        return true;
    }

    /**
     * As in the lexer, returns {@code true} if {@link #peek(Object...)} is true
     * and advances the token stream.
     */
    private boolean match(Object... patterns) {
        boolean peek = peek(patterns);
        if (peek) {
            for (int i = 0; i < patterns.length; i++) {
                tokens.advance();
            }
        }
        return peek;
    }

    public int tocLoc() {
        return tokens.has(0) ? tokens.get(0).getIndex() : tokens.get(-1).getIndex();
    }


    private static final class TokenStream {

        private final List<Token> tokens;
        private int index = 0;

        private TokenStream(List<Token> tokens) {
            this.tokens = tokens;
        }

        /**
         * Returns true if there is a token at index + offset.
         */
        public boolean has(int offset) {
            return index + offset < tokens.size();
        }

        /**
         * Gets the token at index + offset.
         */
        public Token get(int offset) {
            return tokens.get(index + offset);
        }

        /**
         * Advances to the next token, incrementing the index.
         */
        public void advance() {
            index++;
        }

    }

}
