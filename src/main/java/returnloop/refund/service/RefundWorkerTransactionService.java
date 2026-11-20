package returnloop.refund.service;

import java.util.Optional;

import returnloop.refund.domain.RefundJob;
import returnloop.refund.domain.RefundJobRepository;

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
	public Optional<RefundRequest> findPendingRequest(long jobId) {
		return jobRepository.findById(jobId).filter(RefundJob::isPending).map(job -> {
			var refund = job.getRefund();
			return new RefundRequest(refund.getPayment().getId(), refund.getAmount(), refund.getIdempotencyKey());
		});
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void complete(long jobId) {
		var job = jobRepository.findById(jobId).orElseThrow();
		if (job.isPending()) {
			var refund = job.getRefund();
			refund.markSucceeded();
			refund.getPayment().markRefunded();
			job.complete();
		}
	}

	public record RefundRequest(long paymentId, long amount, String idempotencyKey) {
	}
}
