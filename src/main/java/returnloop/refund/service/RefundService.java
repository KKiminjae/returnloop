package returnloop.refund.service;

import returnloop.refund.dto.RefundResult;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundService {

	private final RefundTransactionService transactions;

	public RefundService(RefundTransactionService transactions) {
		this.transactions = transactions;
	}

	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	public RefundResult createRefund(Long paymentId) {
		try {
			return transactions.createOrFind(paymentId);
		} catch (DataIntegrityViolationException exception) {
			if (!isPaymentRefundConflict(exception)) {
				throw exception;
			}
			return transactions.findExisting(paymentId).orElseThrow(() -> exception);
		}
	}

	private boolean isPaymentRefundConflict(Throwable exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation && violation.getErrorCode() == 1062) {
				String name = violation.getConstraintName();
				if (name != null && name.substring(name.lastIndexOf('.') + 1).equals("uk_refund_payment_id")) {
					return true;
				}
			}
		}
		return false;
	}
}
