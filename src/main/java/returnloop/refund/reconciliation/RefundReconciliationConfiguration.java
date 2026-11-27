package returnloop.refund.reconciliation;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import returnloop.refund.domain.RefundJobRepository;
import returnloop.refund.pg.RefundLedger;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "returnloop.refund.reconciliation", name = "enabled", havingValue = "true")
@EnableScheduling
class RefundReconciliationConfiguration {

	@Bean
	ThreadPoolTaskScheduler taskScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setThreadNamePrefix("refund-worker-");
		return scheduler;
	}

	@Bean
	ThreadPoolTaskScheduler refundReconciliationTaskScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setThreadNamePrefix("refund-reconciliation-");
		return scheduler;
	}

	@Bean
	RefundReconciliationService refundReconciliationService(RefundJobRepository jobs, RefundLedger ledger,
			RefundReconciliationTransactionService transactions) {
		return new RefundReconciliationService(jobs, ledger, transactions, Clock.systemUTC());
	}

	@Bean
	RefundReconciliationScheduler refundReconciliationScheduler(RefundReconciliationService reconciliation) {
		return new RefundReconciliationScheduler(reconciliation);
	}
}
