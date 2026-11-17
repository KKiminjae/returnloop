package returnloop.refund.controller;

import returnloop.payment.exception.PaymentNotFoundException;
import returnloop.refund.domain.RefundStatus;
import returnloop.refund.exception.RefundStateInconsistentException;
import returnloop.refund.dto.RefundResult;
import returnloop.refund.service.RefundService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(RefundController.class)
class RefundControllerTests {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private RefundService service;

	@Test
	void 최초_생성은_내부_필드_없이_환불과_201을_반환한다() throws Exception {
		when(service.createRefund(10L)).thenReturn(new RefundResult(1L, 10L, 15000L, RefundStatus.PENDING, true));

		mvc.perform(post("/payments/10/refund"))
				.andExpect(status().isCreated())
				.andExpect(content().json("""
						{"id":1,"paymentId":10,"amount":15000,"status":"PENDING"}
						"""))
				.andExpect(jsonPath("$.length()").value(4))
				.andExpect(jsonPath("$.created").doesNotExist())
				.andExpect(jsonPath("$.idempotencyKey").doesNotExist());
	}

	@ParameterizedTest
	@EnumSource(RefundStatus.class)
	void 기존_환불은_현재_상태와_200을_반환한다(RefundStatus refundStatus) throws Exception {
		when(service.createRefund(10L)).thenReturn(new RefundResult(1L, 10L, 15000L, refundStatus, false));

		mvc.perform(post("/payments/10/refund"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(1))
				.andExpect(jsonPath("$.paymentId").value(10))
				.andExpect(jsonPath("$.amount").value(15000))
				.andExpect(jsonPath("$.status").value(refundStatus.name()))
				.andExpect(jsonPath("$.length()").value(4));
	}

	@Test
	void 결제가_없으면_404를_반환한다() throws Exception {
		when(service.createRefund(10L)).thenThrow(new PaymentNotFoundException(10L));

		mvc.perform(post("/payments/10/refund"))
				.andExpect(status().isNotFound())
				.andExpect(content().json("""
						{"code":"PAYMENT_NOT_FOUND","message":"결제를 찾을 수 없습니다."}
						"""))
				.andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	void 상태가_불일치하면_409를_반환한다() throws Exception {
		when(service.createRefund(10L)).thenThrow(new RefundStateInconsistentException(10L));

		mvc.perform(post("/payments/10/refund"))
				.andExpect(status().isConflict())
				.andExpect(content().json("""
						{"code":"PAYMENT_REFUND_STATE_INCONSISTENT","message":"결제 상태와 환불 기록이 일치하지 않습니다."}
						"""))
				.andExpect(jsonPath("$.length()").value(2));
	}

	@Test
	void 예상하지_못한_DB_오류는_내부_내용_없이_500을_반환한다() throws Exception {
		when(service.createRefund(10L)).thenThrow(new DataIntegrityViolationException("SQL secret constraint"));

		mvc.perform(post("/payments/10/refund"))
				.andExpect(status().isInternalServerError())
				.andExpect(content().json("""
						{"code":"INTERNAL_SERVER_ERROR","message":"서버 내부 오류가 발생했습니다."}
						"""))
				.andExpect(jsonPath("$.length()").value(2));
	}
}
