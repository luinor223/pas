package com.abclogistics.pas.esign;

import com.abclogistics.pas.common.error.FailedPreconditionException;
import com.abclogistics.pas.esign.domain.SigningSession;
import com.abclogistics.pas.esign.domain.SigningSession.SessionStatus;
import com.abclogistics.pas.esign.domain.StatusHistory.TriggerKind;
import com.abclogistics.pas.esign.service.StatusTransitionService;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transition seam validates against the legal edge table (registry §9). Every real transition
 * routes through it, so an illegal edge (or the right target under the wrong trigger) is refused
 * before the column or the timeline moves.
 */
class StatusTransitionServiceTest {

    private final StatusTransitionService transitions = new StatusTransitionService();

    @Test
    void aLegalEdgeMovesTheColumnAndAppendsOneHistoryRow() {
        SigningSession s = session(SessionStatus.PENDING_SEND);

        transitions.transition(s, SessionStatus.SIGNING, TriggerKind.S, null, "System", "sent");

        assertThat(s.getStatus()).isEqualTo(SessionStatus.SIGNING);
        assertThat(s.getStatusHistory()).hasSize(1);
        assertThat(s.getStatusHistory().get(0).getFromStatus()).isEqualTo("PENDING_SEND");
        assertThat(s.getStatusHistory().get(0).getToStatus()).isEqualTo("SIGNING");
    }

    @Test
    void anEdgeOutOfATerminalStatusIsRejected() {
        SigningSession s = session(SessionStatus.SIGNED);

        assertThatThrownBy(() -> transitions.transition(s, SessionStatus.CANCELLED, TriggerKind.U,
                UUID.randomUUID(), "Ops", "too late"))
                .isInstanceOf(FailedPreconditionException.class);
        assertThat(s.getStatus()).isEqualTo(SessionStatus.SIGNED);   // unchanged
        assertThat(s.getStatusHistory()).isEmpty();                  // no row written
    }

    @Test
    void theRightTargetUnderTheWrongTriggerIsRejected() {
        // PENDING_SEND -> SIGNED is legal only under a provider callback (E), not a user action (U).
        SigningSession s = session(SessionStatus.PENDING_SEND);

        assertThatThrownBy(() -> transitions.transition(s, SessionStatus.SIGNED, TriggerKind.U,
                null, "System", "nope"))
                .isInstanceOf(FailedPreconditionException.class);
    }

    @Test
    void aCallbackMayCompleteEvenBeforeTheSendCommits() {
        // The PENDING_SEND -> SIGNED (E) edge exists so a callback that overtakes our send is applied.
        SigningSession s = session(SessionStatus.PENDING_SEND);

        transitions.transition(s, SessionStatus.SIGNED, TriggerKind.E, null, "System", "callback");

        assertThat(s.getStatus()).isEqualTo(SessionStatus.SIGNED);
    }

    @Test
    void openWritesTheCreationRowWithoutAPriorStatus() {
        SigningSession s = session(SessionStatus.PENDING_SEND);

        transitions.open(s, UUID.randomUUID(), "Sales", "Session created");

        assertThat(s.getStatusHistory()).hasSize(1);
        assertThat(s.getStatusHistory().get(0).getFromStatus()).isNull();
        assertThat(s.getStatusHistory().get(0).getToStatus()).isEqualTo("PENDING_SEND");
    }

    private static SigningSession session(SessionStatus status) {
        SigningSession s = SigningSession.create("CONTRACT", UUID.randomUUID(), "HD-1", "ACME",
                "Signer", "s@acme.vn", UUID.randomUUID(), UUID.randomUUID(), "Req");
        s.setSessionNo("SIG-1");
        s.setStatus(status);
        return s;
    }
}
