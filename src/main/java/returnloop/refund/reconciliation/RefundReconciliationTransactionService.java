package returnloop.refund.reconciliation;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import returnloop.payment.domain.PaymentStatus;
import returnloop.refund.domain.Refund;
import returnloop.refund.domain.RefundAttempt;
import returnloop.refund.domain.RefundAttempt.Outcome;
import returnloop.refund.domain.RefundAttemptRepository;
import returnloop.refund.domain.RefundAttempt.Operation;
import returnloop.refund.domain.RefundJobRepository;
import returnloop.refund.domain.RefundStatus;
import returnloop.refund.pg.RefundLedger.LedgerResult;
import returnloop.refund.pg.RefundGateway.QueryResult;

@Service
public class RefundReconciliationTransactionService {

	private final RefundJobRepository jobs;
	private final RefundAttemptRepository attempts;
	private final JdbcTemplate jdbc;

	public RefundReconciliationTransactionService(RefundJobRepository jobs, RefundAttemptRepository attempts,
			JdbcTemplate jdbc) {
		this.jobs = jobs;
		this.attempts = attempts;
		this.jdbc = jdbc;
	}

	@Transactional
	public void apply(long jobId, LedgerResult record, Instant observedAt) {
		var job = jobs.findByIdForUpdate(jobId).orElseThrow();
		if (!job.isCompleted() && !job.needsReview()) {
			return;
		}
		var refund = job.getRefund();
		var status = refund.getStatus();
		if (record.status() == QueryResult.NOT_FOUND) {
			if (status == RefundStatus.SUCCEEDED) {
				recordIssue(refund, record, "MISSING_PG_RECORD", observedAt);
			}
			return;
		}
		if (record.status() != QueryResult.SUCCEEDED && record.status() != QueryResult.FAILED) {
			return;
		}

		boolean paymentMatches = Objects.equals(refund.getPayment().getId(), record.paymentId());
		boolean amountMatches = Objects.equals(refund.getAmount(), record.amount());
		boolean keyMatches = refund.getIdempotencyKey().equals(record.idempotencyKey());
		if (!paymentMatches) {
			recordIssue(refund, record, "PAYMENT_ID_MISMATCH", observedAt);
		}
		if (!amountMatches) {
			recordIssue(refund, record, "AMOUNT_MISMATCH", observedAt);
		}
		if (!keyMatches) {
			recordIssue(refund, record, "IDEMPOTENCY_KEY_MISMATCH", observedAt);
		}
		boolean pgSucceeded = record.status() == QueryResult.SUCCEEDED;
		if ((status == RefundStatus.SUCCEEDED && !pgSucceeded) || (status == RefundStatus.FAILED && pgSucceeded)) {
			recordIssue(refund, record, "STATUS_CONFLICT", observedAt);
		}
		if (job.needsReview() && status == RefundStatus.UNKNOWN
				&& paymentMatches && amountMatches && keyMatches) {
			if (refund.getPayment().getStatus() != PaymentStatus.PAID) {
				recordIssue(refund, record, "PAYMENT_STATUS_CONFLICT", observedAt);
				return;
			}
			Outcome outcome;
			if (pgSucceeded) {
				refund.markSucceeded();
				refund.getPayment().markRefunded();
				outcome = Outcome.SUCCEEDED;
			} else {
				refund.markFailed();
				outcome = Outcome.FAILED;
			}
			job.completeAfterReconciliation();
			attempts.save(new RefundAttempt(refund, Operation.RECONCILE, outcome, observedAt));
		}
	}

	private void recordIssue(Refund refund, LedgerResult record, String type, Instant observedAt) {
		var observed = LocalDateTime.ofInstant(observedAt, ZoneOffset.UTC);
		jdbc.update("""
				INSERT INTO refund_reconciliation_issue
				    (refund_id, mismatch_type, internal_status, internal_payment_status, pg_status, internal_payment_id,
				     pg_payment_id, internal_amount, pg_amount, internal_idempotency_key, pg_idempotency_key,
				     first_detected_at, last_detected_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) AS observation
				ON DUPLICATE KEY UPDATE
				    internal_status = IF(observation.last_detected_at >= refund_reconciliation_issue.last_detected_at,
				        observation.internal_status, refund_reconciliation_issue.internal_status),
				    internal_payment_status = IF(observation.last_detected_at >= refund_reconciliation_issue.last_detected_at,
				        observation.internal_payment_status, refund_reconciliation_issue.internal_payment_status),
				    pg_status = IF(observation.last_detected_at >= refund_reconciliation_issue.last_detected_at,
				        observation.pg_status, refund_reconciliation_issue.pg_status),
				    pg_payment_id = IF(observation.last_detected_at >= refund_reconciliation_issue.last_detected_at,
				        observation.pg_payment_id, refund_reconciliation_issue.pg_payment_id),
				    pg_amount = IF(observation.last_detected_at >= refund_reconciliation_issue.last_detected_at,
				        observation.pg_amount, refund_reconciliation_issue.pg_amount),
				    pg_idempotency_key = IF(observation.last_detected_at >= refund_reconciliation_issue.last_detected_at,
				        observation.pg_idempotency_key, refund_reconciliation_issue.pg_idempotency_key),
				    first_detected_at = LEAST(refund_reconciliation_issue.first_detected_at, observation.first_detected_at),
				    last_detected_at = GREATEST(refund_reconciliation_issue.last_detected_at, observation.last_detected_at)
				""", refund.getId(), type, refund.getStatus().name(), refund.getPayment().getStatus().name(),
				record.status().name(), refund.getPayment().getId(), record.paymentId(), refund.getAmount(),
				record.amount(), refund.getIdempotencyKey(), record.idempotencyKey(), observed, observed);
	}
}
