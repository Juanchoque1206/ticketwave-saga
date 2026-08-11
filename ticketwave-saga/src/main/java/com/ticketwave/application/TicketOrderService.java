package com.ticketwave.application;

import com.ticketwave.domain.bus.EventBus;
import com.ticketwave.domain.event.Event;
import com.ticketwave.domain.event.EventStatus;
import com.ticketwave.domain.events.PromotionApplied;
import com.ticketwave.domain.events.TicketOrderCancelled;
import com.ticketwave.domain.order.OrderStatus;
import com.ticketwave.domain.order.TicketOrder;
import com.ticketwave.domain.promotion.Promotion;
import com.ticketwave.domain.promotion.PromotionScope;
import com.ticketwave.domain.promotion.PromotionType;
import com.ticketwave.domain.ticket.Ticket;
import com.ticketwave.domain.ticket.TicketStatus;
import com.ticketwave.domain.user.AppUser;
import com.ticketwave.domain.user.Role;
import com.ticketwave.domain.venue.Venue;
import com.ticketwave.infrastructure.dto.event.EventResponse;
import com.ticketwave.infrastructure.dto.order.CreateOrderRequest;
import com.ticketwave.infrastructure.dto.order.OrderResponse;
import com.ticketwave.infrastructure.exception.BusinessRuleException;
import com.ticketwave.infrastructure.exception.OrderStateException;
import com.ticketwave.infrastructure.exception.ResourceNotFoundException;
import com.ticketwave.domain.order.TicketOrderRepository;
import com.ticketwave.domain.ticket.TicketRepository;
import com.ticketwave.domain.order.PriceCalculator;
import com.ticketwave.infrastructure.util.QrCodeGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class TicketOrderService {

    private final TicketOrderRepository orderRepository;
    private final EventService eventService;
    private final UserService userService;
    private final PromotionService promotionService;
    private final FraudService fraudService;
    private final TicketRepository ticketRepository;
    private final EventBus eventBus;
    private final long orderTtlMinutes;

    public TicketOrderService(TicketOrderRepository orderRepository,
                              EventService eventService,
                              UserService userService,
                              PromotionService promotionService,
                              FraudService fraudService,
                              TicketRepository ticketRepository,
                              EventBus eventBus,
                              @Value("${ticketwave.order-ttl-minutes:15}") long orderTtlMinutes) {
        this.orderRepository = orderRepository;
        this.eventService = eventService;
        this.userService = userService;
        this.promotionService = promotionService;
        this.fraudService = fraudService;
        this.ticketRepository = ticketRepository;
        this.eventBus = eventBus;
        this.orderTtlMinutes = orderTtlMinutes;
    }

    @Transactional
    public OrderResponse createReservation(AuthenticationContext ctx, CreateOrderRequest request) {
        AppUser user = userService.findByUsername(ctx.username());
        fraudService.guard(user, ctx.ipAddress());

        Event event = eventService.getEntity(request.eventId());
        EventResponse capacity = eventService.reserveCapacity(event.getId(), request.quantity());
        if (capacity.availableCount() < request.quantity()) {
            eventService.releaseCapacity(event.getId(), request.quantity());
            throw new BusinessRuleException("Not enough capacity available");
        }

        TicketOrder order = new TicketOrder();
        order.setUser(user);
        order.setEvent(event);
        order.setQuantity(request.quantity());
        order.setReservedAt(LocalDateTime.now());
        order.setExpiresAt(LocalDateTime.now().plusMinutes(orderTtlMinutes));
        order.setStatus(OrderStatus.PENDING);

        BigDecimal subtotal = event.getBasePrice().multiply(BigDecimal.valueOf(request.quantity()));
        BigDecimal discount = BigDecimal.ZERO;
        String appliedPromotionCode = null;
        if (request.promotionCode() != null && !request.promotionCode().isBlank()) {
            Promotion promotion = promotionService.findByCode(request.promotionCode());
            discount = promotionService.discountFor(promotion, event, request.quantity(), subtotal);
            appliedPromotionCode = promotion.getCode();
            promotionService.incrementUsage(promotion);
        }
        order = orderRepository.save(order);

        if (appliedPromotionCode != null) {
            eventBus.publish(new PromotionApplied(UUID.randomUUID(), Instant.now(),
                    order.getId(), appliedPromotionCode, discount));
        }

        fraudService.markOrder(order.getId(), user);
        return toResponse(order, discount);
    }

    @Transactional
    public List<Ticket> confirmOrder(UUID orderId) {
        TicketOrder order = getPendingOrder(orderId);
        order.setStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);
        return emitTickets(order);
    }

    @Transactional
    public List<Ticket> emitTickets(TicketOrder order) {
        List<Ticket> tickets = new ArrayList<>();
        for (int i = 0; i < order.getQuantity(); i++) {
            Ticket ticket = new Ticket();
            ticket.setOrder(order);
            ticket.setEvent(order.getEvent());
            ticket.setPrice(order.getEvent().getBasePrice());
            ticket.setSeat("Row-" + (i + 1));
            ticket.setStatus(TicketStatus.EMITTED);
            ticket.setIssuedAt(LocalDateTime.now());
            ticket.setQrCode(QrCodeGenerator.generate(
                    order.getId().toString(), UUID.randomUUID().toString(), order.getEvent().getId().toString()));
            tickets.add(ticketRepository.save(ticket));
        }
        return tickets;
    }

    @Transactional
    public void cancelOrder(UUID orderId) {
        TicketOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new OrderStateException("Only pending orders can be cancelled");
        }
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
        eventService.releaseCapacity(order.getEvent().getId(), order.getQuantity());
        eventBus.publish(new TicketOrderCancelled(UUID.randomUUID(), Instant.now(),
                order.getId(), order.getUser().getId(), order.getEvent().getId(), order.getQuantity()));
    }

    @Transactional
    public void expireOrder(UUID orderId) {
        TicketOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (order.getStatus() == OrderStatus.PENDING && PriceCalculator.isExpired(order)) {
            order.setStatus(OrderStatus.EXPIRED);
            orderRepository.save(order);
            eventService.releaseCapacity(order.getEvent().getId(), order.getQuantity());
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID orderId) {
        TicketOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        return toResponse(order, BigDecimal.ZERO);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listOrdersForUser(AuthenticationContext ctx) {
        AppUser user = userService.findByUsername(ctx.username());
        return orderRepository.findByUserId(user.getId())
                .stream().map(o -> toResponse(o, BigDecimal.ZERO)).toList();
    }

    private TicketOrder getPendingOrder(UUID orderId) {
        TicketOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new OrderStateException("Order is not pending");
        }
        if (PriceCalculator.isExpired(order)) {
            throw new OrderStateException("Order has expired");
        }
        return order;
    }

    private OrderResponse toResponse(TicketOrder order, BigDecimal discount) {
        List<UUID> ticketIds = ticketRepository.findByOrderId(order.getId())
                .stream().map(Ticket::getId).toList();
        return toResponse(order, discount, ticketIds);
    }

    private OrderResponse toResponse(TicketOrder order, BigDecimal discount, List<UUID> ticketIds) {
        BigDecimal subtotal = order.getEvent().getBasePrice().multiply(BigDecimal.valueOf(order.getQuantity()));
        return new OrderResponse(order.getId(), order.getEvent().getId(), order.getEvent().getName(),
                order.getStatus(), order.getQuantity(), subtotal.subtract(discount), discount,
                order.getReservedAt(), order.getExpiresAt(), ticketIds);
    }

    public record AuthenticationContext(String username, String ipAddress) {
    }
}