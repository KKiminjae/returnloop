package returnloop.refund.domain;

import java.util.Objects;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "refund_job")
public class RefundJob {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "refund_id", nullable = false, unique = true, updatable = false)
	private Refund refund;

	protected RefundJob() {
	}

	public RefundJob(Refund refund) {
		this.refund = Objects.requireNonNull(refund, "Refund must not be null");
	}
}
