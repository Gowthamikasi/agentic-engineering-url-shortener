package com.example.urlshortener.orchestration.api.web;

import com.example.urlshortener.orchestration.engine.DagValidator;
import com.example.urlshortener.orchestration.engine.NodeStateMachine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.stream.Collectors;

/** Maps control-plane failures to RFC 9457 problem responses. */
@RestControllerAdvice(basePackages = "com.example.urlshortener.orchestration.api")
public class ControlPlaneProblemAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onNotFoundOrBadRequest(IllegalArgumentException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        boolean missing = message.startsWith("No such");
        return problem(missing ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST,
                missing ? "Resource not found" : "Request is not valid",
                message, missing ? "NOT_FOUND" : "INVALID_REQUEST");
    }

    /** Deciding on a gate that is not waiting for a decision is a conflict, not a server error. */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail onConflict(IllegalStateException e) {
        return problem(HttpStatus.CONFLICT, "Action conflicts with the current state",
                e.getMessage(), "STATE_CONFLICT");
    }

    /** The engine refused a transition. Surfaced as a 409 naming it, rather than an opaque 500. */
    @ExceptionHandler(NodeStateMachine.IllegalTransitionException.class)
    public ProblemDetail onIllegalTransition(NodeStateMachine.IllegalTransitionException e) {
        return problem(HttpStatus.CONFLICT, "Illegal state transition", e.getMessage(), "ILLEGAL_TRANSITION");
    }

    @ExceptionHandler(DagValidator.InvalidDefinitionException.class)
    public ProblemDetail onInvalidDefinition(DagValidator.InvalidDefinitionException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Workflow definition is not a valid DAG",
                e.getMessage(), "INVALID_DEFINITION");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Request is not valid",
                detail.isBlank() ? "Request body failed validation." : detail, "INVALID_REQUEST");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("https://example/errors/" + errorCode.toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setDetail(detail);
        problem.setProperty("errorCode", errorCode);
        return problem;
    }
}
