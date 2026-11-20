package returnloop.refund.domain;

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

	protected RefundJob() {
	}

	public RefundJob(Refund refund) {
		this.refund = Objects.requireNonNull(refund, "Refund must not be null");
		this.status = Status.PENDING;
	}

	public Refund getRefund() {
		return refund;
	}

	public boolean isPending() {
		return status == Status.PENDING;
	}

	public void complete() {
		status = Status.COMPLETED;
	}

	private enum Status {
		PENDING, COMPLETED
	}
}
