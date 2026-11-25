package returnloop.refund.pg;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import returnloop.ReturnloopApplication;
import returnloop.payment.domain.Payment;
import returnloop.payment.domain.PaymentRepository;
import returnloop.refund.domain.RefundJobRepository;
import returnloop.refund.service.RefundService;
import returnloop.refund.worker.RefundWorker;
import returnloop.refund.worker.RefundWorkerTransactionService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RefundWorkerRestartIntegrationTests {

	@Container
	static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

	private static final Instant START = Instant.parse("2026-11-25T00:00:00Z");
	private static final Duration PG_TIMEOUT = Duration.ofSeconds(1);
	private static final Instant EXPIRY = START.plusSeconds(6);

	@Test
	void PG_처리_후_결과_저장_전_종료하면_재시작_후_만료_시점에_조회로_복구한다() throws Exception {
		try (MockPgServer pg = new MockPgServer()) {
			RefundGateway gateway = new HttpRefundGateway(pg.주소(), PG_TIMEOUT);
			long paymentId;
			long refundId;
			long jobId;
			String key;
			try (var app = 앱을_시작한다()) {
				paymentId = app.getBean(PaymentRepository.class).saveAndFlush(new Payment(15000L)).getId();
				refundId = app.getBean(RefundService.class).createRefund(paymentId).id();
				JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
				jobId = jdbc.queryForObject("SELECT id FROM refund_job WHERE refund_id = ?", Long.class, refundId);
				key = jdbc.queryForObject("SELECT idempotency_key FROM refund WHERE id = ?", String.class, refundId);
				var worker = 작업자를_만든다(app, new RefundGateway() {
					@Override
					public Result refund(long paymentId, long amount, String idempotencyKey) {
						assertEquals("PROCESSING", jdbc.queryForObject(
								"SELECT status FROM refund_job WHERE id = ?", String.class, jobId));
						assertEquals(Result.SUCCEEDED, gateway.refund(paymentId, amount, idempotencyKey));
						throw new IllegalStateException("Worker stopped before saving the PG result");
					}

					@Override
					public QueryResult query(String idempotencyKey) {
						throw new AssertionError("Unexpected PG query");
					}
				}, START);
				assertThrows(IllegalStateException.class, () -> worker.process(jobId));
				assertTrue(pg.환불이_기록되었다(key));
			}

			try (var app = 앱을_시작한다()) {
				JdbcTemplate jdbc = app.getBean(JdbcTemplate.class);
				var jobs = app.getBean(RefundJobRepository.class);
				assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM refund WHERE id = ?", String.class, refundId));
				assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM refund_attempt WHERE refund_id = ?", Integer.class, refundId));
				assertTrue(jobs.findReadyJobIds(EXPIRY.minusSeconds(1)).isEmpty());
				작업자를_만든다(app, gateway, EXPIRY.minusSeconds(1)).process(jobId);
				assertEquals(0, pg.조회_횟수());

				assertEquals(List.of(jobId), jobs.findReadyJobIds(EXPIRY));
				작업자를_만든다(app, gateway, EXPIRY).process(jobId);

				assertEquals(1, pg.환불_요청_횟수());
				assertEquals(1, pg.환불_실행_횟수());
				assertEquals(1, pg.조회_횟수());
				assertEquals(key, pg.마지막_조회_키());
				assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM refund WHERE id = ?", String.class, refundId));
				assertEquals("REFUNDED", jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId));
				assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM refund_job WHERE id = ?", String.class, jobId));
				assertEquals(1, jdbc.queryForObject(
						"SELECT COUNT(*) FROM refund_job WHERE id = ? AND lease_expires_at IS NULL", Integer.class, jobId));
				assertEquals(List.of("QUERY_PG:SUCCEEDED"), jdbc.query("""
						SELECT operation, outcome FROM refund_attempt WHERE refund_id = ?
						""", (row, index) -> row.getString("operation") + ":" + row.getString("outcome"), refundId));
			}
		}
	}

	private ConfigurableApplicationContext 앱을_시작한다() {
		return new SpringApplicationBuilder(ReturnloopApplication.class)
				.web(WebApplicationType.NONE)
				.run("--spring.datasource.url=" + mysql.getJdbcUrl(),
						"--spring.datasource.username=" + mysql.getUsername(),
						"--spring.datasource.password=" + mysql.getPassword(),
						"--returnloop.refund.worker.enabled=false");
	}

	private RefundWorker 작업자를_만든다(ConfigurableApplicationContext app, RefundGateway gateway, Instant now) {
		return new RefundWorker(app.getBean(RefundWorkerTransactionService.class), gateway,
				Clock.fixed(now, ZoneOffset.UTC), PG_TIMEOUT);
	}
}
