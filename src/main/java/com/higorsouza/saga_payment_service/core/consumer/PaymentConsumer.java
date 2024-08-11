package com.higorsouza.saga_payment_service.core.consumer;

import com.higorsouza.saga_payment_service.core.service.PaymentService;
import com.higorsouza.saga_payment_service.core.utils.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@AllArgsConstructor
public class PaymentConsumer {

    private final JsonUtil jsonUtil;

    @Autowired
    PaymentService paymentService;

    @KafkaListener(
            groupId = "${spring.kafka.consumer.group-id}",
            topics = "${spring.kafka.topic.payment-success}"
    )
    public void consumerSuccessEvent(String payload) {
        log.info("Receiving success event {} from payment-success topic", payload);
        var event = jsonUtil.toEvent(payload);
        log.info(event.toString());
        paymentService.realizePayment(event);
    }

    @KafkaListener(
            groupId = "${spring.kafka.consumer.group-id}",
            topics = "${spring.kafka.topic.payment-fail}"
    )
    public void consumerFailEvent(String payload) {
        log.info("Receiving rollback event {} from payment-fail topic", payload);
        var event = jsonUtil.toEvent(payload);
        log.info(event.toString());
        paymentService.realizeRefound(event);
    }
}
