package returnloop.refund.worker;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import returnloop.refund.domain.Refund;
import returnloop.refund.domain.RefundAttempt;
import returnloop.refund.domain.RefundAttempt.Outcome;
import returnloop.refund.domain.RefundAttemptRepository;
import returnloop.refund.domain.RefundJob;
import returnloop.refund.domain.RefundJob.Operation;
import returnloop.refund.domain.RefundJobRepository;
import returnloop.refund.pg.RefundGateway.Result;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundWorkerTransactionService {

	private final RefundJobRepository jobRepository;
	private final RefundAttemptRepository attemptRepository;

	public RefundWorkerTransactionService(RefundJobRepository jobRepository, RefundAttemptRepository attemptRepository) {
		this.jobRepository = jobRepository;
		this.attemptRepository = attemptRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<RefundRequest> claimRequest(long jobId, Clock clock, Duration leaseDuration) {
		return jobRepository.findByIdForUpdate(jobId).flatMap(job -> {
			Instant now = clock.instant();
			if (job.hasExpiredLease(now)) {
				job.recoverAsQuery();
			} else if (!job.isReady(now)) {
				return Optional.empty();
			}

			String token = UUID.randomUUID().toString();
			job.claim(now.plus(leaseDuration), token);
			Refund refund = job.getRefund();
			return Optional.of(new RefundRequest(job.getOperation(), refund.getPayment().getId(),
					refund.getAmount(), refund.getIdempotencyKey(), token));
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(long jobId, String token, Operation operation, Result result, Instant attemptedAt) {
		RefundJob job = jobRepository.findByIdForUpdate(jobId).orElseThrow();
		if (job.ownsLease(operation, token)) {
			Refund refund = job.getRefund();
			switch (result) {
				case SUCCEEDED -> {
					refund.markSucceeded();
					refund.getPayment().markRefunded();
				}
				case FAILED -> refund.markFailed();
			}
			job.complete();
			Outcome outcome = result == Result.SUCCEEDED ? Outcome.SUCCEEDED : Outcome.FAILED;
			attemptRepository.save(new RefundAttempt(refund, operation, outcome, attemptedAt));
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void scheduleQuery(long jobId, String token, Instant attemptedAt, Instant now) {
		RefundJob job = jobRepository.findByIdForUpdate(jobId).orElseThrow();
		if (job.ownsLease(Operation.EXECUTE_REFUND, token)) {
			job.getRefund().markUnknown();
			job.scheduleQuery(now);
			attemptRepository.save(new RefundAttempt(job.getRefund(), Operation.EXECUTE_REFUND, Outcome.TIMEOUT, attemptedAt));
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordUnresolvedQuery(long jobId, String token, Outcome outcome, Instant attemptedAt, Instant now) {
		RefundJob job = jobRepository.findByIdForUpdate(jobId).orElseThrow();
		if (job.ownsLease(Operation.QUERY_PG, token)) {
			job.recordUnresolvedQuery(now);
			attemptRepository.save(new RefundAttempt(job.getRefund(), Operation.QUERY_PG, outcome, attemptedAt));
		}
	}

	public record RefundRequest(Operation operation, long paymentId, long amount, String idempotencyKey, String leaseToken) {
	}
}
