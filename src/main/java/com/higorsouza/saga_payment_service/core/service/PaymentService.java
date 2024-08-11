package com.higorsouza.saga_payment_service.core.service;

import com.higorsouza.saga_payment_service.config.exception.ValidationException;
import com.higorsouza.saga_payment_service.core.dto.Event;
import com.higorsouza.saga_payment_service.core.dto.History;
import com.higorsouza.saga_payment_service.core.dto.OrderProducts;
import com.higorsouza.saga_payment_service.core.enums.EPaymentStatus;
import com.higorsouza.saga_payment_service.core.enums.ESagaStatus;
import com.higorsouza.saga_payment_service.core.model.Payment;
import com.higorsouza.saga_payment_service.core.producer.KafkaProducer;
import com.higorsouza.saga_payment_service.core.repository.PaymentRepository;
import com.higorsouza.saga_payment_service.core.utils.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Log4j2
@Service
@AllArgsConstructor
public class PaymentService {

    private static final String CURRENT_SOURCE = "PAYMENT_SERVICE";
    private static final Double REDUCE_SUM_VALUE = 0.0;
    private static final Double MIN_AMOUNT_VALUE = 0.1;

    private final JsonUtil jsonUtil;
    private final KafkaProducer kafkaProducer;

    @Autowired
    PaymentRepository paymentRepository;

    public void realizePayment(Event event){
        try {
            checkCurrentValidation(event);
            createPendingPayment(event);
            var payment = findByOrderIdAndTransactionId(event);
            validateAmount(payment.getTotalAmount());
            changePaymentToSuccess(payment);
            handleSuccess(event);
        }catch (Exception ex) {
            log.error("Error trying to make payment: ", ex);
        }
        kafkaProducer.sendEvent(jsonUtil.toJson(event));
    }

    private void checkCurrentValidation(Event event) {
        if(paymentRepository.existsByOrderIdAndTransactionId(event.getPayload().getId(), event.getTransactionId())){
            throw new ValidationException("There's another transactionId for this validation.");
        }
    }

    private void createPendingPayment(Event event){
        var totalAmount = calculateAmount(event);
        var totalItems = calculateTotalItems(event);
        var payment = Payment
                .builder()
                .orderId(event.getPayload().getId())
                .transactionId(event.getTransactionId())
                .totalAmount(totalAmount)
                .totalItems(totalItems)
                .build();
        save(payment);
        setEventAmountItems(event, payment);
    }

    private double calculateAmount(Event event) {
        return event.getPayload()
                .getProducts()
                .stream()
                .map(product -> product.getQuantity() * product.getProduct().getUnitValue())
                .reduce(REDUCE_SUM_VALUE, Double::sum);
    }

    private int calculateTotalItems(Event event) {
        return event.getPayload()
                .getProducts()
                .stream()
                .map(OrderProducts::getQuantity)
                .reduce(REDUCE_SUM_VALUE.intValue(), Integer::sum);
    }

    private void setEventAmountItems(Event event, Payment payment){
        event.getPayload().setTotalAmount(payment.getTotalAmount());
        event.getPayload().setTotalItems(payment.getTotalItems());
    }

    private void validateAmount(double amount) {
        if(amount < MIN_AMOUNT_VALUE) {
            log.info("The minimum amount available is {}", MIN_AMOUNT_VALUE.toString());
            throw new ValidationException("The minimum amount available is ".concat(MIN_AMOUNT_VALUE.toString()));
        }
    }

    private void changePaymentToSuccess(Payment payment){
        payment.setStatus(EPaymentStatus.SUCCESS);
        save(payment);
    }

    private void handleSuccess(Event event) {
        event.setStatus(ESagaStatus.SUCCESS);
        event.setSource(CURRENT_SOURCE);
        addHistory(event, "Payment realized successfully!");
    }

    private void addHistory(Event event, String message){
        var history = History
                .builder()
                .source(event.getSource())
                .status(event.getStatus())
                .message(message)
                .createdAt(LocalDateTime.now())
                .build();
        event.addToHistory(history);
    }

    private void handleFailCurrentNotExecuted(Event event, String message) {
        event.setStatus(ESagaStatus.ROLLBACK_PENDING);
        event.setSource(CURRENT_SOURCE);
        addHistory(event, "Fail to realize payment: ".concat(message));
    }

    public void realizeRefound(Event event) {
        event.setStatus(ESagaStatus.FAIL);
        event.setSource(CURRENT_SOURCE);
        try {
            changePaymentStatusToRefound(event);
            addHistory(event, "Rollback executed for payment!");
        }catch (Exception ex) {
            log.error("");
            addHistory(event, "Rollback not executed for payment: ".concat(ex.getMessage()));
        }
        kafkaProducer.sendEvent(jsonUtil.toJson(event));
    }

    private void changePaymentStatusToRefound(Event event) {
        var payment = findByOrderIdAndTransactionId(event);
        payment.setStatus(EPaymentStatus.REFUND);
        setEventAmountItems(event, payment);
        save(payment);
    }

    private Payment findByOrderIdAndTransactionId(Event event) {
        return paymentRepository
                .findByOrderIdAndTransactionId(event.getPayload().getId(), event.getTransactionId())
                .orElseThrow(() -> new ValidationException("Payment not found by orderId and TransactionId"));
    }

    private void save(Payment payment){
        paymentRepository.save(payment);
    }
}
