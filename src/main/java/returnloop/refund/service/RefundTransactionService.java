package returnloop.refund.service;

import returnloop.refund.dto.RefundResult;

import returnloop.payment.domain.Payment;
import returnloop.payment.domain.PaymentRepository;
import returnloop.payment.domain.PaymentStatus;
import returnloop.payment.exception.PaymentNotFoundException;
import returnloop.refund.domain.Refund;
import returnloop.refund.domain.RefundRepository;
import returnloop.refund.exception.RefundStateInconsistentException;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundTransactionService {

	private static final Logger log = LoggerFactory.getLogger(RefundTransactionService.class);

	private final PaymentRepository paymentRepository;
	private final RefundRepository refundRepository;

	public RefundTransactionService(PaymentRepository paymentRepository, RefundRepository refundRepository) {
		this.paymentRepository = paymentRepository;
		this.refundRepository = refundRepository;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RefundResult createOrFind(Long paymentId) {
		Payment payment = paymentRepository.findById(paymentId)
				.orElseThrow(() -> new PaymentNotFoundException(paymentId));
		Optional<Refund> existing = refundRepository.findByPaymentId(paymentId);
		if (existing.isPresent()) {
			return RefundResult.from(existing.get(), false);
		}
		if (payment.getStatus() != PaymentStatus.PAID) {
			log.error("Refund state inconsistent: paymentId={}, paymentStatus={}", paymentId, payment.getStatus());
			throw new RefundStateInconsistentException(paymentId);
		}
		Refund refund = refundRepository.saveAndFlush(new Refund(payment));
		return RefundResult.from(refund, true);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<RefundResult> findExisting(Long paymentId) {
		return refundRepository.findByPaymentId(paymentId).map(refund -> RefundResult.from(refund, false));
	}
}
