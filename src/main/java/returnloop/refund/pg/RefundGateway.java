package returnloop.refund.pg;

public interface RefundGateway {

	Result refund(long paymentId, long amount, String idempotencyKey);

	QueryResult query(String idempotencyKey);

	enum Result {
		SUCCEEDED, FAILED
	}

	enum QueryResult {
		SUCCEEDED, FAILED, NOT_FOUND, UNAVAILABLE
	}

	class TimeoutException extends RuntimeException {

		public TimeoutException(String message) {
			super(message);
		}
	}
}
