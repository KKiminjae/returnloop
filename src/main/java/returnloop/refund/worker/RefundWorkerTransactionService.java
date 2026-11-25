package returnloop.refund.worker;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

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
	public Optional<RefundRequest> claimRequest(long jobId, Instant now, Duration leaseDuration) {
		return jobRepository.findById(jobId).flatMap(job -> {
			if (job.hasExpiredLease(now)) {
				job.recoverAsQuery();
			} else if (!job.isReady(now)) {
				return Optional.empty();
			}

			job.claim(now.plus(leaseDuration));
			Refund refund = job.getRefund();
			return Optional.of(new RefundRequest(job.getOperation(), refund.getPayment().getId(),
					refund.getAmount(), refund.getIdempotencyKey()));
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(long jobId, Operation operation, Result result, Instant attemptedAt) {
		RefundJob job = jobRepository.findById(jobId).orElseThrow();
		if (job.isProcessing(operation)) {
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
	public void scheduleQuery(long jobId, Instant attemptedAt, Instant now) {
		RefundJob job = jobRepository.findById(jobId).orElseThrow();
		if (job.isProcessing(Operation.EXECUTE_REFUND)) {
			job.getRefund().markUnknown();
			job.scheduleQuery(now);
			attemptRepository.save(new RefundAttempt(job.getRefund(), Operation.EXECUTE_REFUND, Outcome.TIMEOUT, attemptedAt));
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordUnresolvedQuery(long jobId, Outcome outcome, Instant attemptedAt, Instant now) {
		RefundJob job = jobRepository.findById(jobId).orElseThrow();
		if (job.isProcessing(Operation.QUERY_PG)) {
			job.recordUnresolvedQuery(now);
			attemptRepository.save(new RefundAttempt(job.getRefund(), Operation.QUERY_PG, outcome, attemptedAt));
		}
	}

	public record RefundRequest(Operation operation, long paymentId, long amount, String idempotencyKey) {
	}
}
