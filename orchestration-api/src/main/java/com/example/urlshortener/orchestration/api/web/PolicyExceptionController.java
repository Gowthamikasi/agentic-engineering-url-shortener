package com.example.urlshortener.orchestration.api.web;

import com.example.urlshortener.orchestration.infrastructure.PolicyExceptionStore;
import com.example.urlshortener.policy.PolicyException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The policy-exception workflow (REQ-D-006).
 *
 * <p>An exception is requested, then approved by a named human with a compensating control and an
 * expiry. Two rules are enforced here rather than left to discipline: an approval must name a
 * compensating control, and it must state when the waiver lapses. An exception with neither is how
 * a temporary deviation quietly becomes the new standard.
 */
@RestController
@RequestMapping("/api/v1/policy-exceptions")
public class PolicyExceptionController {

    /** Maximum life of a waiver. Longer than this and it is a policy change, not an exception. */
    private static final Duration MAX_VALIDITY = Duration.ofDays(90);

    public record RequestException(
            @NotBlank(message = "policyId is required") String policyId,
            @NotBlank(message = "reason is required") String reason,
            @NotBlank(message = "scope is required") String scope,
            String runId) {
    }

    public record DecideException(
            @NotBlank(message = "decision is required") String decision,
            @NotBlank(message = "approver is required") String approver,
            @NotBlank(message = "compensatingControl is required; a waiver with no compensating control is just a gap")
            String compensatingControl,
            Long validForDays,
            String reviewCondition) {
    }

    private final PolicyExceptionStore store;
    private final Clock clock;

    public PolicyExceptionController(PolicyExceptionStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @GetMapping
    public List<PolicyException> list() {
        return store.findAll();
    }

    /** Records a request. It is not approved and grants nothing until a decision arrives. */
    @PostMapping
    public ResponseEntity<PolicyException> request(@Valid @RequestBody RequestException request) {
        String id = "exc_" + Long.toHexString(clock.millis());
        PolicyException exception = new PolicyException(id, request.policyId(), request.reason(),
                request.scope(), null, null, null, null, null);
        store.save(request.runId(), exception);
        return ResponseEntity.status(HttpStatus.CREATED).body(exception);
    }

    @PostMapping("/{exceptionId}/decision")
    public PolicyException decide(@PathVariable String exceptionId, @Valid @RequestBody DecideException request) {
        PolicyException existing = store.find(exceptionId)
                .orElseThrow(() -> new IllegalArgumentException("No such policy exception: " + exceptionId));

        if (!"APPROVE".equalsIgnoreCase(request.decision())) {
            PolicyException rejected = new PolicyException(existing.id(), existing.policyId(), existing.reason(),
                    existing.scope(), null, null, null, null,
                    "Rejected by " + request.approver());
            store.save(null, rejected);
            return rejected;
        }

        long days = request.validForDays() == null ? 30 : request.validForDays();
        if (days <= 0 || days > MAX_VALIDITY.toDays()) {
            throw new IllegalArgumentException(
                    "validForDays must be between 1 and " + MAX_VALIDITY.toDays()
                            + "; a longer waiver is a policy change, not an exception.");
        }

        Instant now = clock.instant();
        PolicyException approved = new PolicyException(existing.id(), existing.policyId(), existing.reason(),
                existing.scope(), request.approver(), request.compensatingControl(), now,
                now.plus(Duration.ofDays(days)), request.reviewCondition());

        store.save(null, approved);
        return approved;
    }
}
