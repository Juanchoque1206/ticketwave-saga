package com.ticketwave.application;

import com.ticketwave.domain.bus.EventBus;
import com.ticketwave.domain.events.TicketOrderCreated;
import com.ticketwave.domain.user.AppUser;
import com.ticketwave.infrastructure.dto.order.CreateOrderRequest;
import com.ticketwave.infrastructure.dto.order.OrderResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class ReserveTicketUseCase {

    private final TicketOrderService orderService;
    private final UserService userService;
    private final EventBus eventBus;

    public ReserveTicketUseCase(TicketOrderService orderService, UserService userService, EventBus eventBus) {
        this.orderService = orderService;
        this.userService = userService;
        this.eventBus = eventBus;
    }

    @Transactional
    public OrderResponse reserve(TicketOrderService.AuthenticationContext ctx, CreateOrderRequest request) {
        OrderResponse response = orderService.createReservation(ctx, request);
        AppUser user = userService.findByUsername(ctx.username());
        eventBus.publish(new TicketOrderCreated(
                UUID.randomUUID(),
                Instant.now(),
                response.id(),
                user.getId(),
                response.eventId(),
                response.quantity(),
                response.totalAmount(),
                response.discountAmount()));
        return response;
    }
}