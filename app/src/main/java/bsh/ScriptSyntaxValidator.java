package bsh;

import bsh.preprocess.AnnotationIgnorePreprocess;
import bsh.preprocess.DefaultArgsDesugar;
import bsh.preprocess.ImplicitDefaultConstructorPreprocess;
import bsh.preprocess.KtStringTemplate;
import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses with the bundled runtime grammar, without creating an interpreter or evaluating nodes. */
public final class ScriptSyntaxValidator {
    private static final Pattern LEXICAL_POSITION = Pattern.compile("at line (\\d+), column (\\d+)");

    private ScriptSyntaxValidator() {}

    public static final class Result {
        public final String error;
        public final int line;
        public final int column;
        public final String parsedSource;
        public final boolean originalLineNumbers;

        private Result(String error, int line, int column, String parsedSource, boolean originalLineNumbers) {
            this.error = error;
            this.line = line;
            this.column = column;
            this.parsedSource = parsedSource;
            this.originalLineNumbers = originalLineNumbers;
        }
    }

    public static Result validate(String source) {
        // Keep the order identical to Interpreter.preprocessScript(). These are pure text rewrites.
        String rewritten = AnnotationIgnorePreprocess.rewrite(source);
        String defaults = DefaultArgsDesugar.rewrite(rewritten);
        boolean originalLineNumbers = defaults.equals(rewritten);
        rewritten = KtStringTemplate.rewrite(defaults);
        // Template expansion may collapse multiline literals; it never creates new source lines.
        originalLineNumbers &= lineCount(rewritten) == lineCount(defaults);
        rewritten = ImplicitDefaultConstructorPreprocess.rewrite(rewritten);
        String input = rewritten.endsWith(";") ? rewritten : rewritten + ";";
        Parser parser = new Parser(new StringReader(input));
        Result nativeIssue = null;
        try {
            while (!parser.Line()) {
                SimpleNode node = parser.popNode(); // Release each AST instead of accumulating the script.
                if (nativeIssue == null && node instanceof BSHMethodDeclaration method
                        && method.modifiers.hasModifier("native")) {
                    nativeIssue = new Result(
                            "BeanShell 顶层 native 方法无法绑定 JNI，请把 native 声明放进类并将该类的 ClassLoader 传给 loadSo",
                            node.firstToken.beginLine, node.firstToken.beginColumn, rewritten, originalLineNumbers);
                }
            }
            return nativeIssue != null ? nativeIssue : new Result(null, 0, 0, rewritten, originalLineNumbers);
        } catch (ParseException error) {
            // ParseException.getErrorLineNumber() dereferences a null token for literal/modifier errors.
            // getMessage() also uses a global sourceFile, which may belong to another running plugin.
            Token token = error.currentToken == null ? parser.token : error.currentToken.next;
            String detail = error.getCause() == null ? null : error.getCause().getMessage();
            if (detail == null || detail.isEmpty()) {
                String image = token == null || token.kind == ParserConstants.EOF ? "文件结尾" : token.image;
                detail = "无法解析 “" + image + "”，请检查当前位置及前一行";
            }
            return new Result("BeanShell 语法错误：" + detail,
                    token == null ? 0 : token.beginLine, token == null ? 0 : token.beginColumn,
                    rewritten, originalLineNumbers);
        } catch (TokenMgrException error) {
            String detail = error.getMessage();
            Matcher position = LEXICAL_POSITION.matcher(detail == null ? "" : detail);
            int line = 0;
            int column = 0;
            if (position.find()) {
                line = Integer.parseInt(position.group(1));
                column = Integer.parseInt(position.group(2));
            }
            return new Result("BeanShell 词法错误：" + detail, line, column, rewritten, originalLineNumbers);
        }
    }

    private static int lineCount(String text) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || (ch == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n'))) lines++;
        }
        return lines;
    }
}
