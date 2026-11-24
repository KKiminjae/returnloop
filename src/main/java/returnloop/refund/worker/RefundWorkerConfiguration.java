package returnloop.refund.worker;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

import returnloop.refund.domain.RefundJobRepository;
import returnloop.refund.pg.HttpRefundGateway;
import returnloop.refund.pg.RefundGateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "returnloop.refund.worker", name = "enabled", havingValue = "true")
@EnableScheduling
class RefundWorkerConfiguration {

	@Bean
	RefundGateway refundGateway(
			@Value("${returnloop.refund.worker.pg-base-url}") URI baseUrl,
			@Value("${returnloop.refund.worker.pg-timeout}") Duration timeout) {
		return new HttpRefundGateway(baseUrl, timeout);
	}

	@Bean
	RefundWorker refundWorker(RefundWorkerTransactionService transactions, RefundGateway gateway) {
		return new RefundWorker(transactions, gateway, Clock.systemUTC());
	}

	@Bean
	RefundJobScheduler refundJobScheduler(RefundJobRepository jobs, RefundWorker worker) {
		return new RefundJobScheduler(jobs, worker, Clock.systemUTC());
	}
}
