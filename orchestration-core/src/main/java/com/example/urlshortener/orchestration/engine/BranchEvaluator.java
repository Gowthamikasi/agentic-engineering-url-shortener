package com.example.urlshortener.orchestration.engine;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.Map;

/**
 * Evaluates a node's {@code branchCondition} against the run context.
 *
 * <p>Expressions see two read-only maps, {@code #input} and {@code #facts}, and nothing else:
 * the evaluation context is a {@link SimpleEvaluationContext} restricted to property reads, so a
 * condition in a definition file cannot call methods, construct types, or otherwise become a
 * scripting hole in a file that looks like configuration.
 *
 * <p>A malformed expression evaluates to false rather than true. A node that is skipped because
 * its condition could not be understood is visible in the graph; a node that ran because of a
 * typo would not be.
 */
public final class BranchEvaluator {

    private final ExpressionParser parser =
            new SpelExpressionParser(new SpelParserConfiguration(false, false, 64));

    public boolean evaluate(String expression, Map<String, Object> input, Map<String, Object> facts) {
        if (expression == null || expression.isBlank()) {
            return true;
        }
        EvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        context.setVariable("input", input);
        context.setVariable("facts", facts);
        try {
            Boolean value = parser.parseExpression(expression).getValue(context, Boolean.class);
            return Boolean.TRUE.equals(value);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Explains the verdict for the journal, so a skipped node says why it was skipped. */
    public String describe(String expression, boolean result) {
        if (expression == null || expression.isBlank()) {
            return "no branch condition";
        }
        return "branchCondition " + (result ? "true" : "false") + ": " + expression;
    }
}
