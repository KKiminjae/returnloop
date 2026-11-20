package returnloop.refund.service;

import returnloop.refund.pg.RefundGateway;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class RefundWorker {

	private final RefundWorkerTransactionService transactions;
	private final RefundGateway gateway;

	public RefundWorker(RefundWorkerTransactionService transactions, RefundGateway gateway) {
		this.transactions = transactions;
		this.gateway = gateway;
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void process(long jobId) {
		var request = transactions.findPendingRequest(jobId);
		if (request.isEmpty()) {
			return;
		}

		var refund = request.get();
		switch (gateway.refund(refund.paymentId(), refund.amount(), refund.idempotencyKey())) {
			case SUCCEEDED -> transactions.complete(jobId);
		}
	}
}
