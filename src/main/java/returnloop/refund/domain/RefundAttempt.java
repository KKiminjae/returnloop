package returnloop.refund.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "refund_attempt")
public class RefundAttempt {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "refund_id", nullable = false, updatable = false)
	private Refund refund;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20, updatable = false)
	private Operation operation;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20, updatable = false)
	private Outcome outcome;

	@Column(name = "attempted_at", nullable = false, updatable = false)
	private Instant attemptedAt;

	protected RefundAttempt() {
	}

	public RefundAttempt(Refund refund, RefundJob.Operation operation, Outcome outcome, Instant attemptedAt) {
		this(refund, switch (operation) {
			case EXECUTE_REFUND -> Operation.EXECUTE_REFUND;
			case QUERY_PG -> Operation.QUERY_PG;
		}, outcome, attemptedAt);
	}

	public RefundAttempt(Refund refund, Operation operation, Outcome outcome, Instant attemptedAt) {
		this.refund = refund;
		this.operation = operation;
		this.outcome = outcome;
		this.attemptedAt = attemptedAt;
	}

	public enum Operation {
		EXECUTE_REFUND, QUERY_PG, RECONCILE
	}

	public enum Outcome {
		SUCCEEDED, FAILED, TIMEOUT, NOT_FOUND, UNAVAILABLE
	}
}
