package returnloop.refund.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundJobRepository extends JpaRepository<RefundJob, Long> {

	@Query(value = "SELECT COALESCE(MAX(id), 0) FROM refund_job", nativeQuery = true)
	long findMaxJobId();

	@Query(value = """
			SELECT job.id AS jobId, idempotency_key AS idempotencyKey
			FROM refund_job job JOIN refund ON refund.id = job.refund_id
			WHERE job.id > :afterId AND job.id <= :maxId
			  AND job.status IN ('COMPLETED', 'NEEDS_REVIEW')
			ORDER BY job.id
			LIMIT 20
			""", nativeQuery = true)
	List<ReconciliationCandidate> findReconciliationCandidates(
			@Param("afterId") long afterId, @Param("maxId") long maxId);

	interface ReconciliationCandidate {
		long getJobId();
		String getIdempotencyKey();
	}

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT job FROM RefundJob job WHERE job.id = :id")
	Optional<RefundJob> findByIdForUpdate(@Param("id") long id);

	@Query(value = """
			SELECT id FROM refund_job
			WHERE (status = 'PENDING' AND (next_run_at IS NULL OR next_run_at <= :now))
			   OR (status = 'PROCESSING' AND lease_expires_at <= :now)
			ORDER BY id
			LIMIT 20
			""", nativeQuery = true)
	List<Long> findReadyJobIds(@Param("now") Instant now);
}
