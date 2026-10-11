package bsh.preprocess;

/**
 * Adds an explicit empty constructor for script-declared classes that do not
 * declare any constructor themselves.
 *
 * <p>This keeps WA-style BeanShell plugins working when they rely on
 * {@code new Foo()} for simple data-holder classes.
 */
public final class ImplicitDefaultConstructorPreprocess {
    private ImplicitDefaultConstructorPreprocess() {
    }

    public static String rewrite(String source) {
        if (source == null || source.indexOf("class") < 0) {
            return source;
        }
        return rewriteSegment(source, 0, source.length());
    }

    private static String rewriteSegment(String source, int start, int end) {
        StringBuilder out = new StringBuilder((end - start) + 64);
        int i = start;
        int lastEmit = start;
        while (i < end) {
            int skip = skipLiteralOrComment(source, i, end);
            if (skip > i) {
                i = skip;
                continue;
            }

            if (!isKeywordAt(source, i, end, "class")) {
                i++;
                continue;
            }

            int nameStart = skipWsAndCommentsForward(source, i + 5, end);
            if (nameStart >= end || !Character.isJavaIdentifierStart(source.charAt(nameStart))) {
                i++;
                continue;
            }

            int nameEnd = nameStart + 1;
            while (nameEnd < end && Character.isJavaIdentifierPart(source.charAt(nameEnd))) {
                nameEnd++;
            }
            String className = source.substring(nameStart, nameEnd);

            int bodyStart = findClassBodyStart(source, nameEnd, end);
            if (bodyStart < 0) {
                i = nameEnd;
                continue;
            }

            int bodyEnd = findMatching(source, bodyStart, end, '{', '}');
            if (bodyEnd < 0) {
                i = nameEnd;
                continue;
            }

            String rawBody = source.substring(bodyStart + 1, bodyEnd);
            String rewrittenBody = rewriteSegment(rawBody, 0, rawBody.length());
            boolean hasConstructor = hasTopLevelConstructor(rawBody, className);

            out.append(source, lastEmit, bodyStart + 1);
            if (!hasConstructor) {
                out.append(' ').append(className).append("(){}");
            }
            out.append(rewrittenBody);
            out.append('}');

            lastEmit = bodyEnd + 1;
            i = lastEmit;
        }

        out.append(source, lastEmit, end);
        return out.toString();
    }

    private static boolean hasTopLevelConstructor(String body, String className) {
        int len = body.length();
        int depth = 0;
        int i = 0;
        while (i < len) {
            int skip = skipLiteralOrComment(body, i, len);
            if (skip > i) {
                i = skip;
                continue;
            }

            char ch = body.charAt(i);
            if (ch == '{') {
                depth++;
                i++;
                continue;
            }
            if (ch == '}') {
                if (depth > 0) {
                    depth--;
                }
                i++;
                continue;
            }

            if (depth == 0 && Character.isJavaIdentifierStart(ch)) {
                int tokenEnd = i + 1;
                while (tokenEnd < len && Character.isJavaIdentifierPart(body.charAt(tokenEnd))) {
                    tokenEnd++;
                }
                if (className.equals(body.substring(i, tokenEnd))) {
                    int next = skipWsAndCommentsForward(body, tokenEnd, len);
                    if (next < len && body.charAt(next) == '(') {
                        int closeParen = findMatching(body, next, len, '(', ')');
                        if (closeParen >= 0) {
                            int afterParen = skipWsAndCommentsForward(body, closeParen + 1, len);
                            if (isKeywordAt(body, afterParen, len, "throws")) {
                                afterParen = skipThrowsClause(body, afterParen + 6, len);
                            }
                            afterParen = skipWsAndCommentsForward(body, afterParen, len);
                            if (afterParen < len && body.charAt(afterParen) == '{') {
                                return true;
                            }
                        }
                    }
                }
                i = tokenEnd;
                continue;
            }

            i++;
        }
        return false;
    }

    private static int findClassBodyStart(String source, int from, int end) {
        int angleDepth = 0;
        int parenDepth = 0;
        int bracketDepth = 0;
        for (int i = from; i < end; i++) {
            int skip = skipLiteralOrComment(source, i, end);
            if (skip > i) {
                i = skip - 1;
                continue;
            }

            char ch = source.charAt(i);
            switch (ch) {
                case '<':
                    angleDepth++;
                    break;
                case '>':
                    if (angleDepth > 0) {
                        angleDepth--;
                    }
                    break;
                case '(':
                    parenDepth++;
                    break;
                case ')':
                    if (parenDepth > 0) {
                        parenDepth--;
                    }
                    break;
                case '[':
                    bracketDepth++;
                    break;
                case ']':
                    if (bracketDepth > 0) {
                        bracketDepth--;
                    }
                    break;
                case '{':
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                        return i;
                    }
                    break;
                default:
                    break;
            }
        }
        return -1;
    }

    private static int skipThrowsClause(String source, int from, int end) {
        int i = from;
        int angleDepth = 0;
        int parenDepth = 0;
        int bracketDepth = 0;
        while (i < end) {
            int skip = skipLiteralOrComment(source, i, end);
            if (skip > i) {
                i = skip;
                continue;
            }
            char ch = source.charAt(i);
            switch (ch) {
                case '<':
                    angleDepth++;
                    break;
                case '>':
                    if (angleDepth > 0) {
                        angleDepth--;
                    }
                    break;
                case '(':
                    parenDepth++;
                    break;
                case ')':
                    if (parenDepth > 0) {
                        parenDepth--;
                    }
                    break;
                case '[':
                    bracketDepth++;
                    break;
                case ']':
                    if (bracketDepth > 0) {
                        bracketDepth--;
                    }
                    break;
                case '{':
                case ';':
                    if (angleDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                        return i;
                    }
                    break;
                default:
                    break;
            }
            i++;
        }
        return i;
    }

    private static boolean isKeywordAt(String source, int index, int end, String keyword) {
        int keywordLen = keyword.length();
        if (index < 0 || index + keywordLen > end) {
            return false;
        }
        if (!source.regionMatches(index, keyword, 0, keywordLen)) {
            return false;
        }

        if (index > 0) {
            char prev = source.charAt(index - 1);
            if (Character.isJavaIdentifierPart(prev) || prev == '.' || prev == '$') {
                return false;
            }
        }
        if (index + keywordLen < end) {
            char next = source.charAt(index + keywordLen);
            if (Character.isJavaIdentifierPart(next)) {
                return false;
            }
        }
        return true;
    }

    private static int skipWsAndCommentsForward(String source, int index, int end) {
        int i = index;
        while (i < end) {
            char ch = source.charAt(i);
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            int skip = skipLiteralOrComment(source, i, end);
            if (skip > i) {
                i = skip;
                continue;
            }
            break;
        }
        return i;
    }

    private static int findMatching(String source, int openPos, int end, char open, char close) {
        int depth = 0;
        for (int i = openPos; i < end; i++) {
            int skip = skipLiteralOrComment(source, i, end);
            if (skip > i) {
                i = skip - 1;
                continue;
            }

            char ch = source.charAt(i);
            if (ch == open) {
                depth++;
            } else if (ch == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int skipLiteralOrComment(String source, int i, int end) {
        if (i >= end) {
            return i;
        }
        char ch = source.charAt(i);

        if (ch == '"') {
            if (isTripleQuote(source, i, end)) {
                int close = findTripleQuoteEnd(source, i + 3, end);
                return close < 0 ? end : close + 3;
            }
            int close = findStringEnd(source, i + 1, end);
            return close < 0 ? end : close + 1;
        }

        if (ch == '\'') {
            int close = findCharEnd(source, i + 1, end);
            return close < 0 ? end : close + 1;
        }

        if (ch == '/' && i + 1 < end) {
            char next = source.charAt(i + 1);
            if (next == '/') {
                int j = i + 2;
                while (j < end && source.charAt(j) != '\n' && source.charAt(j) != '\r') {
                    j++;
                }
                return j;
            }
            if (next == '*') {
                int j = i + 2;
                while (j + 1 < end) {
                    if (source.charAt(j) == '*' && source.charAt(j + 1) == '/') {
                        return j + 2;
                    }
                    j++;
                }
                return end;
            }
        }

        return i;
    }

    private static boolean isTripleQuote(String source, int index, int end) {
        return index + 2 < end
                && source.charAt(index) == '"'
                && source.charAt(index + 1) == '"'
                && source.charAt(index + 2) == '"';
    }

    private static int findTripleQuoteEnd(String source, int from, int end) {
        for (int i = from; i + 2 < end; i++) {
            if (source.charAt(i) == '"'
                    && source.charAt(i + 1) == '"'
                    && source.charAt(i + 2) == '"') {
                return i;
            }
        }
        return -1;
    }

    private static int findStringEnd(String source, int from, int end) {
        for (int i = from; i < end; i++) {
            char ch = source.charAt(i);
            if (ch == '\\') {
                i++;
                continue;
            }
            if (ch == '"') {
                return i;
            }
        }
        return -1;
    }

    private static int findCharEnd(String source, int from, int end) {
        for (int i = from; i < end; i++) {
            char ch = source.charAt(i);
            if (ch == '\\') {
                i++;
                continue;
            }
            if (ch == '\'') {
                return i;
            }
            if (ch == '\n' || ch == '\r') {
                return -1;
            }
        }
        return -1;
    }
}
