package returnloop.payment.exception;

import returnloop.payment.domain.Payment;

public class PaymentNotFoundException extends RuntimeException {

	public PaymentNotFoundException(Long paymentId) {
		super("Payment not found: " + paymentId);
	}
}
