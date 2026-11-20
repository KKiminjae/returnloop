package returnloop.refund.service;

import java.time.Instant;
import java.util.Optional;

import returnloop.refund.domain.Refund;
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

	public RefundWorkerTransactionService(RefundJobRepository jobRepository) {
		this.jobRepository = jobRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<RefundRequest> findPendingRequest(long jobId, Instant now) {
		return jobRepository.findById(jobId).filter(job -> job.isReady(now)).map(job -> {
			Refund refund = job.getRefund();
			return new RefundRequest(job.getOperation(), refund.getPayment().getId(),
					refund.getAmount(), refund.getIdempotencyKey());
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(long jobId, Operation operation, Result result) {
		RefundJob job = jobRepository.findById(jobId).orElseThrow();
		if (job.isPending(operation)) {
			Refund refund = job.getRefund();
			switch (result) {
				case SUCCEEDED -> {
					refund.markSucceeded();
					refund.getPayment().markRefunded();
				}
				case FAILED -> refund.markFailed();
			}
			job.complete();
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void scheduleQuery(long jobId, Instant now) {
		RefundJob job = jobRepository.findById(jobId).orElseThrow();
		if (job.isPending(Operation.EXECUTE_REFUND)) {
			job.getRefund().markUnknown();
			job.scheduleQuery(now);
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void recordUnresolvedQuery(long jobId, Instant now) {
		RefundJob job = jobRepository.findById(jobId).orElseThrow();
		if (job.isPending(Operation.QUERY_PG)) {
			job.recordUnresolvedQuery(now);
		}
	}

	public record RefundRequest(Operation operation, long paymentId, long amount, String idempotencyKey) {
	}
}
