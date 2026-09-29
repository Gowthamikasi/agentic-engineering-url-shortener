package com.example.urlshortener.orchestration.engine;

import com.example.urlshortener.orchestration.model.FailureClass;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;

/** Decides whether a failure is worth retrying. */
public final class FailureClassifier {

    private static final String[] TRANSIENT_SIGNATURES = {
            "lock timeout", "deadlock", "connection reset", "connection refused",
            "timed out", "temporarily unavailable", "address already in use",
            "port in use", "too many connections", "sqlite_busy", "could not obtain connection"
    };

    private FailureClassifier() {
    }

    public static FailureClass classify(Throwable throwable) {
        if (throwable instanceof TimeoutException || throwable instanceof java.net.SocketTimeoutException) {
            return FailureClass.TRANSIENT;
        }
        if (throwable instanceof IOException) {
            return FailureClass.TRANSIENT;
        }
        String message = throwable.getMessage();
        if (message != null && matchesTransientSignature(message)) {
            return FailureClass.TRANSIENT;
        }
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            return classify(cause);
        }
        return FailureClass.PERMANENT;
    }

    /** An agent's own classification is honoured only when it claims transient for a known signature. */
    public static FailureClass reconcile(FailureClass agentClaim, String message) {
        if (agentClaim == FailureClass.TRANSIENT) {
            return FailureClass.TRANSIENT;
        }
        if (agentClaim == null && message != null && matchesTransientSignature(message)) {
            return FailureClass.TRANSIENT;
        }
        return FailureClass.PERMANENT;
    }

    private static boolean matchesTransientSignature(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        for (String signature : TRANSIENT_SIGNATURES) {
            if (lower.contains(signature)) {
                return true;
            }
        }
        return false;
    }
}
