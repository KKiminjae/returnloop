package returnloop.refund.service;

import returnloop.refund.dto.RefundResult;

import returnloop.refund.domain.RefundStatus;

import java.sql.SQLException;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundServiceTests {

	private final RefundTransactionService transactions = mock(RefundTransactionService.class);
	private final RefundService service = new RefundService(transactions);

	@ParameterizedTest
	@ValueSource(strings = {"uk_refund_payment_id", "refund.uk_refund_payment_id"})
	void 결제별_환불_중복이면_생성_실패_이후_기존_환불을_조회한다(String constraintName) {
		var exception = 무결성_예외(1062, constraintName);
		var existing = new RefundResult(1L, 10L, 15000L, RefundStatus.PENDING, false);
		when(transactions.createOrFind(10L)).thenThrow(exception);
		when(transactions.findExisting(10L)).thenReturn(Optional.of(existing));

		assertEquals(existing, service.createRefund(10L));
		var order = inOrder(transactions);
		order.verify(transactions).createOrFind(10L);
		order.verify(transactions).findExisting(10L);
	}

	@ParameterizedTest
	@ValueSource(strings = {"uk_refund_idempotency_key", "fk_refund_payment", "other_uk_refund_payment_id"})
	void 다른_제약_위반은_중복_환불로_숨기지_않는다(String constraintName) {
		var exception = 무결성_예외(1062, constraintName);
		when(transactions.createOrFind(10L)).thenThrow(exception);

		assertSame(exception, assertThrows(DataIntegrityViolationException.class, () -> service.createRefund(10L)));
		verify(transactions, never()).findExisting(anyLong());
	}

	@Test
	void 중복_키_오류가_아니면_기존_환불을_조회하지_않는다() {
		var exception = 무결성_예외(1452, "uk_refund_payment_id");
		when(transactions.createOrFind(10L)).thenThrow(exception);

		assertSame(exception, assertThrows(DataIntegrityViolationException.class, () -> service.createRefund(10L)));
		verify(transactions, never()).findExisting(anyLong());
	}

	@Test
	void 제약_이름을_알_수_없는_오류는_그대로_전파한다() {
		var exception = 무결성_예외(1062, null);
		when(transactions.createOrFind(10L)).thenThrow(exception);

		assertSame(exception, assertThrows(DataIntegrityViolationException.class, () -> service.createRefund(10L)));
		verify(transactions, never()).findExisting(anyLong());
	}

	@Test
	void 충돌_후_기존_환불도_찾지_못하면_원래_오류를_전파한다() {
		var exception = 무결성_예외(1062, "uk_refund_payment_id");
		when(transactions.createOrFind(10L)).thenThrow(exception);
		when(transactions.findExisting(10L)).thenReturn(Optional.empty());

		assertSame(exception, assertThrows(DataIntegrityViolationException.class, () -> service.createRefund(10L)));
	}

	private DataIntegrityViolationException 무결성_예외(int errorCode, String constraintName) {
		var sqlException = new SQLException("Constraint violation", "23000", errorCode);
		return new DataIntegrityViolationException("Integrity violation",
				new ConstraintViolationException("Constraint violation", sqlException, constraintName));
	}
}
