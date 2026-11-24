package returnloop.refund.worker;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import returnloop.refund.domain.RefundJob.Operation;
import returnloop.refund.domain.RefundAttempt.Outcome;
import returnloop.refund.pg.RefundGateway;
import returnloop.refund.pg.RefundGateway.QueryResult;
import returnloop.refund.pg.RefundGateway.Result;
import returnloop.refund.pg.RefundGateway.TimeoutException;
import returnloop.refund.worker.RefundWorkerTransactionService.RefundRequest;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

public class RefundWorker {

	private final RefundWorkerTransactionService transactions;
	private final RefundGateway gateway;
	private final Clock clock;

	public RefundWorker(RefundWorkerTransactionService transactions, RefundGateway gateway, Clock clock) {
		this.transactions = transactions;
		this.gateway = gateway;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void process(long jobId) {
		Optional<RefundRequest> request = transactions.findPendingRequest(jobId, clock.instant());
		if (request.isEmpty()) {
			return;
		}

		RefundRequest refund = request.get();
		switch (refund.operation()) {
			case EXECUTE_REFUND -> execute(jobId, refund);
			case QUERY_PG -> query(jobId, refund.idempotencyKey());
		}
	}

	private void execute(long jobId, RefundRequest refund) {
		Result result;
		Instant attemptedAt = clock.instant();
		try {
			result = gateway.refund(refund.paymentId(), refund.amount(), refund.idempotencyKey());
		} catch (TimeoutException exception) {
			transactions.scheduleQuery(jobId, attemptedAt, clock.instant());
			return;
		}
		transactions.complete(jobId, Operation.EXECUTE_REFUND, result, attemptedAt);
	}

	private void query(long jobId, String idempotencyKey) {
		QueryResult result;
		Instant attemptedAt = clock.instant();
		try {
			result = gateway.query(idempotencyKey);
		} catch (TimeoutException exception) {
			result = QueryResult.UNAVAILABLE;
		}
		switch (result) {
			case SUCCEEDED -> transactions.complete(jobId, Operation.QUERY_PG, Result.SUCCEEDED, attemptedAt);
			case FAILED -> transactions.complete(jobId, Operation.QUERY_PG, Result.FAILED, attemptedAt);
			case NOT_FOUND -> transactions.recordUnresolvedQuery(jobId, Outcome.NOT_FOUND, attemptedAt, clock.instant());
			case UNAVAILABLE -> transactions.recordUnresolvedQuery(jobId, Outcome.UNAVAILABLE, attemptedAt, clock.instant());
		}
	}
}
