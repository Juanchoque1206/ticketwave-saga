package com.ticketwave.application;

import com.ticketwave.domain.order.OrderStatus;
import com.ticketwave.domain.order.TicketOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class OrderExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryJob.class);

    private final TicketOrderRepository orderRepository;
    private final TicketOrderService orderService;

    public OrderExpiryJob(TicketOrderRepository orderRepository, TicketOrderService orderService) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
    }

    @Scheduled(cron = "${ticketwave.order-expiry-cron:*/30 * * * * *}")
    public void expirePendingOrders() {
        orderRepository.findAll().stream()
                .filter(o -> o.getStatus() == OrderStatus.PENDING)
                .filter(o -> o.getExpiresAt() != null && o.getExpiresAt().isBefore(LocalDateTime.now()))
                .forEach(o -> {
                    orderService.expireOrder(o.getId());
                    log.info("Expired order {}", o.getId());
                });
    }
}