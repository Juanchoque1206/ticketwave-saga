package com.ticketwave.infrastructure.controller;

import com.ticketwave.domain.bus.CommandBus;
import com.ticketwave.domain.bus.EventBus;
import com.ticketwave.domain.commands.*;
import com.ticketwave.domain.events.*;
import com.ticketwave.domain.saga.SagaState;
import com.ticketwave.domain.saga.SagaStateRepository;
import com.ticketwave.domain.saga.TicketOrderSagaOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Test controller for manually triggering and inspecting the saga pattern
 * components: event producers, command producers, event/command subscribers,
 * and the orchestrator itself.
 * <p>
 * This controller is intended for development and testing only.
 */
@RestController
@RequestMapping("/api/test/saga")
@Tag(name = "Saga Test", description = "Endpoints for testing the saga orchestrator, producers, and subscribers")
public class SagaTestController {

    private static final Logger log = LoggerFactory.getLogger(SagaTestController.class);

    private final EventBus eventBus;
    private final CommandBus commandBus;
    private final SagaStateRepository sagaRepository;
    private final TicketOrderSagaOrchestrator orchestrator;

    public SagaTestController(EventBus eventBus,
                              CommandBus commandBus,
                              SagaStateRepository sagaRepository,
                              TicketOrderSagaOrchestrator orchestrator) {
        this.eventBus = eventBus;
        this.commandBus = commandBus;
        this.sagaRepository = sagaRepository;
        this.orchestrator = orchestrator;
    }

    // ──────────────────────────────────────────────────────────────────────
    // 1. FULL SAGA FLOW (happy path & compensation)
    // ──────────────────────────────────────────────────────────────────────

    @PostMapping("/flow/happy-path")
    @Operation(summary = "Run the complete happy-path saga flow",
            description = "Publishes TicketOrderCreated → triggers payment → ticket issuance → notification → saga completes")
    public ResponseEntity<Map<String, Object>> runHappyPath(@RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        log.info("TEST: Starting happy-path saga flow for orderId={}", req.orderId);

        eventBus.publish(new TicketOrderCreated(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, req.eventId, req.quantity,
                req.total, BigDecimal.ZERO));

        return ResponseEntity.ok(sagaSnapshot(req.orderId, "happy-path flow triggered"));
    }

    @PostMapping("/flow/payment-failure")
    @Operation(summary = "Run a saga flow where payment fails",
            description = "Publishes TicketOrderCreated then PaymentFailed → triggers compensation (CancelTicketOrderCommand)")
    public ResponseEntity<Map<String, Object>> runPaymentFailure(@RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        log.info("TEST: Starting payment-failure saga flow for orderId={}", req.orderId);

        eventBus.publish(new TicketOrderCreated(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, req.eventId, req.quantity,
                req.total, BigDecimal.ZERO));

        eventBus.publish(new PaymentFailed(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, req.total, "Simulated payment failure"));

        return ResponseEntity.ok(sagaSnapshot(req.orderId, "payment-failure flow triggered"));
    }

    @PostMapping("/flow/ticket-delivery-failure")
    @Operation(summary = "Run a saga flow where ticket delivery fails",
            description = "TicketOrderCreated → PaymentAuthorized → TicketDeliveryFailed → RefundPaymentCommand compensation")
    public ResponseEntity<Map<String, Object>> runTicketDeliveryFailure(@RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        log.info("TEST: Starting ticket-delivery-failure saga flow for orderId={}", req.orderId);

        eventBus.publish(new TicketOrderCreated(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, req.eventId, req.quantity,
                req.total, BigDecimal.ZERO));

        eventBus.publish(new PaymentAuthorized(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, req.total, "TXN-TEST-" + UUID.randomUUID()));

        eventBus.publish(new TicketDeliveryFailed(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, "Simulated ticket delivery failure"));

        return ResponseEntity.ok(sagaSnapshot(req.orderId, "ticket-delivery-failure flow triggered"));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 2. EVENT PRODUCERS (publish individual domain events)
    // ──────────────────────────────────────────────────────────────────────

    @PostMapping("/events/ticket-order-created")
    @Operation(summary = "Publish a TicketOrderCreated event",
            description = "Triggers saga creation and ProcessPaymentCommand")
    public ResponseEntity<Map<String, Object>> publishTicketOrderCreated(
            @RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        TicketOrderCreated event = new TicketOrderCreated(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, req.eventId, req.quantity, req.total, BigDecimal.ZERO);
        eventBus.publish(event);
        log.info("TEST: Published TicketOrderCreated for orderId={}", req.orderId);
        return ResponseEntity.ok(Map.of("event", "TicketOrderCreated", "orderId", req.orderId));
    }

    @PostMapping("/events/payment-authorized")
    @Operation(summary = "Publish a PaymentAuthorized event",
            description = "Advances saga to issue tickets (IssueTicketCommand)")
    public ResponseEntity<Map<String, Object>> publishPaymentAuthorized(
            @RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        PaymentAuthorized event = new PaymentAuthorized(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, req.total, "TXN-TEST-" + UUID.randomUUID());
        eventBus.publish(event);
        log.info("TEST: Published PaymentAuthorized for orderId={}", req.orderId);
        return ResponseEntity.ok(Map.of("event", "PaymentAuthorized", "orderId", req.orderId));
    }

    @PostMapping("/events/payment-failed")
    @Operation(summary = "Publish a PaymentFailed event",
            description = "Triggers saga compensation (CancelTicketOrderCommand)")
    public ResponseEntity<Map<String, Object>> publishPaymentFailed(
            @RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        PaymentFailed event = new PaymentFailed(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, req.total, "Simulated payment failure");
        eventBus.publish(event);
        log.info("TEST: Published PaymentFailed for orderId={}", req.orderId);
        return ResponseEntity.ok(Map.of("event", "PaymentFailed", "orderId", req.orderId));
    }

    @PostMapping("/events/ticket-issued")
    @Operation(summary = "Publish a TicketIssued event",
            description = "Advances saga to notification step (NotifyOrderCommand)")
    public ResponseEntity<Map<String, Object>> publishTicketIssued(
            @RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        List<UUID> ticketIds = List.of(UUID.randomUUID(), UUID.randomUUID());
        TicketIssued event = new TicketIssued(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, req.eventId, ticketIds);
        eventBus.publish(event);
        log.info("TEST: Published TicketIssued for orderId={}", req.orderId);
        return ResponseEntity.ok(Map.of("event", "TicketIssued", "orderId", req.orderId, "ticketIds", ticketIds));
    }

    @PostMapping("/events/ticket-delivery-failed")
    @Operation(summary = "Publish a TicketDeliveryFailed event",
            description = "Triggers saga compensation (RefundPaymentCommand)")
    public ResponseEntity<Map<String, Object>> publishTicketDeliveryFailed(
            @RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        TicketDeliveryFailed event = new TicketDeliveryFailed(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, "Simulated ticket delivery failure");
        eventBus.publish(event);
        log.info("TEST: Published TicketDeliveryFailed for orderId={}", req.orderId);
        return ResponseEntity.ok(Map.of("event", "TicketDeliveryFailed", "orderId", req.orderId));
    }

    @PostMapping("/events/notification-sent")
    @Operation(summary = "Publish a NotificationSent event",
            description = "Completes the saga successfully")
    public ResponseEntity<Map<String, Object>> publishNotificationSent(
            @RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        UUID notificationId = UUID.randomUUID();
        NotificationSent event = new NotificationSent(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, notificationId);
        eventBus.publish(event);
        log.info("TEST: Published NotificationSent for orderId={}", req.orderId);
        return ResponseEntity.ok(Map.of("event", "NotificationSent", "orderId", req.orderId,
                "notificationId", notificationId));
    }

    @PostMapping("/events/notification-failed")
    @Operation(summary = "Publish a NotificationFailed event",
            description = "Saga still completes (notification failure is non-compensating)")
    public ResponseEntity<Map<String, Object>> publishNotificationFailed(
            @RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        NotificationFailed event = new NotificationFailed(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, "Simulated notification failure");
        eventBus.publish(event);
        log.info("TEST: Published NotificationFailed for orderId={}", req.orderId);
        return ResponseEntity.ok(Map.of("event", "NotificationFailed", "orderId", req.orderId));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 3. COMMAND PRODUCERS (send individual commands through the CommandBus)
    // ──────────────────────────────────────────────────────────────────────

    @PostMapping("/commands/process-payment")
    @Operation(summary = "Send a ProcessPaymentCommand",
            description = "Triggers the PaymentService to process a payment")
    public ResponseEntity<Map<String, Object>> sendProcessPayment(
            @RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        ProcessPaymentCommand cmd = new ProcessPaymentCommand(UUID.randomUUID(), Instant.now(),
                req.orderId, "STRIPE", req.total);
        commandBus.send(cmd);
        log.info("TEST: Sent ProcessPaymentCommand for orderId={}", req.orderId);
        return ResponseEntity.ok(Map.of("command", "ProcessPaymentCommand", "orderId", req.orderId));
    }

    @PostMapping("/commands/issue-ticket")
    @Operation(summary = "Send an IssueTicketCommand",
            description = "Triggers ticket issuance for an order")
    public ResponseEntity<Map<String, Object>> sendIssueTicket(
            @RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        IssueTicketCommand cmd = new IssueTicketCommand(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, req.eventId, req.quantity);
        commandBus.send(cmd);
        log.info("TEST: Sent IssueTicketCommand for orderId={}", req.orderId);
        return ResponseEntity.ok(Map.of("command", "IssueTicketCommand", "orderId", req.orderId));
    }

    @PostMapping("/commands/notify-order")
    @Operation(summary = "Send a NotifyOrderCommand",
            description = "Triggers notification for order completion")
    public ResponseEntity<Map<String, Object>> sendNotifyOrder(
            @RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        NotifyOrderCommand cmd = new NotifyOrderCommand(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, req.eventId);
        commandBus.send(cmd);
        log.info("TEST: Sent NotifyOrderCommand for orderId={}", req.orderId);
        return ResponseEntity.ok(Map.of("command", "NotifyOrderCommand", "orderId", req.orderId));
    }

    @PostMapping("/commands/cancel-order")
    @Operation(summary = "Send a CancelTicketOrderCommand",
            description = "Triggers order cancellation (compensation step)")
    public ResponseEntity<Map<String, Object>> sendCancelOrder(
            @RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        CancelTicketOrderCommand cmd = new CancelTicketOrderCommand(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, req.eventId, req.quantity, "Test cancellation");
        commandBus.send(cmd);
        log.info("TEST: Sent CancelTicketOrderCommand for orderId={}", req.orderId);
        return ResponseEntity.ok(Map.of("command", "CancelTicketOrderCommand", "orderId", req.orderId));
    }

    @PostMapping("/commands/refund-payment")
    @Operation(summary = "Send a RefundPaymentCommand",
            description = "Triggers payment refund (compensation step)")
    public ResponseEntity<Map<String, Object>> sendRefundPayment(
            @RequestBody(required = false) SagaFlowRequest request) {
        SagaFlowRequest req = defaults(request);
        RefundPaymentCommand cmd = new RefundPaymentCommand(UUID.randomUUID(), Instant.now(),
                req.orderId, req.userId, req.total, "Test refund");
        commandBus.send(cmd);
        log.info("TEST: Sent RefundPaymentCommand for orderId={}", req.orderId);
        return ResponseEntity.ok(Map.of("command", "RefundPaymentCommand", "orderId", req.orderId));
    }

    // ──────────────────────────────────────────────────────────────────────
    // 4. ORCHESTRATOR MANAGEMENT
    // ──────────────────────────────────────────────────────────────────────

    @PostMapping("/orchestrator/recover")
    @Operation(summary = "Trigger saga recovery",
            description = "Re-drives all incomplete sagas (CREATED or RUNNING)")
    public ResponseEntity<Map<String, Object>> recover() {
        orchestrator.recover();
        log.info("TEST: Saga recovery triggered");

        List<Map<String, Object>> incomplete = sagaRepository.findAll().stream()
                .filter(s -> s.status().name().equals("CREATED") || s.status().name().equals("RUNNING"))
                .map(this::stateToMap)
                .toList();

        return ResponseEntity.ok(Map.of("action", "recover", "incompleteSagas", incomplete));
    }

    @PostMapping("/orchestrator/recover/{sagaId}")
    @Operation(summary = "Recover a specific saga by ID")
    public ResponseEntity<Map<String, Object>> recoverSaga(@PathVariable UUID sagaId) {
        orchestrator.recoverSaga(sagaId);
        log.info("TEST: Recovery triggered for sagaId={}", sagaId);

        return sagaRepository.findById(sagaId)
                .map(state -> ResponseEntity.ok(Map.of("action", "recover-single", "saga", (Object) stateToMap(state))))
                .orElse(ResponseEntity.notFound().build());
    }

    // ──────────────────────────────────────────────────────────────────────
    // 5. SAGA STATE INSPECTION
    // ──────────────────────────────────────────────────────────────────────

    @GetMapping("/state")
    @Operation(summary = "List all saga states")
    public ResponseEntity<List<Map<String, Object>>> listAll() {
        List<Map<String, Object>> sagas = sagaRepository.findAll().stream()
                .map(this::stateToMap)
                .toList();
        return ResponseEntity.ok(sagas);
    }

    @GetMapping("/state/{sagaId}")
    @Operation(summary = "Get saga state by saga ID")
    public ResponseEntity<Map<String, Object>> getBySagaId(@PathVariable UUID sagaId) {
        return sagaRepository.findById(sagaId)
                .map(state -> ResponseEntity.ok(stateToMap(state)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/state/order/{orderId}")
    @Operation(summary = "Get saga state by order ID")
    public ResponseEntity<Map<String, Object>> getByOrderId(@PathVariable UUID orderId) {
        return sagaRepository.findByOrderId(orderId)
                .map(state -> ResponseEntity.ok(stateToMap(state)))
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/state/{sagaId}")
    @Operation(summary = "Delete a saga state by saga ID")
    public ResponseEntity<Void> deleteSaga(@PathVariable UUID sagaId) {
        sagaRepository.deleteById(sagaId);
        log.info("TEST: Deleted sagaId={}", sagaId);
        return ResponseEntity.noContent().build();
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    private Map<String, Object> sagaSnapshot(UUID orderId, String action) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        result.put("orderId", orderId);
        sagaRepository.findByOrderId(orderId)
                .ifPresent(state -> result.put("sagaState", stateToMap(state)));
        return result;
    }

    private Map<String, Object> stateToMap(SagaState state) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sagaId", state.sagaId());
        map.put("orderId", state.orderId());
        map.put("userId", state.userId());
        map.put("eventId", state.eventId());
        map.put("quantity", state.quantity());
        map.put("total", state.total());
        map.put("status", state.status().name());
        map.put("currentStep", state.currentStep().name());
        map.put("error", state.error());
        map.put("payload", state.payload());
        map.put("createdAt", state.createdAt());
        map.put("updatedAt", state.updatedAt());
        return map;
    }

    private SagaFlowRequest defaults(SagaFlowRequest request) {
        if (request == null) {
            request = new SagaFlowRequest();
        }
        if (request.orderId == null) request.orderId = UUID.randomUUID();
        if (request.userId == null) request.userId = UUID.randomUUID();
        if (request.eventId == null) request.eventId = UUID.randomUUID();
        if (request.quantity <= 0) request.quantity = 2;
        if (request.total == null) request.total = new BigDecimal("200.00");
        return request;
    }

    /**
     * Optional request body for saga test endpoints. All fields default to
     * random UUIDs / sensible values when not provided.
     */
    static class SagaFlowRequest {
        public UUID orderId;
        public UUID userId;
        public UUID eventId;
        public int quantity;
        public BigDecimal total;
    }
}
