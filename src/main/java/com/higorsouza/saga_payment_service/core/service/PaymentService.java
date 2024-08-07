package com.higorsouza.saga_payment_service.core.service;

import com.higorsouza.saga_payment_service.config.exception.ValidationException;
import com.higorsouza.saga_payment_service.core.dto.Event;
import com.higorsouza.saga_payment_service.core.dto.OrderProducts;
import com.higorsouza.saga_payment_service.core.model.Payment;
import com.higorsouza.saga_payment_service.core.producer.KafkaProducer;
import com.higorsouza.saga_payment_service.core.repository.PaymentRepository;
import com.higorsouza.saga_payment_service.core.utils.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Log4j2
@Service
@AllArgsConstructor
public class PaymentService {

    private static final String CURRENT_SOURCE = "PAYMENT_SERVICE";
    private static final Double REDUCE_SUM_VALUE = 0.0;

    private final JsonUtil jsonUtil;
    private final KafkaProducer kafkaProducer;

    @Autowired
    PaymentRepository paymentRepository;

    public void realizePayment(Event event){
        try {
            checkCurrentValidation(event);
            createPendingPayment(event);
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

    private void save(Payment payment){
        paymentRepository.save(payment);
    }
}
