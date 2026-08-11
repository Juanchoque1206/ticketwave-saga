package com.ticketwave;

import com.ticketwave.domain.event.Event;
import com.ticketwave.domain.event.EventStatus;
import com.ticketwave.domain.notification.Notification;
import com.ticketwave.domain.notification.NotificationChannel;
import com.ticketwave.domain.notification.NotificationType;
import com.ticketwave.domain.order.OrderItem;
import com.ticketwave.domain.order.OrderStatus;
import com.ticketwave.domain.order.TicketOrder;
import com.ticketwave.domain.payment.Payment;
import com.ticketwave.domain.payment.PaymentProvider;
import com.ticketwave.domain.payment.PaymentStatus;
import com.ticketwave.domain.promotion.Promotion;
import com.ticketwave.domain.promotion.PromotionScope;
import com.ticketwave.domain.promotion.PromotionType;
import com.ticketwave.domain.ticket.Ticket;
import com.ticketwave.domain.ticket.TicketStatus;
import com.ticketwave.domain.user.AppUser;
import com.ticketwave.domain.user.Role;
import com.ticketwave.domain.venue.Venue;
import com.ticketwave.domain.event.EventRepository;
import com.ticketwave.domain.notification.NotificationRepository;
import com.ticketwave.domain.order.TicketOrderRepository;
import com.ticketwave.domain.payment.PaymentRepository;
import com.ticketwave.domain.promotion.PromotionRepository;
import com.ticketwave.domain.ticket.TicketRepository;
import com.ticketwave.domain.user.UserRepository;
import com.ticketwave.domain.venue.VenueRepository;
import com.ticketwave.application.AuthService;
import com.ticketwave.application.EventService;
import com.ticketwave.application.FraudService;
import com.ticketwave.application.NotificationService;
import com.ticketwave.application.OrderExpiryJob;
import com.ticketwave.application.PaymentService;
import com.ticketwave.application.PromotionService;
import com.ticketwave.application.TicketOrderService;
import com.ticketwave.application.TicketService;
import com.ticketwave.application.UserService;
import com.ticketwave.infrastructure.security.JwtService;
import com.ticketwave.infrastructure.security.UserDetailsServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
class TicketOrderFlowIntegrationTest {

    @Autowired
    private TicketOrderService orderService;
    @Autowired
    private TicketService ticketService;
    @Autowired
    private TicketOrderRepository orderRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PromotionRepository promotionRepository;

    @Test
    @Transactional
    void reservation_confirmationAndEmission_flow() {
        AppUser user = createUser();
        Event event = createEvent();

        TicketOrderService.AuthenticationContext ctx =
                new TicketOrderService.AuthenticationContext(user.getUsername(), "127.0.0.1");

        TicketOrder order = new TicketOrder();
        order.setUser(user);
        order.setEvent(event);
        order.setQuantity(2);
        order.setReservedAt(LocalDateTime.now());
        order.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        order.setStatus(OrderStatus.PENDING);

        order = orderRepository.save(order);
        var tickets = orderService.confirmOrder(order.getId());
        assertNotNull(tickets);
        assertEquals(2, tickets.size());
    }

    private AppUser createUser() {
        AppUser user = new AppUser();
        user.setUsername("tester-" + UUID.randomUUID());
        user.setEmail("tester-" + UUID.randomUUID() + "@mail.com");
        user.setPassword("$2a$10$dummyhash");
        user.setRole(Role.USER);
        return userRepository.save(user);
    }

    private Event createEvent() {
        Event event = new Event();
        event.setName("Test Event");
        event.setCity("Lima");
        event.setVenue("Test Venue");
        event.setEventDate(LocalDateTime.now().plusDays(10));
        event.setBasePrice(new BigDecimal("100.00"));
        event.setTotalCapacity(100);
        event.setStatus(EventStatus.PUBLISHED);
        return eventRepository.save(event);
    }
}
