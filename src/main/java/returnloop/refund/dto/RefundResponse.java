package returnloop.refund.dto;

import returnloop.refund.domain.RefundStatus;

public record RefundResponse(Long id, Long paymentId, Long amount, RefundStatus status) {

	public static RefundResponse from(RefundResult result) {
		return new RefundResponse(result.id(), result.paymentId(), result.amount(), result.status());
	}
}
