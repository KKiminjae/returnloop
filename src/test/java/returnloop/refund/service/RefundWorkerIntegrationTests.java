package returnloop.refund.service;

import returnloop.payment.domain.Payment;
import returnloop.payment.domain.PaymentRepository;
import returnloop.refund.pg.RefundGateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Testcontainers
@Import(RefundWorker.class)
class RefundWorkerIntegrationTests {

	@Container
	@ServiceConnection
	static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

	@Autowired
	RefundService refundService;

	@Autowired
	RefundWorker worker;

	@Autowired
	PaymentRepository paymentRepository;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoBean
	RefundGateway gateway;

	@BeforeEach
	void 테스트_DB를_정리한다() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			jdbc.update("DELETE FROM refund_job");
			jdbc.update("DELETE FROM refund");
			jdbc.update("DELETE FROM payment");
		});
	}

	@Test
	void 트랜잭션_밖에서_PG를_호출하고_성공_결과를_함께_커밋한다() {
		var target = 작업_준비();
		PG_성공_응답_준비(target);

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
			worker.process(target.jobId());
			assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
		});
		worker.process(target.jobId());

		verify(gateway, times(1)).refund(target.paymentId(), 15000L, target.key());
		저장_상태_확인(target, "SUCCEEDED", "REFUNDED", "COMPLETED");
	}

	@Test
	void 작업_완료_갱신이_거부되면_성공_결과가_커밋되지_않는다() {
		var target = 작업_준비();
		PG_성공_응답_준비(target);

		jdbc.execute("""
				ALTER TABLE refund_job ADD CONSTRAINT ck_test_reject_job_completion
				CHECK (status <> 'COMPLETED')
				""");
		try {
			var error = assertThrows(DataAccessException.class, () -> worker.process(target.jobId()));

			assertTrue(error.getMostSpecificCause().getMessage().contains("ck_test_reject_job_completion"));
			verify(gateway).refund(target.paymentId(), 15000L, target.key());
			저장_상태_확인(target, "PENDING", "PAID", "PENDING");
		} finally {
			jdbc.execute("ALTER TABLE refund_job DROP CHECK ck_test_reject_job_completion");
		}
	}

	private 작업대상 작업_준비() {
		Long paymentId = new TransactionTemplate(transactionManager).execute(status ->
				paymentRepository.saveAndFlush(new Payment(15000L)).getId());
		var refund = refundService.createRefund(paymentId);
		Long jobId = jdbc.queryForObject("SELECT id FROM refund_job WHERE refund_id = ?", Long.class, refund.id());
		String key = jdbc.queryForObject("SELECT idempotency_key FROM refund WHERE id = ?", String.class, refund.id());
		return new 작업대상(paymentId, refund.id(), jobId, key);
	}

	private void PG_성공_응답_준비(작업대상 target) {
		when(gateway.refund(target.paymentId(), 15000L, target.key())).thenAnswer(invocation -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			return RefundGateway.Result.SUCCEEDED;
		});
	}

	private void 저장_상태_확인(작업대상 target, String refundStatus, String paymentStatus, String jobStatus) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			assertEquals(refundStatus, jdbc.queryForObject(
					"SELECT status FROM refund WHERE id = ?", String.class, target.refundId()));
			assertEquals(paymentStatus, jdbc.queryForObject(
					"SELECT status FROM payment WHERE id = ?", String.class, target.paymentId()));
			assertEquals(jobStatus, jdbc.queryForObject(
					"SELECT status FROM refund_job WHERE id = ?", String.class, target.jobId()));
		});
	}

	private record 작업대상(long paymentId, long refundId, long jobId, String key) {
	}
}
