package returnloop.refund.pg;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${returnloop.refund.worker.enabled:false} or ${returnloop.refund.reconciliation.enabled:false}")
class RefundGatewayConfiguration {

	@Bean
	HttpRefundGateway refundGateway(
			@Value("${returnloop.refund.worker.pg-base-url}") URI baseUrl,
			@Value("${returnloop.refund.worker.pg-timeout}") Duration timeout) {
		return new HttpRefundGateway(baseUrl, timeout);
	}
}
