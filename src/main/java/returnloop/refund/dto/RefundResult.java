package returnloop.refund.dto;

import returnloop.refund.domain.Refund;
import returnloop.refund.domain.RefundStatus;

public record RefundResult(Long id, Long paymentId, Long amount, RefundStatus status, boolean created) {

	public static RefundResult from(Refund refund, boolean created) {
		return new RefundResult(refund.getId(), refund.getPayment().getId(), refund.getAmount(),
				refund.getStatus(), created);
	}
}
