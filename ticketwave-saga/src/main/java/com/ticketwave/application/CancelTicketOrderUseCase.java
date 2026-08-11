package com.ticketwave.application;

import com.ticketwave.domain.bus.CommandBus;
import com.ticketwave.domain.commands.CancelTicketOrderCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compensating step of the saga: when payment fails the pending order is
 * cancelled and its reserved capacity is released, publishing
 * TicketOrderCancelled so the orchestrator converges to COMPENSATED.
 */
@Service
public class CancelTicketOrderUseCase {

    private final TicketOrderService orderService;

    public CancelTicketOrderUseCase(TicketOrderService orderService, CommandBus commandBus) {
        this.orderService = orderService;
        commandBus.subscribe(CancelTicketOrderCommand.class, this::cancel);
    }

    @Transactional
    public void cancel(CancelTicketOrderCommand command) {
        orderService.cancelOrder(command.orderId());
    }
}