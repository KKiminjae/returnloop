package returnloop.refund.worker;

import java.time.Clock;

import returnloop.refund.domain.RefundJobRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

class RefundJobScheduler {

	private static final Logger logger = LoggerFactory.getLogger(RefundJobScheduler.class);

	private final RefundJobRepository jobs;
	private final RefundWorker worker;
	private final Clock clock;

	RefundJobScheduler(RefundJobRepository jobs, RefundWorker worker, Clock clock) {
		this.jobs = jobs;
		this.worker = worker;
		this.clock = clock;
	}

	@Scheduled(fixedDelay = 1000)
	void processReadyJobs() {
		for (long jobId : jobs.findReadyJobIds(clock.instant())) {
			try {
				worker.process(jobId);
			} catch (RuntimeException exception) {
				logger.error("Refund job {} processing failed", jobId, exception);
			}
		}
	}
}
