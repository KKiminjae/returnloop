package returnloop.refund.domain;

import returnloop.payment.domain.Payment;

import java.util.Objects;
import java.util.UUID;

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
@Table(name = "refund")
public class Refund {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "payment_id", nullable = false, unique = true, updatable = false)
	private Payment payment;

	@Column(nullable = false, updatable = false)
	private Long amount;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private RefundStatus status;

	@Column(name = "idempotency_key", nullable = false, unique = true, length = 36, updatable = false)
	private String idempotencyKey;

	protected Refund() {
	}

	public Refund(Payment payment) {
		this.payment = Objects.requireNonNull(payment, "Payment must not be null");
		this.amount = payment.getAmount();
		this.status = RefundStatus.PENDING;
		this.idempotencyKey = UUID.randomUUID().toString();
	}

	public Long getId() {
		return id;
	}

	public Payment getPayment() {
		return payment;
	}

	public Long getAmount() {
		return amount;
	}

	public RefundStatus getStatus() {
		return status;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public void markSucceeded() {
		status = RefundStatus.SUCCEEDED;
	}

	public void markUnknown() {
		status = RefundStatus.UNKNOWN;
	}

	public void markFailed() {
		status = RefundStatus.FAILED;
	}
}
