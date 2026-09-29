package com.example.urlshortener.orchestration.engine;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.SpelParserConfiguration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.Map;

/** Evaluates a node's branchCondition against the run context. */
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
