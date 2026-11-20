package returnloop.refund.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "refund_job")
public class RefundJob {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "refund_id", nullable = false, unique = true, updatable = false)
	private Refund refund;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private Status status;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private Operation operation;

	@Column(name = "query_count", nullable = false)
	private int queryCount;

	@Column(name = "next_run_at")
	private Instant nextRunAt;

	protected RefundJob() {
	}

	public RefundJob(Refund refund) {
		this.refund = Objects.requireNonNull(refund, "Refund must not be null");
		this.status = Status.PENDING;
		this.operation = Operation.EXECUTE_REFUND;
	}

	public Refund getRefund() {
		return refund;
	}

	public Operation getOperation() {
		return operation;
	}

	public boolean isReady(Instant now) {
		return status == Status.PENDING && (nextRunAt == null || !now.isBefore(nextRunAt));
	}

	public boolean isPending(Operation expectedOperation) {
		return status == Status.PENDING && operation == expectedOperation;
	}

	public void scheduleQuery(Instant now) {
		operation = Operation.QUERY_PG;
		nextRunAt = now;
	}

	public void recordUnresolvedQuery(Instant now) {
		queryCount++;
		switch (queryCount) {
			case 1 -> nextRunAt = now.plusSeconds(5);
			case 2 -> nextRunAt = now.plusSeconds(30);
			case 3 -> {
				status = Status.NEEDS_REVIEW;
				nextRunAt = null;
			}
			default -> throw new IllegalStateException("Refund job query limit exceeded");
		}
	}

	public void complete() {
		if (operation == Operation.QUERY_PG) {
			queryCount++;
		}
		status = Status.COMPLETED;
		nextRunAt = null;
	}

	public enum Operation {
		EXECUTE_REFUND, QUERY_PG
	}

	private enum Status {
		PENDING, COMPLETED, NEEDS_REVIEW
	}
}
