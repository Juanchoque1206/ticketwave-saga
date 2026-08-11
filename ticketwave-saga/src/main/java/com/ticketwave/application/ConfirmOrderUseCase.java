package com.ticketwave.application;

import com.ticketwave.domain.bus.CommandBus;
import com.ticketwave.domain.bus.EventBus;
import com.ticketwave.domain.commands.ProcessPaymentCommand;
import com.ticketwave.domain.events.PaymentAuthorized;
import com.ticketwave.domain.events.PaymentFailed;
import com.ticketwave.domain.order.TicketOrder;
import com.ticketwave.domain.order.TicketOrderRepository;
import com.ticketwave.domain.payment.PaymentProvider;
import com.ticketwave.domain.payment.PaymentStatus;
import com.ticketwave.infrastructure.dto.payment.CreatePaymentRequest;
import com.ticketwave.infrastructure.dto.payment.PaymentResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Executes the payment step of the saga: processes a ProcessPaymentCommand,
 * charges the order and publishes PaymentAuthorized (or PaymentFailed so the
 * orchestrator can compensate). Registered both as the command handler and as
 * the target of the legacy REST confirm endpoint.
 */
@Service
public class ConfirmOrderUseCase {

    private final PaymentService paymentService;
    private final TicketOrderRepository orderRepository;
    private final EventBus eventBus;
    private final TransactionTemplate transactionTemplate;

    public ConfirmOrderUseCase(PaymentService paymentService,
                               TicketOrderRepository orderRepository,
                               EventBus eventBus,
                               CommandBus commandBus,
                               TransactionTemplate transactionTemplate) {
        this.paymentService = paymentService;
        this.orderRepository = orderRepository;
        this.eventBus = eventBus;
        this.transactionTemplate = transactionTemplate;
        commandBus.subscribe(ProcessPaymentCommand.class, this::processPayment);
    }

    @Transactional
    public PaymentResponse confirm(CreatePaymentRequest request) {
        return processPayment(new ProcessPaymentCommand(UUID.randomUUID(), Instant.now(),
                request.orderId(), request.provider().name(), null));
    }

    public PaymentResponse processPayment(ProcessPaymentCommand command) {
        return transactionTemplate.execute(status -> doProcessPayment(command));
    }

    private PaymentResponse doProcessPayment(ProcessPaymentCommand command) {
        TicketOrder order = orderRepository.findById(command.orderId()).orElse(null);
        if (order == null) {
            eventBus.publish(new PaymentFailed(UUID.randomUUID(), Instant.now(),
                    command.orderId(), null, command.amount(), "Order not found"));
            return failedResponse(command, command.amount());
        }

        BigDecimal total = orderTotal(order);
        try {
            PaymentResponse payment = paymentService.create(
                    new CreatePaymentRequest(command.orderId(), PaymentProvider.valueOf(command.provider())));
            eventBus.publish(new PaymentAuthorized(UUID.randomUUID(), Instant.now(),
                    order.getId(), order.getUser().getId(), payment.amount(), payment.providerTransactionId()));
            return payment;
        } catch (Exception ex) {
            eventBus.publish(new PaymentFailed(UUID.randomUUID(), Instant.now(),
                    order.getId(), order.getUser().getId(), total, ex.getMessage()));
            return failedResponse(command, total);
        }
    }

    private BigDecimal orderTotal(TicketOrder order) {
        return order.getEvent().getBasePrice().multiply(BigDecimal.valueOf(order.getQuantity()));
    }

    private PaymentResponse failedResponse(ProcessPaymentCommand command, BigDecimal amount) {
        return new PaymentResponse(null, command.orderId(),
                PaymentProvider.valueOf(command.provider()), PaymentStatus.FAILED, amount, null, null, null);
    }
}