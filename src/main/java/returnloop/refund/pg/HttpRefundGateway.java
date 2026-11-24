package returnloop.refund.pg;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class HttpRefundGateway implements RefundGateway {

	private final RestClient client;

	public HttpRefundGateway(URI baseUrl, Duration timeout) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(timeout);
		requestFactory.setReadTimeout(timeout);
		this.client = RestClient.builder()
				.baseUrl(baseUrl.toString())
				.requestFactory(requestFactory)
				.build();
	}

	@Override
	public Result refund(long paymentId, long amount, String idempotencyKey) {
		try {
			String status = client.post()
					.uri("/refunds")
					.header("Idempotency-Key", idempotencyKey)
					.contentType(MediaType.APPLICATION_JSON)
					.body(new RefundRequest(paymentId, amount))
					.retrieve()
					.body(String.class);
			return Result.valueOf(status);
		} catch (RestClientException exception) {
			if (exception.getMostSpecificCause() instanceof SocketTimeoutException) {
				throw new TimeoutException("PG refund request timed out", exception);
			}
			throw exception;
		}
	}

	@Override
	public QueryResult query(String idempotencyKey) {
		try {
			String status = client.get()
					.uri("/refunds/{idempotencyKey}", idempotencyKey)
					.retrieve()
					.body(String.class);
			return QueryResult.valueOf(status);
		} catch (ResourceAccessException exception) {
			return QueryResult.UNAVAILABLE;
		} catch (RestClientResponseException exception) {
			if (exception.getStatusCode().value() == 404) {
				return QueryResult.NOT_FOUND;
			}
			if (exception.getStatusCode().is5xxServerError()) {
				return QueryResult.UNAVAILABLE;
			}
			throw exception;
		} catch (RestClientException exception) {
			if (exception.getMostSpecificCause() instanceof SocketTimeoutException) {
				return QueryResult.UNAVAILABLE;
			}
			throw exception;
		}
	}

	private record RefundRequest(long paymentId, long amount) {
	}
}
