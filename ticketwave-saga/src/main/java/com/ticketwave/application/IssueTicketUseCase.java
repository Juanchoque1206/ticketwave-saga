package com.ticketwave.application;

import com.ticketwave.domain.bus.CommandBus;
import com.ticketwave.domain.bus.EventBus;
import com.ticketwave.domain.commands.IssueTicketCommand;
import com.ticketwave.domain.events.TicketDeliveryFailed;
import com.ticketwave.domain.events.TicketIssued;
import com.ticketwave.domain.order.OrderStatus;
import com.ticketwave.domain.order.TicketOrder;
import com.ticketwave.domain.order.TicketOrderRepository;
import com.ticketwave.domain.ticket.Ticket;
import com.ticketwave.domain.ticket.TicketRepository;
import com.ticketwave.infrastructure.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Executes the ticket issuance step of the saga: confirms the pending order and
 * emits the digital tickets, publishing TicketIssued (or TicketDeliveryFailed
 * on any error so the orchestrator can compensate).
 */
@Service
public class IssueTicketUseCase {

    private final TicketOrderService orderService;
    private final TicketOrderRepository orderRepository;
    private final TicketRepository ticketRepository;
    private final EventBus eventBus;

    public IssueTicketUseCase(TicketOrderService orderService,
                              TicketOrderRepository orderRepository,
                              TicketRepository ticketRepository,
                              EventBus eventBus,
                              CommandBus commandBus) {
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.ticketRepository = ticketRepository;
        this.eventBus = eventBus;
        commandBus.subscribe(IssueTicketCommand.class, this::issue);
    }

    @Transactional
    public void issue(IssueTicketCommand command) {
        try {
            TicketOrder order = orderRepository.findById(command.orderId())
                    .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

            List<UUID> ticketIds;
            if (order.getStatus() == OrderStatus.CONFIRMED) {
                ticketIds = ticketRepository.findByOrderId(order.getId())
                        .stream().map(Ticket::getId).toList();
            } else {
                ticketIds = orderService.confirmOrder(order.getId())
                        .stream().map(Ticket::getId).toList();
            }

            eventBus.publish(new TicketIssued(UUID.randomUUID(), Instant.now(),
                    order.getId(), command.userId(), command.eventId(), ticketIds));
        } catch (Exception ex) {
            eventBus.publish(new TicketDeliveryFailed(UUID.randomUUID(), Instant.now(),
                    command.orderId(), command.userId(), ex.getMessage()));
        }
    }
}