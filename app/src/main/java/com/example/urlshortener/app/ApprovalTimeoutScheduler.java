package com.example.urlshortener.app;

import com.example.urlshortener.orchestration.engine.WorkflowEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sweeps gates that have been waiting too long.
 *
 * <p>All it can do is safe-stop a run. There is no setting that lets elapsed time approve
 * anything (REQ-D-010), and a test checks that.
 */
@Component
public class ApprovalTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalTimeoutScheduler.class);

    private final WorkflowEngine engine;

    public ApprovalTimeoutScheduler(WorkflowEngine engine) {
        this.engine = engine;
    }

    @Scheduled(fixedDelayString = "${orchestration.approval-sweep-interval-ms:60000}")
    public void sweep() {
        int safeStopped = engine.expireApprovals();
        if (safeStopped > 0) {
            log.warn("Safe-stopped {} run(s) whose approval window elapsed", safeStopped);
        }
    }
}
