package returnloop.refund.exception;

import returnloop.refund.domain.Refund;

public class RefundStateInconsistentException extends RuntimeException {

	public RefundStateInconsistentException(Long paymentId) {
		super("Refund is missing for refunded payment: " + paymentId);
	}
}
