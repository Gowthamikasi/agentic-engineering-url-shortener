package com.example.urlshortener.api.web;

import com.example.urlshortener.domain.ErrorCodes;
import com.example.urlshortener.domain.error.BlockedTargetException;
import com.example.urlshortener.domain.error.CodeGenerationException;
import com.example.urlshortener.domain.error.DomainException;
import com.example.urlshortener.domain.error.IdempotencyConflictException;
import com.example.urlshortener.domain.error.InvalidRequestException;
import com.example.urlshortener.domain.error.LinkExpiredException;
import com.example.urlshortener.domain.error.LinkNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.stream.Collectors;

/** Turns domain failures into RFC 9457 problem responses. */
@RestControllerAdvice(basePackages = "com.example.urlshortener.api")
public class ProblemDetailAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailAdvice.class);

    @ExceptionHandler(LinkNotFoundException.class)
    public ProblemDetail onNotFound(LinkNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Short link not found", e);
    }

    @ExceptionHandler(LinkExpiredException.class)
    public ProblemDetail onExpired(LinkExpiredException e) {
        return problem(HttpStatus.GONE, "Short link has expired", e);
    }

    @ExceptionHandler(BlockedTargetException.class)
    public ProblemDetail onBlocked(BlockedTargetException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Target host is not allowed", e);
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ProblemDetail onIdempotencyConflict(IdempotencyConflictException e) {
        return problem(HttpStatus.CONFLICT, "Idempotency-Key reused with a different request", e);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ProblemDetail onInvalidRequest(InvalidRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, "Request is not valid", e);
    }

    @ExceptionHandler(CodeGenerationException.class)
    public ProblemDetail onCodeExhaustion(CodeGenerationException e) {
        log.warn("Short code minting exhausted its retry budget: {}", e.getMessage());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Could not allocate a short code", e);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onBeanValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setType(URI.create("https://example/errors/validation"));
        problem.setTitle("Request is not valid");
        problem.setDetail(detail.isBlank() ? "Request body failed validation." : detail);
        problem.setProperty("errorCode", ErrorCodes.URL_MALFORMED);
        addTraceId(problem);
        return problem;
    }

    private static ProblemDetail problem(HttpStatus status, String title, DomainException e) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("https://example/errors/" + e.errorCode().toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setDetail(e.getMessage());
        problem.setProperty("errorCode", e.errorCode());
        addTraceId(problem);
        return problem;
    }

    /** Links the error the caller sees to the log line an operator will read. */
    private static void addTraceId(ProblemDetail problem) {
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
    }
}
