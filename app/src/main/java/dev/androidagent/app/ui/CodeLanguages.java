package dev.androidagent.app.ui;

import io.noties.prism4j.annotations.PrismBundle;

@PrismBundle(include = {"clike", "java", "kotlin", "python", "javascript", "json", "sql", "markup", "css"}, grammarLocatorClassName = ".CodeGrammarLocator")
public final class CodeLanguages {
    private CodeLanguages() {}
    public static io.noties.markwon.MarkwonPlugin syntax() {
        return io.noties.markwon.syntax.SyntaxHighlightPlugin.create(
            new io.noties.prism4j.Prism4j(new CodeGrammarLocator()),
            io.noties.markwon.syntax.Prism4jThemeDarkula.create());
    }
}
