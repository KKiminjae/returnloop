package returnloop.refund.pg;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

final class MockPgServer implements AutoCloseable {

	private final HttpServer server;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final JsonMapper json = JsonMapper.shared();
	private final Map<String, RefundLedger.LedgerResult> refunds = new ConcurrentHashMap<>();
	private final AtomicInteger executions = new AtomicInteger();
	private final AtomicInteger refundRequests = new AtomicInteger();
	private final AtomicInteger queries = new AtomicInteger();
	private final AtomicBoolean delayNextRefund = new AtomicBoolean();
	private final CountDownLatch releaseResponse = new CountDownLatch(1);
	private volatile String delayedKey;
	private volatile String lastQueryKey;

	MockPgServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(executor);
		server.createContext("/refunds", this::처리한다);
		server.start();
	}

	URI 주소() {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
	}

	void 응답을_보류한다(String key) {
		delayedKey = key;
	}

	void 다음_환불_응답을_보류한다() {
		delayNextRefund.set(true);
	}

	void 응답_보류를_해제한다() {
		releaseResponse.countDown();
	}

	boolean 환불이_기록되었다(String key) {
		return refunds.containsKey(key);
	}

	void 환불_실패를_기록한다(long paymentId, long amount, String key) {
		refunds.put(key, new RefundLedger.LedgerResult(RefundGateway.QueryResult.FAILED, paymentId, amount, key));
	}

	int 환불_실행_횟수() {
		return executions.get();
	}

	int 환불_요청_횟수() {
		return refundRequests.get();
	}

	int 조회_횟수() {
		return queries.get();
	}

	String 마지막_조회_키() {
		return lastQueryKey;
	}

	private void 처리한다(HttpExchange exchange) throws IOException {
		try (exchange) {
			String path = exchange.getRequestURI().getPath();
			if ("POST".equals(exchange.getRequestMethod()) && "/refunds".equals(path)) {
				환불한다(exchange);
			} else if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/refunds/")) {
				queries.incrementAndGet();
				String key = path.substring("/refunds/".length());
				lastQueryKey = key;
				var record = refunds.get(key);
				if (record == null) {
					exchange.sendResponseHeaders(404, -1);
				} else if ("application/json".equals(exchange.getRequestHeaders().getFirst("Accept"))) {
					응답한다(exchange, json.writeValueAsString(record), "application/json");
				} else {
					응답한다(exchange, record.status().name(), "text/plain");
				}
			} else {
				exchange.sendResponseHeaders(501, -1);
			}
		}
	}

	private void 환불한다(HttpExchange exchange) throws IOException {
		refundRequests.incrementAndGet();
		String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
		if (key == null || key.isBlank()) {
			exchange.sendResponseHeaders(400, -1);
			return;
		}

		var request = json.readValue(exchange.getRequestBody(), RefundRequest.class);
		var record = new RefundLedger.LedgerResult(RefundGateway.QueryResult.SUCCEEDED,
				request.paymentId(), request.amount(), key);
		var recordedRequest = refunds.computeIfAbsent(key, ignored -> {
			executions.incrementAndGet();
			return record;
		});
		if (!recordedRequest.equals(record)) {
			exchange.sendResponseHeaders(409, -1);
			return;
		}
		if (key.equals(delayedKey) || delayNextRefund.getAndSet(false)) {
			try {
				releaseResponse.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		성공을_응답한다(exchange);
	}

	private void 성공을_응답한다(HttpExchange exchange) throws IOException {
		응답한다(exchange, "SUCCEEDED", "text/plain");
	}

	private void 응답한다(HttpExchange exchange, String value, String contentType) throws IOException {
		byte[] body = value.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", contentType);
		exchange.sendResponseHeaders(200, body.length);
		exchange.getResponseBody().write(body);
	}

	private record RefundRequest(long paymentId, long amount) {
	}

	@Override
	public void close() {
		releaseResponse.countDown();
		server.stop(0);
		executor.close();
	}
}
