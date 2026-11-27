package returnloop.refund.pg;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.function.Function;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class HttpRefundGateway implements RefundGateway, RefundLedger {

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
		return query(idempotencyKey, MediaType.TEXT_PLAIN, response -> QueryResult.valueOf(response.body(String.class)),
				QueryResult.NOT_FOUND, QueryResult.UNAVAILABLE);
	}

	@Override
	public LedgerResult queryLedger(String idempotencyKey) {
		return query(idempotencyKey, MediaType.APPLICATION_JSON, response -> {
			LedgerResult record = response.body(LedgerResult.class);
			if (record == null || (record.status() != QueryResult.SUCCEEDED && record.status() != QueryResult.FAILED)
					|| record.paymentId() == null || record.amount() == null || record.idempotencyKey() == null) {
				throw new IllegalStateException("Invalid PG ledger record");
			}
			return record;
		}, new LedgerResult(QueryResult.NOT_FOUND, null, null, null),
				new LedgerResult(QueryResult.UNAVAILABLE, null, null, null));
	}

	private <T> T query(String idempotencyKey, MediaType mediaType,
			Function<RestClient.ResponseSpec, T> readBody, T notFound, T unavailable) {
		try {
			var response = client.get()
					.uri("/refunds/{idempotencyKey}", idempotencyKey)
					.accept(mediaType)
					.retrieve();
			return readBody.apply(response);
		} catch (ResourceAccessException exception) {
			return unavailable;
		} catch (RestClientResponseException exception) {
			if (exception.getStatusCode().value() == 404) {
				return notFound;
			}
			if (exception.getStatusCode().is5xxServerError()) {
				return unavailable;
			}
			throw exception;
		} catch (RestClientException exception) {
			if (exception.getMostSpecificCause() instanceof SocketTimeoutException) {
				return unavailable;
			}
			throw exception;
		}
	}

	private record RefundRequest(long paymentId, long amount) {
	}
}
