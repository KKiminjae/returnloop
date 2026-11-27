package returnloop.refund.reconciliation;

import org.springframework.scheduling.annotation.Scheduled;

class RefundReconciliationScheduler {

	private final RefundReconciliationService reconciliation;

	RefundReconciliationScheduler(RefundReconciliationService reconciliation) {
		this.reconciliation = reconciliation;
	}

	@Scheduled(fixedDelayString = "${returnloop.refund.reconciliation.interval}", scheduler = "refundReconciliationTaskScheduler")
	void reconcile() {
		reconciliation.reconcile();
	}
}
