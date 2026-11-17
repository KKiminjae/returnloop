package returnloop.refund.controller;

import returnloop.refund.dto.RefundResponse;
import returnloop.refund.dto.RefundResult;
import returnloop.refund.service.RefundService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RefundController {

	private final RefundService service;

	public RefundController(RefundService service) {
		this.service = service;
	}

	@PostMapping("/payments/{paymentId}/refund")
	public ResponseEntity<RefundResponse> createRefund(@PathVariable Long paymentId) {
		RefundResult result = service.createRefund(paymentId);
		return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
				.body(RefundResponse.from(result));
	}
}
