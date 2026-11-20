package returnloop.refund.pg;

public interface RefundGateway {

	Result refund(long paymentId, long amount, String idempotencyKey);

	enum Result {
		SUCCEEDED
	}
}
