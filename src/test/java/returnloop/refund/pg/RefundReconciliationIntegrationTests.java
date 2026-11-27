package returnloop.refund.pg;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import returnloop.ReturnloopApplication;
import returnloop.payment.domain.Payment;
import returnloop.payment.domain.PaymentRepository;
import returnloop.refund.domain.RefundJobRepository;
import returnloop.refund.reconciliation.RefundReconciliationService;
import returnloop.refund.reconciliation.RefundReconciliationTransactionService;
import returnloop.refund.service.RefundService;
import returnloop.refund.worker.RefundWorker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Testcontainers
class RefundReconciliationIntegrationTests {

	@Container
	@ServiceConnection
	static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

	private static final Instant START = Instant.parse("2026-11-26T00:00:00Z");

	@Autowired
	PaymentRepository payments;

	@Autowired
	RefundService refunds;

	@Autowired
	RefundJobRepository jobs;

	@Autowired
	RefundReconciliationTransactionService transactions;

	@Autowired
	JdbcTemplate jdbc;

	@AfterEach
	void 환불_데이터를_정리한다() {
		jdbc.update("DELETE FROM refund_reconciliation_issue");
		jdbc.update("DELETE FROM refund_attempt");
		jdbc.update("DELETE FROM refund_job");
		jdbc.update("DELETE FROM refund");
		jdbc.update("DELETE FROM payment");
	}

	@Test
	void 한_번의_대사에서_45건을_조회하고_마지막_페이지의_환불까지_복구한다() throws Exception {
		try (var server = new MockPgServer()) {
			var gateway = new HttpRefundGateway(server.주소(), Duration.ofSeconds(2));
			for (int index = 0; index < 45; index++) {
				var target = 환불을_준비한다("UNKNOWN", "NEEDS_REVIEW", "PAID");
				gateway.refund(target.paymentId(), 15000, target.key());
			}
			환불을_준비한다("PENDING", "PENDING", "PAID");

			대사를_실행한다(gateway, START);

			assertEquals(45, server.조회_횟수());
			assertEquals(45, server.환불_요청_횟수());
			assertEquals(45, jdbc.queryForObject("""
					SELECT COUNT(*) FROM refund r JOIN payment p ON p.id = r.payment_id
					JOIN refund_job j ON j.refund_id = r.id JOIN refund_attempt a ON a.refund_id = r.id
					WHERE r.status = 'SUCCEEDED' AND p.status = 'REFUNDED' AND j.status = 'COMPLETED'
					  AND j.query_count = 3 AND a.operation = 'RECONCILE' AND a.outcome = 'SUCCEEDED'
					""", Integer.class));

			대사를_실행한다(gateway, START.plusSeconds(60));

			assertEquals(45, server.환불_요청_횟수());
			assertEquals(45, jdbc.queryForObject("SELECT COUNT(*) FROM refund_attempt", Integer.class));
		}
	}

	@Test
	void 일치하는_PG_최종_실패를_확정하고_재요청에는_기존_환불을_반환한다() throws Exception {
		try (var server = new MockPgServer()) {
			var gateway = new HttpRefundGateway(server.주소(), Duration.ofSeconds(2));
			var target = 환불을_준비한다("UNKNOWN", "NEEDS_REVIEW", "PAID");
			var mismatch = 환불을_준비한다("UNKNOWN", "NEEDS_REVIEW", "PAID");
			server.환불_실패를_기록한다(target.paymentId(), 15000, target.key());
			server.환불_실패를_기록한다(mismatch.paymentId(), 10000, mismatch.key());

			대사를_실행한다(gateway, START);
			대사를_실행한다(gateway, START.plusSeconds(60));

			var repeated = refunds.createRefund(target.paymentId());
			assertEquals(target.refundId(), repeated.id());
			assertEquals(0, server.환불_요청_횟수());
			assertEquals(1, jdbc.queryForObject("""
					SELECT COUNT(*) FROM refund r JOIN payment p ON p.id = r.payment_id
					JOIN refund_job j ON j.refund_id = r.id JOIN refund_attempt a ON a.refund_id = r.id
					WHERE r.id = ? AND r.status = 'FAILED' AND p.status = 'PAID' AND j.status = 'COMPLETED'
					  AND j.query_count = 3 AND a.operation = 'RECONCILE' AND a.outcome = 'FAILED'
					""", Integer.class, target.refundId()));
			assertEquals(1, jdbc.queryForObject("""
					SELECT COUNT(*) FROM refund r JOIN refund_job j ON j.refund_id = r.id
					JOIN refund_reconciliation_issue i ON i.refund_id = r.id
					WHERE r.id = ? AND r.status = 'UNKNOWN' AND j.status = 'NEEDS_REVIEW'
					  AND i.mismatch_type = 'AMOUNT_MISMATCH'
					""", Integer.class, mismatch.refundId()));
		}
	}

	@Test
	void 확정_상태와_금액_충돌을_기록하고_반복_탐지는_같은_행을_갱신한다() throws Exception {
		try (var server = new MockPgServer()) {
			var gateway = new HttpRefundGateway(server.주소(), Duration.ofSeconds(2));
			var target = 환불을_준비한다("FAILED", "COMPLETED", "PAID");
			gateway.refund(target.paymentId(), 10000, target.key());

			대사를_실행한다(gateway, START);
			대사를_실행한다(gateway, START.plusSeconds(60));

			assertEquals("FAILED", jdbc.queryForObject(
					"SELECT status FROM refund WHERE id = ?", String.class, target.refundId()));
			assertEquals("PAID", jdbc.queryForObject(
					"SELECT status FROM payment WHERE id = ?", String.class, target.paymentId()));
			assertEquals(2, jdbc.queryForObject("""
					SELECT COUNT(*) FROM refund_reconciliation_issue WHERE refund_id = ?
					  AND internal_amount = 15000 AND pg_amount = 10000
					  AND mismatch_type IN ('AMOUNT_MISMATCH', 'STATUS_CONFLICT')
					  AND TIMESTAMPDIFF(SECOND, first_detected_at, last_detected_at) = 60
					""", Integer.class, target.refundId()));
			assertEquals(1, server.환불_요청_횟수());
		}
	}

	@Test
	void PG_거래가_없을_때_내부_성공_건만_조사_기록을_남긴다() throws Exception {
		try (var server = new MockPgServer()) {
			var gateway = new HttpRefundGateway(server.주소(), Duration.ofSeconds(2));
			var succeeded = 환불을_준비한다("SUCCEEDED", "COMPLETED", "REFUNDED");
			환불을_준비한다("UNKNOWN", "NEEDS_REVIEW", "PAID");

			대사를_실행한다(gateway, START);

			assertEquals(2, server.조회_횟수());
			assertEquals(0, server.환불_요청_횟수());
			assertEquals(succeeded.refundId(), jdbc.queryForObject("""
					SELECT refund_id FROM refund_reconciliation_issue WHERE mismatch_type = 'MISSING_PG_RECORD'
					""", Long.class));
		}
	}

	@Test
	void 작업_완료_갱신이_거부되면_환불과_결제와_대사_이력이_함께_롤백된다() throws Exception {
		try (var server = new MockPgServer()) {
			var gateway = new HttpRefundGateway(server.주소(), Duration.ofSeconds(2));
			var target = 환불을_준비한다("UNKNOWN", "NEEDS_REVIEW", "PAID");
			gateway.refund(target.paymentId(), 15000, target.key());
			jdbc.execute("ALTER TABLE refund_job ADD CONSTRAINT ck_test_reject_reconciliation CHECK (status <> 'COMPLETED')");
			try {
				long jobId = jdbc.queryForObject(
						"SELECT id FROM refund_job WHERE refund_id = ?", Long.class, target.refundId());
				var record = gateway.queryLedger(target.key());
				assertThrows(DataIntegrityViolationException.class,
						() -> transactions.apply(jobId, record, START));
				assertEquals("UNKNOWN", jdbc.queryForObject(
						"SELECT status FROM refund WHERE id = ?", String.class, target.refundId()));
				assertEquals("PAID", jdbc.queryForObject(
						"SELECT status FROM payment WHERE id = ?", String.class, target.paymentId()));
				assertEquals("NEEDS_REVIEW", jdbc.queryForObject(
						"SELECT status FROM refund_job WHERE id = ?", String.class, jobId));
				assertEquals(0, jdbc.queryForObject(
						"SELECT COUNT(*) FROM refund_attempt WHERE refund_id = ?", Integer.class, target.refundId()));
			} finally {
				jdbc.execute("ALTER TABLE refund_job DROP CHECK ck_test_reject_reconciliation");
			}
		}
	}

	@Test
	void Worker를_끄고_대사만_켜도_스케줄러가_복구한다() throws Exception {
		try (var server = new MockPgServer()) {
			var gateway = new HttpRefundGateway(server.주소(), Duration.ofSeconds(2));
			var target = 환불을_준비한다("UNKNOWN", "NEEDS_REVIEW", "PAID");
			gateway.refund(target.paymentId(), 15000, target.key());

			try (var app = new SpringApplicationBuilder(ReturnloopApplication.class)
					.web(WebApplicationType.NONE)
					.run("--spring.datasource.url=" + mysql.getJdbcUrl(),
							"--spring.datasource.username=" + mysql.getUsername(),
							"--spring.datasource.password=" + mysql.getPassword(),
							"--returnloop.refund.worker.enabled=false",
							"--returnloop.refund.worker.pg-base-url=" + server.주소(),
							"--returnloop.refund.reconciliation.enabled=true")) {
				assertEquals(0, app.getBeansOfType(RefundWorker.class).size());
				long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
				String status;
				do {
					status = jdbc.queryForObject("SELECT status FROM refund WHERE id = ?", String.class, target.refundId());
					if (!"SUCCEEDED".equals(status)) {
						Thread.sleep(50);
					}
				} while (!"SUCCEEDED".equals(status) && System.nanoTime() < deadline);
				assertEquals("SUCCEEDED", status);
				assertEquals(1, server.조회_횟수());
				assertEquals(1, server.환불_요청_횟수());
			}
		}
	}

	private RefundTarget 환불을_준비한다(String refundStatus, String jobStatus, String paymentStatus) {
		long paymentId = payments.saveAndFlush(new Payment(15000L)).getId();
		long refundId = refunds.createRefund(paymentId).id();
		String key = jdbc.queryForObject("SELECT idempotency_key FROM refund WHERE id = ?", String.class, refundId);
		jdbc.update("UPDATE payment SET status = ? WHERE id = ?", paymentStatus, paymentId);
		jdbc.update("UPDATE refund SET status = ? WHERE id = ?", refundStatus, refundId);
		if (!"PENDING".equals(jobStatus)) {
			jdbc.update("""
					UPDATE refund_job SET status = ?, operation = 'QUERY_PG', query_count = 3 WHERE refund_id = ?
					""", jobStatus, refundId);
		}
		return new RefundTarget(paymentId, refundId, key);
	}

	private void 대사를_실행한다(HttpRefundGateway gateway, Instant observedAt) {
		new RefundReconciliationService(jobs, gateway, transactions, Clock.fixed(observedAt, ZoneOffset.UTC)).reconcile();
	}

	private record RefundTarget(long paymentId, long refundId, String key) {
	}
}
