package returnloop.refund.worker;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import returnloop.payment.domain.Payment;
import returnloop.payment.domain.PaymentRepository;
import returnloop.refund.dto.RefundResult;
import returnloop.refund.pg.RefundGateway;
import returnloop.refund.pg.RefundGateway.QueryResult;
import returnloop.refund.service.RefundService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

	private static final Instant INITIAL_TIME = Instant.parse("2026-11-20T00:00:00Z");

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

	@MockitoBean
	Clock clock;

	@BeforeEach
	void 테스트를_준비한다() {
		when(clock.instant()).thenReturn(INITIAL_TIME);
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			jdbc.update("DELETE FROM refund_attempt");
			jdbc.update("DELETE FROM refund_job");
			jdbc.update("DELETE FROM refund");
			jdbc.update("DELETE FROM payment");
		});
	}

	@Test
	void 트랜잭션_밖에서_PG를_호출하고_성공_결과를_함께_커밋한다() {
		JobTarget target = 작업_준비();
		PG_성공_응답_준비(target);

		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
			worker.process(target.jobId());
			assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
		});
		worker.process(target.jobId());

		verify(gateway, times(1)).refund(target.paymentId(), 15000L, target.key());
		저장_상태_확인(target, "SUCCEEDED", "REFUNDED", "COMPLETED");
		이력_확인(target, new AttemptEntry("EXECUTE_REFUND", "SUCCEEDED", INITIAL_TIME));
	}

	@Test
	void 작업_완료_갱신이_거부되면_성공_결과가_커밋되지_않는다() {
		JobTarget target = 작업_준비();
		PG_성공_응답_준비(target);

		jdbc.execute("""
				ALTER TABLE refund_job ADD CONSTRAINT ck_test_reject_job_completion
				CHECK (status <> 'COMPLETED')
				""");
		try {
			DataAccessException error = assertThrows(DataAccessException.class, () -> worker.process(target.jobId()));

			assertTrue(error.getMostSpecificCause().getMessage().contains("ck_test_reject_job_completion"));
			verify(gateway).refund(target.paymentId(), 15000L, target.key());
			저장_상태_확인(target, "PENDING", "PAID", "PENDING");
			이력_확인(target);
		} finally {
			jdbc.execute("ALTER TABLE refund_job DROP CHECK ck_test_reject_job_completion");
		}
	}

	@ParameterizedTest
	@CsvSource({"SUCCEEDED, REFUNDED", "FAILED, PAID"})
	void 환불_응답이_유실되면_같은_키로_조회한_확정_결과를_저장한다(
			QueryResult queryResult, String paymentStatus) {
		JobTarget target = 작업_준비();
		PG_환불_타임아웃_준비(target);
		when(gateway.query(target.key())).thenAnswer(invocation -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			return queryResult;
		});

		worker.process(target.jobId());

		verify(gateway, never()).query(target.key());
		저장_상태_확인(target, "UNKNOWN", "PAID", "PENDING");
		조회_일정_확인(target.jobId(), 0, INITIAL_TIME);
		이력_확인(target, new AttemptEntry("EXECUTE_REFUND", "TIMEOUT", INITIAL_TIME));

		when(clock.instant()).thenReturn(INITIAL_TIME.plusSeconds(2));
		worker.process(target.jobId());
		worker.process(target.jobId());

		verify(gateway).refund(target.paymentId(), 15000L, target.key());
		verify(gateway).query(target.key());
		verifyNoMoreInteractions(gateway);
		저장_상태_확인(target, queryResult.name(), paymentStatus, "COMPLETED");
		조회_일정_확인(target.jobId(), 1, null);
		이력_확인(target,
				new AttemptEntry("EXECUTE_REFUND", "TIMEOUT", INITIAL_TIME),
				new AttemptEntry("QUERY_PG", queryResult.name(), INITIAL_TIME.plusSeconds(2)));
	}

	@Test
	void 확정_결과가_없으면_예약_시각에만_조회하고_세_번_후_중단한다() {
		JobTarget target = 작업_준비();
		PG_환불_타임아웃_준비(target);
		when(gateway.query(target.key()))
				.thenReturn(QueryResult.NOT_FOUND)
				.thenThrow(new RefundGateway.TimeoutException("PG query timed out"))
				.thenReturn(QueryResult.UNAVAILABLE);

		worker.process(target.jobId());
		worker.process(target.jobId());

		조회_일정_확인(target.jobId(), 1, INITIAL_TIME.plusSeconds(5));
		when(clock.instant()).thenReturn(INITIAL_TIME.plusSeconds(4));
		worker.process(target.jobId());
		verify(gateway, times(1)).query(target.key());

		when(clock.instant()).thenReturn(INITIAL_TIME.plusSeconds(5));
		worker.process(target.jobId());

		저장_상태_확인(target, "UNKNOWN", "PAID", "PENDING");
		조회_일정_확인(target.jobId(), 2, INITIAL_TIME.plusSeconds(35));
		when(clock.instant()).thenReturn(INITIAL_TIME.plusSeconds(34));
		worker.process(target.jobId());
		verify(gateway, times(2)).query(target.key());

		when(clock.instant()).thenReturn(INITIAL_TIME.plusSeconds(35));
		worker.process(target.jobId());
		when(clock.instant()).thenReturn(INITIAL_TIME.plusSeconds(3600));
		worker.process(target.jobId());

		저장_상태_확인(target, "UNKNOWN", "PAID", "NEEDS_REVIEW");
		조회_일정_확인(target.jobId(), 3, null);
		verify(gateway).refund(target.paymentId(), 15000L, target.key());
		verify(gateway, times(3)).query(target.key());
		verifyNoMoreInteractions(gateway);
		이력_확인(target,
				new AttemptEntry("EXECUTE_REFUND", "TIMEOUT", INITIAL_TIME),
				new AttemptEntry("QUERY_PG", "NOT_FOUND", INITIAL_TIME),
				new AttemptEntry("QUERY_PG", "UNAVAILABLE", INITIAL_TIME.plusSeconds(5)),
				new AttemptEntry("QUERY_PG", "UNAVAILABLE", INITIAL_TIME.plusSeconds(35)));
	}

	private void 이력_확인(JobTarget target, AttemptEntry... expected) {
		assertEquals(List.of(expected), jdbc.query("""
				SELECT operation, outcome, attempted_at FROM refund_attempt WHERE refund_id = ? ORDER BY id
				""", (row, index) -> new AttemptEntry(row.getString("operation"), row.getString("outcome"),
				row.getObject("attempted_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)), target.refundId()));
	}

	private record AttemptEntry(String operation, String outcome, Instant attemptedAt) {
	}

	private void PG_환불_타임아웃_준비(JobTarget target) {
		when(gateway.refund(target.paymentId(), 15000L, target.key())).thenThrow(
				new RefundGateway.TimeoutException("PG refund timed out"));
	}

	private void 조회_일정_확인(long jobId, int queryCount, Instant nextRunAt) {
		jdbc.queryForObject("SELECT operation, query_count, next_run_at FROM refund_job WHERE id = ?", (row, index) -> {
			assertEquals("QUERY_PG", row.getString("operation"));
			assertEquals(queryCount, row.getInt("query_count"));
			LocalDateTime stored = row.getObject("next_run_at", LocalDateTime.class);
			assertEquals(nextRunAt, stored == null ? null : stored.toInstant(ZoneOffset.UTC));
			return jobId;
		}, jobId);
	}

	private JobTarget 작업_준비() {
		Long paymentId = new TransactionTemplate(transactionManager).execute(status ->
				paymentRepository.saveAndFlush(new Payment(15000L)).getId());
		RefundResult refund = refundService.createRefund(paymentId);
		Long jobId = jdbc.queryForObject("SELECT id FROM refund_job WHERE refund_id = ?", Long.class, refund.id());
		String key = jdbc.queryForObject("SELECT idempotency_key FROM refund WHERE id = ?", String.class, refund.id());
		return new JobTarget(paymentId, refund.id(), jobId, key);
	}

	private void PG_성공_응답_준비(JobTarget target) {
		when(gateway.refund(target.paymentId(), 15000L, target.key())).thenAnswer(invocation -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			return RefundGateway.Result.SUCCEEDED;
		});
	}

	private void 저장_상태_확인(JobTarget target, String refundStatus, String paymentStatus, String jobStatus) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			assertEquals(refundStatus, jdbc.queryForObject(
					"SELECT status FROM refund WHERE id = ?", String.class, target.refundId()));
			assertEquals(paymentStatus, jdbc.queryForObject(
					"SELECT status FROM payment WHERE id = ?", String.class, target.paymentId()));
			assertEquals(jobStatus, jdbc.queryForObject(
					"SELECT status FROM refund_job WHERE id = ?", String.class, target.jobId()));
		});
	}

	private record JobTarget(long paymentId, long refundId, long jobId, String key) {
	}
}
