package returnloop.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment")
public class Payment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, updatable = false)
	private Long amount;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private PaymentStatus status;

	protected Payment() {
	}

	public Payment(long amount) {
		if (amount <= 0) {
			throw new IllegalArgumentException("Payment amount must be positive");
		}
		this.amount = amount;
		this.status = PaymentStatus.PAID;
	}

	public Long getId() {
		return id;
	}

	public Long getAmount() {
		return amount;
	}

	public PaymentStatus getStatus() {
		return status;
	}
}
