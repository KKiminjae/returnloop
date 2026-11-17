package returnloop.refund.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundRepository extends JpaRepository<Refund, Long> {

	Optional<Refund> findByPaymentId(Long paymentId);
}
