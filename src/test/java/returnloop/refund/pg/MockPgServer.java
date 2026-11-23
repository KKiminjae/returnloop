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
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

final class MockPgServer implements AutoCloseable {

	private final HttpServer server;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Map<String, String> refunds = new ConcurrentHashMap<>();
	private final AtomicInteger executions = new AtomicInteger();
	private final AtomicInteger refundRequests = new AtomicInteger();
	private final AtomicInteger queries = new AtomicInteger();
	private final CountDownLatch releaseResponse = new CountDownLatch(1);
	private volatile String delayedKey;

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

	void 응답_보류를_해제한다() {
		releaseResponse.countDown();
	}

	boolean 환불이_기록되었다(String key) {
		return refunds.containsKey(key);
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

	private void 처리한다(HttpExchange exchange) throws IOException {
		try (exchange) {
			String path = exchange.getRequestURI().getPath();
			if ("POST".equals(exchange.getRequestMethod()) && "/refunds".equals(path)) {
				환불한다(exchange);
			} else if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/refunds/")) {
				queries.incrementAndGet();
				String key = path.substring("/refunds/".length());
				if (refunds.containsKey(key)) {
					성공을_응답한다(exchange);
				} else {
					exchange.sendResponseHeaders(404, -1);
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

		String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		String recordedRequest = refunds.computeIfAbsent(key, ignored -> {
			executions.incrementAndGet();
			return request;
		});
		if (!recordedRequest.equals(request)) {
			exchange.sendResponseHeaders(409, -1);
			return;
		}
		if (key.equals(delayedKey)) {
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
		byte[] body = "SUCCEEDED".getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "text/plain");
		exchange.sendResponseHeaders(200, body.length);
		exchange.getResponseBody().write(body);
	}

	@Override
	public void close() {
		releaseResponse.countDown();
		server.stop(0);
		executor.close();
	}
}
