package returnloop.refund.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundJobRepository extends JpaRepository<RefundJob, Long> {

	@Query(value = """
			SELECT id FROM refund_job
			WHERE (status = 'PENDING' AND (next_run_at IS NULL OR next_run_at <= :now))
			   OR (status = 'PROCESSING' AND lease_expires_at <= :now)
			ORDER BY id
			LIMIT 20
			""", nativeQuery = true)
	List<Long> findReadyJobIds(@Param("now") Instant now);
}
