package returnloop.refund.pg;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import returnloop.payment.domain.Payment;
import returnloop.payment.domain.PaymentRepository;
import returnloop.refund.dto.RefundResult;
import returnloop.refund.service.RefundService;
import returnloop.refund.service.RefundWorker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@Import({RefundWorker.class, RefundHttpIntegrationTests.PgConfiguration.class})
class RefundHttpIntegrationTests {

	@Container
	@ServiceConnection
	static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

	@Autowired
	RefundService refundService;

	@Autowired
	PaymentRepository paymentRepository;

	@Autowired
	RefundWorker worker;

	@Autowired
	MockPgServer pg;

	@Autowired
	RefundGateway gateway;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void PG가_환불을_기록한_뒤_HTTP_응답이_타임아웃되면_같은_키로_복구한다() {
		Long paymentId = paymentRepository.saveAndFlush(new Payment(15000L)).getId();
		RefundResult refund = refundService.createRefund(paymentId);
		Long jobId = jdbc.queryForObject("SELECT id FROM refund_job WHERE refund_id = ?", Long.class, refund.id());
		String key = jdbc.queryForObject("SELECT idempotency_key FROM refund WHERE id = ?", String.class, refund.id());
		int refundRequests = pg.환불_요청_횟수();
		int executions = pg.환불_실행_횟수();
		int queries = pg.조회_횟수();
		pg.응답을_보류한다(key);

		try {
			worker.process(jobId);

			assertTrue(pg.환불이_기록되었다(key));
			assertEquals("UNKNOWN", jdbc.queryForObject("SELECT status FROM refund WHERE id = ?", String.class, refund.id()));
			assertEquals("QUERY_PG", jdbc.queryForObject("SELECT operation FROM refund_job WHERE id = ?", String.class, jobId));
			assertEquals(queries, pg.조회_횟수());

			worker.process(jobId);

			assertEquals("SUCCEEDED", jdbc.queryForObject("SELECT status FROM refund WHERE id = ?", String.class, refund.id()));
			assertEquals("REFUNDED", jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId));
			assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM refund_job WHERE id = ?", String.class, jobId));
			assertEquals(refundRequests + 1, pg.환불_요청_횟수());
			assertEquals(executions + 1, pg.환불_실행_횟수());
			assertEquals(queries + 1, pg.조회_횟수());
		} finally {
			pg.응답_보류를_해제한다();
		}
	}

	@Test
	void 같은_멱등성_키로_재요청하면_첫_환불_결과를_재사용한다() {
		String key = UUID.randomUUID().toString();
		int refundRequests = pg.환불_요청_횟수();
		int executions = pg.환불_실행_횟수();

		assertEquals(RefundGateway.Result.SUCCEEDED, gateway.refund(1L, 15000L, key));
		assertEquals(RefundGateway.Result.SUCCEEDED, gateway.refund(1L, 15000L, key));

		assertTrue(pg.환불이_기록되었다(key));
		assertEquals(refundRequests + 2, pg.환불_요청_횟수());
		assertEquals(executions + 1, pg.환불_실행_횟수());
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class PgConfiguration {

		@Bean
		MockPgServer mockPgServer() throws IOException {
			return new MockPgServer();
		}

		@Bean
		RefundGateway refundGateway(MockPgServer server) {
			return new HttpRefundGateway(server.주소(), Duration.ofSeconds(1));
		}

		@Bean
		Clock clock() {
			return Clock.systemUTC();
		}
	}
}
