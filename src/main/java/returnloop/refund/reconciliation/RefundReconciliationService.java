package returnloop.refund.reconciliation;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import returnloop.refund.domain.RefundJobRepository;
import returnloop.refund.pg.RefundLedger;
import returnloop.refund.pg.RefundGateway.QueryResult;

public class RefundReconciliationService {

	private static final Logger logger = LoggerFactory.getLogger(RefundReconciliationService.class);

	private final RefundJobRepository jobs;
	private final RefundLedger ledger;
	private final RefundReconciliationTransactionService transactions;
	private final Clock clock;

	public RefundReconciliationService(RefundJobRepository jobs, RefundLedger ledger,
			RefundReconciliationTransactionService transactions, Clock clock) {
		this.jobs = jobs;
		this.ledger = ledger;
		this.transactions = transactions;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public void reconcile() {
		long maxJobId = jobs.findMaxJobId();
		long afterId = 0;
		while (afterId < maxJobId) {
			var candidates = jobs.findReconciliationCandidates(afterId, maxJobId);
			if (candidates.isEmpty()) {
				return;
			}
			for (var candidate : candidates) {
				long jobId = candidate.getJobId();
				try {
					var record = ledger.queryLedger(candidate.getIdempotencyKey());
					if (record.status() == QueryResult.UNAVAILABLE) {
						logger.warn("PG ledger unavailable for refund job {}", jobId);
					} else {
						transactions.apply(jobId, record, clock.instant());
					}
				} catch (RuntimeException exception) {
					logger.error("Reconciliation failed for refund job {}", jobId, exception);
				}
			}
			afterId = candidates.getLast().getJobId();
		}
	}
}
