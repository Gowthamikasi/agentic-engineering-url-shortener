package com.example.urlshortener.telemetry.audit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditHasherTest {

    private static final Instant T0 = Instant.parse("2026-09-28T14:00:00Z");

    private static AuditEvent unsealed(long seq, String action, String prevHash) {
        return new AuditEvent("run_1", seq, T0.plusSeconds(seq), ActorType.ENGINE, "engine",
                action, "node", "implement", "OK", null, "1.0.0", 1, prevHash, null);
    }

    private static List<AuditEvent> chainOf(String... actions) {
        List<AuditEvent> chain = new ArrayList<>();
        String prev = AuditEvent.GENESIS_HASH;
        for (int i = 0; i < actions.length; i++) {
            AuditEvent sealed = AuditHasher.seal(unsealed(i + 1, actions[i], prev));
            chain.add(sealed);
            prev = sealed.hash();
        }
        return chain;
    }

    @Test
    void sealing_is_deterministic() {
        AuditEvent a = AuditHasher.seal(unsealed(1, AuditActions.NODE_STARTED, AuditEvent.GENESIS_HASH));
        AuditEvent b = AuditHasher.seal(unsealed(1, AuditActions.NODE_STARTED, AuditEvent.GENESIS_HASH));

        assertThat(a.hash()).isEqualTo(b.hash()).hasSize(64);
    }

    @Test
    void an_intact_chain_verifies() {
        List<AuditEvent> chain = chainOf(AuditActions.NODE_STARTED, AuditActions.NODE_SUCCEEDED, AuditActions.POLICY_EVALUATED);

        assertThat(AuditHasher.verifyChain(chain)).isEqualTo(-1);
    }

    @Test
    void editing_a_row_in_the_middle_breaks_verification_at_that_row() {
        List<AuditEvent> chain = new ArrayList<>(
                chainOf(AuditActions.NODE_STARTED, AuditActions.NODE_FAILED, AuditActions.NODE_SUCCEEDED));

        AuditEvent tampered = chain.get(1);
        chain.set(1, new AuditEvent(tampered.runId(), tampered.seq(), tampered.timestamp(), tampered.actorType(),
                tampered.actorId(), AuditActions.NODE_SUCCEEDED, tampered.targetType(), tampered.targetId(),
                tampered.result(), tampered.reason(), tampered.policyVersion(), tampered.definitionVersion(),
                tampered.prevHash(), tampered.hash()));

        assertThat(AuditHasher.verifyChain(chain)).isEqualTo(2);
    }

    @Test
    void removing_a_row_breaks_the_link_at_the_following_row() {
        List<AuditEvent> chain = new ArrayList<>(
                chainOf(AuditActions.NODE_STARTED, AuditActions.NODE_FAILED, AuditActions.NODE_SUCCEEDED));
        chain.remove(1);

        assertThat(AuditHasher.verifyChain(chain)).isEqualTo(2);
    }

    @Test
    void an_empty_chain_is_trivially_intact() {
        assertThat(AuditHasher.verifyChain(List.of())).isEqualTo(-1);
    }
}
