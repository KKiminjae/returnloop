package returnloop.refund.controller;

import returnloop.payment.exception.PaymentNotFoundException;
import returnloop.refund.domain.Refund;
import returnloop.refund.exception.RefundStateInconsistentException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RefundController.class)
public class RefundExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(RefundExceptionHandler.class);

	@ExceptionHandler(PaymentNotFoundException.class)
	public ResponseEntity<ErrorResponse> handlePaymentNotFound(PaymentNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(new ErrorResponse("PAYMENT_NOT_FOUND", "결제를 찾을 수 없습니다."));
	}

	@ExceptionHandler(RefundStateInconsistentException.class)
	public ResponseEntity<ErrorResponse> handleRefundStateInconsistent(RefundStateInconsistentException exception) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(new ErrorResponse("PAYMENT_REFUND_STATE_INCONSISTENT", "결제 상태와 환불 기록이 일치하지 않습니다."));
	}

	@ExceptionHandler(DataAccessException.class)
	public ResponseEntity<ErrorResponse> handleDataAccess(DataAccessException exception) {
		log.error("Refund database operation failed", exception);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ErrorResponse("INTERNAL_SERVER_ERROR", "서버 내부 오류가 발생했습니다."));
	}

	public record ErrorResponse(String code, String message) {
	}
}
