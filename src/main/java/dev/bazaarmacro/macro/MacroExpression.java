package dev.bazaarmacro.macro;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A tiny math expression language for {@code COMPUTE} steps: arithmetic, percent
 * literals, a handful of functions, and variables resolved from a caller-supplied map
 * (game state like {@code purse}/{@code buyPrice}/{@code sellPrice}, plus any earlier
 * step's named result).
 *
 * <p>Grammar (highest to lowest precedence): primary/call/parens, percent suffix (e.g.
 * {@code 25%} = 0.25), unary minus, {@code * /}, {@code + -}.
 *
 * <p>Examples: {@code purse * 0.25}, {@code 25% * purse}, {@code floor(purse * 0.1 / buyPrice)},
 * {@code min(64, floor(budget / buyPrice))}.
 */
public final class MacroExpression {
    private MacroExpression() {
    }

    /** Variable names are matched case-insensitively. */
    public static double evaluate(String expression, Map<String, Double> variables) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Expression is empty");
        }
        List<Token> tokens = tokenize(expression);
        Parser parser = new Parser(tokens, variables);
        double result = parser.parseExpression();
        parser.expectEnd();
        return result;
    }

    private enum TokenType {NUMBER, IDENT, PLUS, MINUS, STAR, SLASH, PERCENT, LPAREN, RPAREN, COMMA, EOF}

    private record Token(TokenType type, String text, double number) {
    }

    private static List<Token> tokenize(String input) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        int len = input.length();
        while (i < len) {
            char c = input.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (Character.isDigit(c) || (c == '.' && i + 1 < len && Character.isDigit(input.charAt(i + 1)))) {
                int start = i;
                while (i < len && (Character.isDigit(input.charAt(i)) || input.charAt(i) == '.')) i++;
                String text = input.substring(start, i);
                tokens.add(new Token(TokenType.NUMBER, text, Double.parseDouble(text)));
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                int start = i;
                while (i < len && (Character.isLetterOrDigit(input.charAt(i)) || input.charAt(i) == '_')) i++;
                tokens.add(new Token(TokenType.IDENT, input.substring(start, i), 0));
                continue;
            }
            switch (c) {
                case '+' -> tokens.add(new Token(TokenType.PLUS, "+", 0));
                case '-' -> tokens.add(new Token(TokenType.MINUS, "-", 0));
                case '*' -> tokens.add(new Token(TokenType.STAR, "*", 0));
                case '/' -> tokens.add(new Token(TokenType.SLASH, "/", 0));
                case '%' -> tokens.add(new Token(TokenType.PERCENT, "%", 0));
                case '(' -> tokens.add(new Token(TokenType.LPAREN, "(", 0));
                case ')' -> tokens.add(new Token(TokenType.RPAREN, ")", 0));
                case ',' -> tokens.add(new Token(TokenType.COMMA, ",", 0));
                default -> throw new IllegalArgumentException("Unexpected character '" + c + "' in expression");
            }
            i++;
        }
        tokens.add(new Token(TokenType.EOF, "", 0));
        return tokens;
    }

    private static final class Parser {
        private final List<Token> tokens;
        private final Map<String, Double> variables;
        private int pos = 0;

        Parser(List<Token> tokens, Map<String, Double> variables) {
            this.tokens = tokens;
            this.variables = variables;
        }

        void expectEnd() {
            if (peek().type() != TokenType.EOF) {
                throw new IllegalArgumentException("Unexpected trailing input near '" + peek().text() + "'");
            }
        }

        private Token peek() {
            return tokens.get(pos);
        }

        private Token advance() {
            return tokens.get(pos++);
        }

        double parseExpression() {
            double value = parseTerm();
            while (true) {
                if (peek().type() == TokenType.PLUS) {
                    advance();
                    value += parseTerm();
                } else if (peek().type() == TokenType.MINUS) {
                    advance();
                    value -= parseTerm();
                } else {
                    break;
                }
            }
            return value;
        }

        private double parseTerm() {
            double value = parseUnary();
            while (true) {
                if (peek().type() == TokenType.STAR) {
                    advance();
                    value *= parseUnary();
                } else if (peek().type() == TokenType.SLASH) {
                    advance();
                    value /= parseUnary();
                } else {
                    break;
                }
            }
            return value;
        }

        private double parseUnary() {
            if (peek().type() == TokenType.MINUS) {
                advance();
                return -parseUnary();
            }
            if (peek().type() == TokenType.PLUS) {
                advance();
                return parseUnary();
            }
            return parsePercent();
        }

        private double parsePercent() {
            double value = parsePrimary();
            while (peek().type() == TokenType.PERCENT) {
                advance();
                value = value / 100.0;
            }
            return value;
        }

        private double parsePrimary() {
            Token t = peek();
            if (t.type() == TokenType.NUMBER) {
                advance();
                return t.number();
            }
            if (t.type() == TokenType.LPAREN) {
                advance();
                double value = parseExpression();
                expect(TokenType.RPAREN, ")");
                return value;
            }
            if (t.type() == TokenType.IDENT) {
                advance();
                String name = t.text();
                if (peek().type() == TokenType.LPAREN) {
                    advance();
                    List<Double> args = new ArrayList<>();
                    if (peek().type() != TokenType.RPAREN) {
                        args.add(parseExpression());
                        while (peek().type() == TokenType.COMMA) {
                            advance();
                            args.add(parseExpression());
                        }
                    }
                    expect(TokenType.RPAREN, ")");
                    return callFunction(name, args);
                }
                return lookupVariable(name);
            }
            throw new IllegalArgumentException("Unexpected token '" + t.text() + "' in expression");
        }

        private void expect(TokenType type, String display) {
            if (peek().type() != type) {
                throw new IllegalArgumentException("Expected '" + display + "' in expression");
            }
            advance();
        }

        private double lookupVariable(String name) {
            Double value = variables.get(name.toLowerCase(Locale.ROOT));
            if (value == null) {
                throw new IllegalArgumentException("Unknown variable \"" + name + "\"");
            }
            return value;
        }

        private double callFunction(String name, List<Double> args) {
            return switch (name.toLowerCase(Locale.ROOT)) {
                case "floor" -> {
                    requireArgs(name, args, 1);
                    yield Math.floor(args.get(0));
                }
                case "ceil" -> {
                    requireArgs(name, args, 1);
                    yield Math.ceil(args.get(0));
                }
                case "round" -> {
                    requireArgs(name, args, 1);
                    yield (double) Math.round(args.get(0));
                }
                case "abs" -> {
                    requireArgs(name, args, 1);
                    yield Math.abs(args.get(0));
                }
                case "min" -> {
                    requireArgs(name, args, 2);
                    yield Math.min(args.get(0), args.get(1));
                }
                case "max" -> {
                    requireArgs(name, args, 2);
                    yield Math.max(args.get(0), args.get(1));
                }
                default -> throw new IllegalArgumentException("Unknown function \"" + name + "\"");
            };
        }

        private void requireArgs(String name, List<Double> args, int count) {
            if (args.size() != count) {
                throw new IllegalArgumentException(name + "() expects " + count + " argument(s)");
            }
        }
    }
}
