package returnloop.refund.service;

import returnloop.payment.domain.Payment;
import returnloop.payment.domain.PaymentRepository;
import returnloop.payment.domain.PaymentStatus;
import returnloop.payment.exception.PaymentNotFoundException;
import returnloop.refund.domain.Refund;
import returnloop.refund.domain.RefundRepository;
import returnloop.refund.domain.RefundStatus;
import returnloop.refund.exception.RefundStateInconsistentException;

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
class RefundServiceIntegrationTests {

	@Container
	@ServiceConnection
	static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

	@Autowired
	RefundService service;

	@Autowired
	PaymentRepository paymentRepository;

	@Autowired
	RefundRepository refundRepository;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@BeforeEach
	void 테스트_DB의_데이터를_정리한다() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			jdbc.update("DELETE FROM refund_job");
			jdbc.update("DELETE FROM refund");
			jdbc.update("DELETE FROM payment");
		});
	}

	@Test
	void 최초_요청은_환불과_작업을_함께_저장한다() {
		Long paymentId = 결제_준비();
		assertFalse(TransactionSynchronizationManager.isActualTransactionActive());

		var result = service.createRefund(paymentId);

		assertTrue(result.created());
		assertEquals(paymentId, result.paymentId());
		assertEquals(15000L, result.amount());
		assertEquals(RefundStatus.PENDING, result.status());
		assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			Refund stored = refundRepository.findByPaymentId(paymentId).orElseThrow();
			assertEquals(result.id(), stored.getId());
			assertEquals(1, 작업_수(result.id()));
			assertEquals(result.amount(), stored.getAmount());
			assertEquals(result.status(), stored.getStatus());
			assertEquals(PaymentStatus.PAID, stored.getPayment().getStatus());
		});
	}

	@Test
	void 작업_INSERT가_실패하면_환불도_롤백된다() {
		Long paymentId = 결제_준비();
		jdbc.execute("""
				ALTER TABLE refund_job ADD CONSTRAINT ck_test_reject_refund_job CHECK (refund_id < 0)
				""");
		try {
			var error = assertThrows(DataAccessException.class, () -> service.createRefund(paymentId));
			assertTrue(error.getMostSpecificCause().getMessage().contains("ck_test_reject_refund_job"));
			new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
				assertEquals(0, refundRepository.count());
				assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM refund_job", Integer.class));
			});
		} finally {
			jdbc.execute("ALTER TABLE refund_job DROP CHECK ck_test_reject_refund_job");
		}
	}

	@Test
	void 작업이_없는_기존_환불은_재요청해도_작업을_만들지_않는다() {
		Long paymentId = 결제_준비();
		Long refundId = new TransactionTemplate(transactionManager).execute(status ->
				refundRepository.saveAndFlush(new Refund(paymentRepository.findById(paymentId).orElseThrow())).getId());

		var result = service.createRefund(paymentId);

		assertFalse(result.created());
		assertEquals(refundId, result.id());
		assertEquals(0, 작업_수(refundId));
	}

	@ParameterizedTest
	@MethodSource("기존_상태_조합")
	void 기존_환불은_결제와_환불_상태에_관계없이_같은_정보를_반환한다(
			PaymentStatus paymentStatus, RefundStatus refundStatus) {
		Long paymentId = 결제_준비();
		var first = service.createRefund(paymentId);
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			jdbc.update("UPDATE payment SET status = ? WHERE id = ?", paymentStatus.name(), paymentId);
			jdbc.update("UPDATE refund SET status = ? WHERE id = ?", refundStatus.name(), first.id());
		});
		String key = jdbc.queryForObject("SELECT idempotency_key FROM refund WHERE id = ?", String.class, first.id());

		var repeated = service.createRefund(paymentId);

		assertFalse(repeated.created());
		assertEquals(first.id(), repeated.id());
		assertEquals(paymentId, repeated.paymentId());
		assertEquals(first.amount(), repeated.amount());
		assertEquals(refundStatus, repeated.status());
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE payment_id = ?", Integer.class, paymentId));
			assertEquals(1, 작업_수(first.id()));
			assertEquals(key, jdbc.queryForObject("SELECT idempotency_key FROM refund WHERE id = ?", String.class, first.id()));
			assertEquals(paymentStatus.name(), jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId));
		});
	}

	@Test
	void 결제가_없으면_예외를_반환하고_환불을_남기지_않는다() {
		assertThrows(PaymentNotFoundException.class, () -> service.createRefund(-1L));
		new TransactionTemplate(transactionManager).executeWithoutResult(status ->
				assertEquals(0, refundRepository.count()));
	}

	@Test
	void 환불_완료_결제에_환불이_없으면_정합성_예외를_반환한다() {
		Long paymentId = 결제_준비();
		new TransactionTemplate(transactionManager).executeWithoutResult(status ->
				jdbc.update("UPDATE payment SET status = 'REFUNDED' WHERE id = ?", paymentId));

		assertThrows(RefundStateInconsistentException.class, () -> service.createRefund(paymentId));
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			assertEquals(0, refundRepository.count());
			assertEquals(PaymentStatus.REFUNDED, paymentRepository.findById(paymentId).orElseThrow().getStatus());
		});
	}

	private int 작업_수(Long refundId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM refund_job WHERE refund_id = ?", Integer.class, refundId);
	}

	private Long 결제_준비() {
		return new TransactionTemplate(transactionManager).execute(status ->
				paymentRepository.saveAndFlush(new Payment(15000L)).getId());
	}

	private static Stream<Arguments> 기존_상태_조합() {
		return Arrays.stream(PaymentStatus.values()).flatMap(paymentStatus ->
				Arrays.stream(RefundStatus.values()).map(refundStatus -> Arguments.of(paymentStatus, refundStatus)));
	}
}
