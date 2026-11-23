package returnloop.refund.domain;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RefundAttemptRepository extends JpaRepository<RefundAttempt, Long> {
}
