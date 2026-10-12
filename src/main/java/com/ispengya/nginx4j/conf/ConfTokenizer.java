package com.ispengya.nginx4j.conf;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns nginx.conf text into a flat token list.
 *
 * <p>Grammar of the supported subset: {@code { } ;} are structural characters, {@code #} starts a
 * comment that runs to the end of the line, whitespace separates tokens, and single or double quotes
 * group a token that contains whitespace or structural characters. A structural character does not
 * need surrounding whitespace, so {@code location /a{...}} tokenises like nginx does.
 *
 * <p>Positions are 1-based and tracked across lines, because {@link ConfException} reports the
 * exact character that broke the configuration.
 */
final class ConfTokenizer {

    enum Kind {
        WORD, LBRACE, RBRACE, SEMICOLON, EOF
    }

    static final class Token {

        final Kind kind;
        final String text;
        final int line;
        final int column;

        Token(Kind kind, String text, int line, int column) {
            this.kind = kind;
            this.text = text;
            this.line = line;
            this.column = column;
        }

        @Override
        public String toString() {
            return kind == Kind.EOF ? "end of file" : "'" + text + "'";
        }
    }

    private final String source;
    private final String text;
    private int index;
    private int line = 1;
    private int column = 1;

    ConfTokenizer(String source, String text) {
        this.source = source;
        this.text = text;
    }

    List<Token> tokenize() {
        List<Token> tokens = new ArrayList<Token>();
        while (true) {
            Token token = next();
            tokens.add(token);
            if (token.kind == Kind.EOF) {
                return tokens;
            }
        }
    }

    private Token next() {
        skipWhitespaceAndComments();
        int startLine = line;
        int startColumn = column;
        if (index >= text.length()) {
            return new Token(Kind.EOF, "", startLine, startColumn);
        }
        char current = text.charAt(index);
        switch (current) {
            case '{':
                advance();
                return new Token(Kind.LBRACE, "{", startLine, startColumn);
            case '}':
                advance();
                return new Token(Kind.RBRACE, "}", startLine, startColumn);
            case ';':
                advance();
                return new Token(Kind.SEMICOLON, ";", startLine, startColumn);
            case '"':
            case '\'':
                return quoted(current, startLine, startColumn);
            default:
                return bareWord(startLine, startColumn);
        }
    }

    private Token quoted(char quote, int startLine, int startColumn) {
        advance();
        StringBuilder value = new StringBuilder();
        while (true) {
            if (index >= text.length()) {
                throw new ConfException(source, startLine, startColumn, "unterminated quoted string");
            }
            char current = text.charAt(index);
            if (current == quote) {
                advance();
                return new Token(Kind.WORD, value.toString(), startLine, startColumn);
            }
            if (current == '\\' && index + 1 < text.length()) {
                advance();
                value.append(text.charAt(index));
                advance();
                continue;
            }
            value.append(current);
            advance();
        }
    }

    private Token bareWord(int startLine, int startColumn) {
        StringBuilder value = new StringBuilder();
        while (index < text.length()) {
            char current = text.charAt(index);
            if (Character.isWhitespace(current) || current == '{' || current == '}' || current == ';') {
                break;
            }
            value.append(current);
            advance();
        }
        return new Token(Kind.WORD, value.toString(), startLine, startColumn);
    }

    private void skipWhitespaceAndComments() {
        while (index < text.length()) {
            char current = text.charAt(index);
            if (Character.isWhitespace(current)) {
                advance();
                continue;
            }
            if (current == '#') {
                while (index < text.length() && text.charAt(index) != '\n') {
                    advance();
                }
                continue;
            }
            return;
        }
    }

    /** Consumes one character while keeping the 1-based line and column accurate. */
    private void advance() {
        char current = text.charAt(index);
        index++;
        if (current == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
    }
}