package returnloop.persistence;

import returnloop.payment.domain.Payment;
import returnloop.payment.domain.PaymentRepository;
import returnloop.payment.domain.PaymentStatus;
import returnloop.refund.domain.Refund;
import returnloop.refund.domain.RefundRepository;
import returnloop.refund.domain.RefundStatus;

import java.util.UUID;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@Transactional
class PaymentRefundRepositoryTests {

	@Container
	@ServiceConnection
	static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

	@Autowired
	PaymentRepository paymentRepository;

	@Autowired
	RefundRepository refundRepository;

	@Autowired
	EntityManager entityManager;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 결제를_저장하고_DB에서_다시_조회한다() {
		Payment payment = paymentRepository.saveAndFlush(new Payment(15000L));
		Long paymentId = payment.getId();
		entityManager.clear();

		Payment loaded = paymentRepository.findById(paymentId).orElseThrow();
		assertNotSame(payment, loaded);
		assertEquals(15000L, loaded.getAmount());
		assertEquals(PaymentStatus.PAID, loaded.getStatus());
		assertEquals("PAID", jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId));
	}

	@Test
	void 환불은_원본_금액과_초기_상태와_멱등_키를_저장하고_유지한다() {
		Payment payment = paymentRepository.saveAndFlush(new Payment(27000L));
		Refund refund = refundRepository.saveAndFlush(new Refund(payment));
		Long refundId = refund.getId();
		Long paymentId = payment.getId();
		String key = refund.getIdempotencyKey();
		assertEquals(key, UUID.fromString(key).toString());
		entityManager.clear();

		Refund loaded = refundRepository.findByPaymentId(paymentId).orElseThrow();
		assertNotSame(refund, loaded);
		assertEquals(refundId, loaded.getId());
		assertEquals(paymentId, loaded.getPayment().getId());
		assertEquals(27000L, loaded.getAmount());
		assertEquals(RefundStatus.PENDING, loaded.getStatus());
		assertEquals(key, loaded.getIdempotencyKey());
		assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM refund WHERE id = ?", String.class, refundId));
		entityManager.flush();
		entityManager.clear();
		assertEquals(key, refundRepository.findById(refundId).orElseThrow().getIdempotencyKey());
	}

	@Test
	void 환불의_결제_참조는_필요할_때_조회한다() {
		Payment payment = paymentRepository.saveAndFlush(new Payment(15000L));
		Long refundId = refundRepository.saveAndFlush(new Refund(payment)).getId();
		entityManager.clear();

		Refund loaded = refundRepository.findById(refundId).orElseThrow();
		var persistenceUnitUtil = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
		assertFalse(persistenceUnitUtil.isLoaded(loaded, "payment"));
		assertEquals(15000L, loaded.getPayment().getAmount());
		assertTrue(persistenceUnitUtil.isLoaded(loaded, "payment"));
	}

	@Test
	void 환불이_없는_결제는_빈_조회_결과를_반환한다() {
		Payment payment = paymentRepository.saveAndFlush(new Payment(15000L));
		assertTrue(refundRepository.findByPaymentId(payment.getId()).isEmpty());
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1})
	void 양수가_아닌_금액으로는_결제를_생성할_수_없다(long amount) {
		assertThrows(IllegalArgumentException.class, () -> new Payment(amount));
	}

	@Test
	void 결제_없이_환불을_생성할_수_없다() {
		assertThrows(NullPointerException.class, () -> new Refund(null));
	}

	@Test
	void JPA로도_동일한_결제의_중복_환불을_저장할_수_없다() {
		Payment payment = paymentRepository.saveAndFlush(new Payment(15000L));
		refundRepository.saveAndFlush(new Refund(payment));

		DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class,
				() -> refundRepository.saveAndFlush(new Refund(payment)));
		assertTrue(exception.getMostSpecificCause().getMessage().contains("uk_refund_payment_id"));
	}
}
