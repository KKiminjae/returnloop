package returnloop;

import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Testcontainers
@Transactional
class ReturnloopApplicationTests {

	@Container
	@ServiceConnection
	static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Flyway flyway;

	@Test
	void 마이그레이션은_한_번만_적용된다() {
		assertEquals("8", flyway.info().current().getVersion().getVersion());
		assertEquals(0, flyway.migrate().migrationsExecuted);
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '1' AND success = 1", Integer.class));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '2' AND success = 1", Integer.class));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '3' AND success = 1", Integer.class));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '4' AND success = 1", Integer.class));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '5' AND success = 1", Integer.class));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '6' AND success = 1", Integer.class));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '7' AND success = 1", Integer.class));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '8' AND success = 1", Integer.class));
	}

	@Test
	void 처리_중인_환불_작업만_점유_만료_시각을_가질_수_있다() {
		환불_저장(결제_저장(), 15000L, "PENDING", UUID.randomUUID().toString());
		long refundId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		제약_위반_검증(3819, "ck_refund_job_lease",
				() -> jdbc.update("""
						INSERT INTO refund_job (refund_id, status, lease_token)
						VALUES (?, 'PROCESSING', UUID())
						""", refundId));
		제약_위반_검증(3819, "ck_refund_job_lease",
				() -> jdbc.update("""
						INSERT INTO refund_job (refund_id, status, lease_expires_at)
						VALUES (?, 'PENDING', CURRENT_TIMESTAMP(6))
						""", refundId));
		제약_위반_검증(3819, "ck_refund_job_lease_token",
				() -> jdbc.update("""
						INSERT INTO refund_job (refund_id, status, lease_expires_at)
						VALUES (?, 'PROCESSING', CURRENT_TIMESTAMP(6))
						""", refundId));
		제약_위반_검증(3819, "ck_refund_job_lease_token",
				() -> jdbc.update("""
						INSERT INTO refund_job (refund_id, status, lease_token)
						VALUES (?, 'PENDING', UUID())
						""", refundId));
	}

	@Test
	void 정상적인_결제와_환불은_저장된다() {
		long paymentId = 결제_저장();
		환불_저장(paymentId, 15000L, "PENDING", UUID.randomUUID().toString());
		assertEquals(15000L, jdbc.queryForObject(
				"SELECT amount FROM refund WHERE payment_id = ?", Long.class, paymentId));
	}

	@Test
	void 동일한_결제의_중복_환불은_거부된다() {
		long paymentId = 결제_저장();
		환불_저장(paymentId, 15000L, "PENDING", UUID.randomUUID().toString());
		제약_위반_검증(1062, "uk_refund_payment_id",
				() -> 환불_저장(paymentId, 15000L, "PENDING", UUID.randomUUID().toString()));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM refund WHERE payment_id = ?", Integer.class, paymentId));
	}

	@Test
	void 동일한_멱등_키의_중복_저장은_거부된다() {
		long firstPaymentId = 결제_저장();
		long secondPaymentId = 결제_저장();
		String key = UUID.randomUUID().toString();
		환불_저장(firstPaymentId, 15000L, "PENDING", key);
		제약_위반_검증(1062, "uk_refund_idempotency_key",
				() -> 환불_저장(secondPaymentId, 15000L, "PENDING", key));
	}

	@Test
	void 존재하지_않는_결제의_환불은_거부된다() {
		제약_위반_검증(1452, "fk_refund_payment",
				() -> 환불_저장(-1L, 15000L, "PENDING", UUID.randomUUID().toString()));
	}

	@Test
	void 결제_ID가_NULL인_환불은_거부된다() {
		제약_위반_검증(1048, "payment_id",
				() -> 환불_저장(null, 15000L, "PENDING", UUID.randomUUID().toString()));
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1})
	void 금액이_0_이하인_결제와_환불은_거부된다(long amount) {
		제약_위반_검증(3819, "ck_payment_amount_positive",
				() -> jdbc.update("INSERT INTO payment (amount, status) VALUES (?, 'PAID')", amount));
		long paymentId = 결제_저장();
		제약_위반_검증(3819, "ck_refund_amount_positive",
				() -> 환불_저장(paymentId, amount, "PENDING", UUID.randomUUID().toString()));
	}

	@Test
	void 금액이_NULL인_결제와_환불은_거부된다() {
		제약_위반_검증(1048, "amount",
				() -> jdbc.update("INSERT INTO payment (amount, status) VALUES (NULL, 'PAID')"));
		long paymentId = 결제_저장();
		제약_위반_검증(1048, "amount",
				() -> 환불_저장(paymentId, null, "PENDING", UUID.randomUUID().toString()));
	}

	@Test
	void 멱등_키가_NULL인_환불은_거부된다() {
		long paymentId = 결제_저장();
		제약_위반_검증(1048, "idempotency_key", () -> 환불_저장(paymentId, 15000L, "PENDING", null));
	}

	@ParameterizedTest
	@ValueSource(strings = {"INVALID", "paid"})
	void 허용되지_않은_결제_상태는_거부된다(String status) {
		제약_위반_검증(3819, "ck_payment_status",
				() -> jdbc.update("INSERT INTO payment (amount, status) VALUES (15000, ?)", status));
	}

	@ParameterizedTest
	@ValueSource(strings = {"INVALID", "pending"})
	void 허용되지_않은_환불_상태는_거부된다(String status) {
		long paymentId = 결제_저장();
		제약_위반_검증(3819, "ck_refund_status",
				() -> 환불_저장(paymentId, 15000L, status, UUID.randomUUID().toString()));
	}

	@Test
	void 상태가_NULL인_결제와_환불은_거부된다() {
		제약_위반_검증(1048, "status",
				() -> jdbc.update("INSERT INTO payment (amount, status) VALUES (15000, NULL)"));
		long paymentId = 결제_저장();
		제약_위반_검증(1048, "status",
				() -> 환불_저장(paymentId, 15000L, null, UUID.randomUUID().toString()));
	}

	private long 결제_저장() {
		jdbc.update("INSERT INTO payment (amount, status) VALUES (15000, 'PAID')");
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void 환불_저장(Long paymentId, Long amount, String status, String key) {
		jdbc.update("INSERT INTO refund (payment_id, amount, status, idempotency_key) VALUES (?, ?, ?, ?)",
				paymentId, amount, status, key);
	}

	private void 제약_위반_검증(int errorCode, String constraint, Runnable operation) {
		DataAccessException exception = assertThrows(DataAccessException.class, operation::run);
		SQLException sqlException = assertInstanceOf(SQLException.class, exception.getMostSpecificCause());
		assertEquals(errorCode, sqlException.getErrorCode());
		assertTrue(sqlException.getMessage().contains(constraint), sqlException.getMessage());
	}
}
