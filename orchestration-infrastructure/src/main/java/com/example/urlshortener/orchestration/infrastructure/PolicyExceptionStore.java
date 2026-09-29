package com.example.urlshortener.orchestration.infrastructure;

import com.example.urlshortener.orchestration.infrastructure.jpa.PolicyExceptionJpaRepository;
import com.example.urlshortener.policy.PolicyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Approved deviations from mandatory policy.
 *
 * <p>Stored separately from decisions because an exception outlives the run that requested it:
 * it has its own expiry and has to be re-checked by every later run that hits the same rule.
 */
@Repository
public class PolicyExceptionStore {

    private final PolicyExceptionJpaRepository jpa;

    public PolicyExceptionStore(PolicyExceptionJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Transactional
    public PolicyException save(String runId, PolicyException exception) {
        jpa.save(new ControlPlaneEntities.PolicyExceptionEntity(exception.id(), runId, exception.policyId(),
                exception.reason(), exception.scope(), exception.approver(), exception.compensatingControl(),
                exception.approvedAt(), exception.expiresAt(), exception.reviewCondition()));
        return exception;
    }

    @Transactional(readOnly = true)
    public List<PolicyException> findAll() {
        return jpa.findAll().stream().map(PolicyExceptionStore::toDomain).toList();
    }

    @Transactional(readOnly = true)
    public Optional<PolicyException> find(String exceptionId) {
        return jpa.findById(exceptionId).map(PolicyExceptionStore::toDomain);
    }

    private static PolicyException toDomain(ControlPlaneEntities.PolicyExceptionEntity e) {
        return new PolicyException(e.getExceptionId(), e.getPolicyId(), e.getReason(), e.getScope(),
                e.getApprover(), e.getCompensatingControl(), e.getApprovedAt(), e.getExpiresAt(),
                e.getReviewCondition());
    }
}
