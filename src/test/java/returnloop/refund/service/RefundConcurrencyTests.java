package returnloop.refund.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import returnloop.payment.domain.Payment;
import returnloop.payment.domain.PaymentRepository;
import returnloop.refund.domain.Refund;
import returnloop.refund.domain.RefundRepository;
import returnloop.refund.domain.RefundStatus;
import returnloop.refund.dto.RefundResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockingDetails;

@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=10")
@Testcontainers
class RefundConcurrencyTests {

	@Container
	@ServiceConnection
	static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

	@Autowired
	RefundService service;

	@Autowired
	PaymentRepository payments;

	@MockitoSpyBean
	RefundRepository refunds;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	EntityManagerFactory entityManagerFactory;

	@BeforeEach
	void 테스트_DB를_정리한다() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			jdbc.update("DELETE FROM refund_job");
			jdbc.update("DELETE FROM refund");
			jdbc.update("DELETE FROM payment");
		});
	}

	@Test
	void 동일_결제에_백_건을_요청하면_하나의_환불을_반환한다() throws Exception {
		Long paymentId = 결제를_준비한다();
		var start = new CountDownLatch(1);
		var ready = new CountDownLatch(10);
		List<RefundResult> results = new ArrayList<>();
		try (var executor = Executors.newFixedThreadPool(10)) {
			var futures = new ArrayList<Future<RefundResult>>();
			for (int i = 0; i < 100; i++) {
				futures.add(executor.submit(() -> {
					ready.countDown();
					assertTrue(start.await(20, TimeUnit.SECONDS));
					return service.createRefund(paymentId);
				}));
			}
			try {
				assertTrue(ready.await(20, TimeUnit.SECONDS));
			} finally {
				start.countDown();
			}
			try {
				for (var future : futures) {
					results.add(future.get(30, TimeUnit.SECONDS));
				}
			} finally {
				executor.shutdownNow();
			}
		}
		assertEquals(100, results.size());
		결과와_DB를_확인한다(paymentId, results);
	}

	@Test
	void 두_생성_트랜잭션이_경쟁하면_충돌_롤백_후_새_트랜잭션에서_조회한다() throws Exception {
		Long paymentId = 결제를_준비한다();
		var barrier = new CyclicBarrier(2);
		var initialReads = new AtomicInteger();
		var rollbacks = new AtomicInteger();
		var commits = new AtomicInteger();
		var recoveries = new AtomicInteger();
		var resources = new ConcurrentLinkedQueue<Object>();
		var conflicts = new ConcurrentLinkedQueue<ConstraintViolationException>();
		var originalResource = new ThreadLocal<Object>();

		doAnswer(invocation -> {
			Object resource = TransactionSynchronizationManager.getResource(entityManagerFactory);
			assertNotNull(resource);
			Object result = mockingDetails(refunds).getMockCreationSettings().getDefaultAnswer().answer(invocation);
			if (initialReads.getAndIncrement() < 2) {
				assertTrue(((Optional<?>) result).isEmpty());
				resources.add(resource);
				originalResource.set(resource);
				TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
					@Override
					public void afterCompletion(int status) {
						if (status == STATUS_ROLLED_BACK) {
							rollbacks.incrementAndGet();
						} else if (status == STATUS_COMMITTED) {
							commits.incrementAndGet();
						}
					}
				});
				barrier.await(20, TimeUnit.SECONDS);
			} else {
				assertEquals(1, rollbacks.get());
				assertNotSame(originalResource.get(), resource);
				assertTrue(((Optional<?>) result).isPresent());
				recoveries.incrementAndGet();
			}
			return result;
		}).when(refunds).findByPaymentId(anyLong());

		doAnswer(invocation -> {
			try {
				return mockingDetails(refunds).getMockCreationSettings().getDefaultAnswer().answer(invocation);
			} catch (RuntimeException exception) {
				for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
					if (cause instanceof ConstraintViolationException violation) {
						conflicts.add(violation);
						break;
					}
				}
				throw exception;
			}
		}).when(refunds).saveAndFlush(any(Refund.class));

		List<RefundResult> results;
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> service.createRefund(paymentId));
			var second = executor.submit(() -> service.createRefund(paymentId));
			try {
				results = List.of(first.get(40, TimeUnit.SECONDS), second.get(40, TimeUnit.SECONDS));
			} finally {
				executor.shutdownNow();
			}
		}
		assertEquals(2, resources.size());
		assertNotSame(resources.toArray()[0], resources.toArray()[1]);
		assertEquals(1, commits.get());
		assertEquals(1, rollbacks.get());
		assertEquals(1, recoveries.get());
		assertEquals(1, conflicts.size());
		var conflict = conflicts.element();
		assertEquals(1062, conflict.getErrorCode());
		String name = conflict.getConstraintName();
		assertNotNull(name);
		assertEquals("uk_refund_payment_id", name.substring(name.lastIndexOf('.') + 1));
		결과와_DB를_확인한다(paymentId, results);
	}

	private Long 결제를_준비한다() {
		return new TransactionTemplate(transactionManager).execute(status ->
				payments.saveAndFlush(new Payment(15000L)).getId());
	}

	private void 결과와_DB를_확인한다(Long paymentId, List<RefundResult> results) {
		assertEquals(1, results.stream().filter(RefundResult::created).count());
		assertEquals(1, results.stream().map(RefundResult::id).distinct().count());
		for (RefundResult result : results) {
			assertEquals(paymentId, result.paymentId());
			assertEquals(15000L, result.amount());
			assertEquals(RefundStatus.PENDING, result.status());
		}
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund WHERE payment_id = ?", Integer.class, paymentId));
			assertEquals(results.getFirst().id(), jdbc.queryForObject("SELECT id FROM refund WHERE payment_id = ?", Long.class, paymentId));
			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refund_job WHERE refund_id = ?",
					Integer.class, results.getFirst().id()));
			assertEquals("PAID", jdbc.queryForObject("SELECT status FROM payment WHERE id = ?", String.class, paymentId));
		});
	}
}
