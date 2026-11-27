package returnloop.refund.pg;

import returnloop.refund.pg.RefundGateway.QueryResult;

public interface RefundLedger {

	LedgerResult queryLedger(String idempotencyKey);

	record LedgerResult(QueryResult status, Long paymentId, Long amount, String idempotencyKey) {
	}
}
