package returnloop.refund.pg;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import returnloop.payment.domain.Payment;
import returnloop.payment.domain.PaymentRepository;
import returnloop.refund.domain.Refund;
import returnloop.refund.domain.RefundJob;
import returnloop.refund.domain.RefundJobRepository;
import returnloop.refund.domain.RefundRepository;
import returnloop.refund.service.RefundService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "returnloop.refund.worker.enabled=true")
@Testcontainers
@Import(RefundJobSchedulerIntegrationTests.PgConfiguration.class)
class RefundJobSchedulerIntegrationTests {

	@Container
	@ServiceConnection
	static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

	@Autowired
	PaymentRepository payments;

	@Autowired
	RefundService refunds;

	@Autowired
	RefundRepository refundRepository;

	@Autowired
	RefundJobRepository jobs;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	MockPgServer pg;

	@Test
	void 스케줄러가_실행_가능한_작업만_HTTP로_처리한다() throws InterruptedException {
		int previousRequests = pg.환불_요청_횟수();
		long futureRefund = transactions.execute(status -> {
			Payment payment = payments.save(new Payment(15000L));
			Refund refund = refundRepository.save(new Refund(payment));
			jobs.saveAndFlush(new RefundJob(refund));
			long id = refund.getId();
			jdbc.update("UPDATE refund_job SET next_run_at = '2099-01-01 00:00:00' WHERE refund_id = ?", id);
			return id;
		});
		long readyRefund = 환불을_접수한다();

		작업_완료를_기다린다(readyRefund);
		assertEquals("SUCCEEDED", jdbc.queryForObject(
				"SELECT status FROM refund WHERE id = ?", String.class, readyRefund));
		assertEquals("PENDING", 작업_상태를_읽는다(futureRefund));
		assertEquals(previousRequests + 1, pg.환불_요청_횟수());
	}

	@Test
	void 스케줄러가_HTTP_응답_유실_후_같은_키로_환불을_복구한다() throws InterruptedException {
		int previousRequests = pg.환불_요청_횟수();
		int previousExecutions = pg.환불_실행_횟수();
		int previousQueries = pg.조회_횟수();
		long paymentId = payments.saveAndFlush(new Payment(15000L)).getId();
		pg.다음_환불_응답을_보류한다();

		try {
			long refundId = refunds.createRefund(paymentId).id();
			작업_완료를_기다린다(refundId);

			assertEquals("SUCCEEDED", jdbc.queryForObject(
					"SELECT status FROM refund WHERE id = ?", String.class, refundId));
			assertEquals("REFUNDED", jdbc.queryForObject(
					"SELECT status FROM payment WHERE id = ?", String.class, paymentId));
			String key = jdbc.queryForObject(
					"SELECT idempotency_key FROM refund WHERE id = ?", String.class, refundId);
			assertTrue(pg.환불이_기록되었다(key));
			assertEquals(key, pg.마지막_조회_키());
			assertEquals(List.of("EXECUTE_REFUND:TIMEOUT", "QUERY_PG:SUCCEEDED"), jdbc.query("""
					SELECT operation, outcome FROM refund_attempt WHERE refund_id = ? ORDER BY id
					""", (row, index) -> row.getString("operation") + ":" + row.getString("outcome"), refundId));
			assertEquals(previousRequests + 1, pg.환불_요청_횟수());
			assertEquals(previousExecutions + 1, pg.환불_실행_횟수());
			assertEquals(previousQueries + 1, pg.조회_횟수());
		} finally {
			pg.응답_보류를_해제한다();
		}
	}

	private void 작업_완료를_기다린다(long refundId) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		String status = 작업_상태를_읽는다(refundId);
		while (!"COMPLETED".equals(status) && System.nanoTime() < deadline) {
			Thread.sleep(50);
			status = 작업_상태를_읽는다(refundId);
		}
		assertEquals("COMPLETED", status);
	}

	private long 환불을_접수한다() {
		long paymentId = payments.saveAndFlush(new Payment(15000L)).getId();
		return refunds.createRefund(paymentId).id();
	}

	private String 작업_상태를_읽는다(long refundId) {
		return jdbc.queryForObject("SELECT status FROM refund_job WHERE refund_id = ?", String.class, refundId);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class PgConfiguration {

		@Bean
		MockPgServer mockPgServer() throws IOException {
			return new MockPgServer();
		}

		@Bean
		DynamicPropertyRegistrar pgAddress(MockPgServer server) {
			return registry -> registry.add("returnloop.refund.worker.pg-base-url", () -> server.주소().toString());
		}
	}
}
