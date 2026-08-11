package com.ticketwave.infrastructure.bus;

import com.ticketwave.domain.bus.EventBus;
import com.ticketwave.domain.bus.EventHandler;
import com.ticketwave.domain.events.DomainEvent;
import jakarta.annotation.PostConstruct;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Production transport backed by RabbitMQ. Published events are sent to a shared
 * topic exchange and consumed through an application-specific bound queue so
 * every service instance reacts to the full event stream without competing for
 * messages. Used when the rabbitmq profile is active.
 */
public class RabbitMQEventBusAdapter implements EventBus {

    public static final String EXCHANGE = "ticketwave.events";
    public static final String ROUTING_KEY = "#";

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final String queue;
    private final Map<Class<?>, List<Consumer<DomainEvent>>> handlers = new ConcurrentHashMap<>();

    public RabbitMQEventBusAdapter(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin, String queue) {
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
        this.queue = queue;
    }

    @PostConstruct
    void declareTopology() {
        if (amqpAdmin == null) {
            return;
        }
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        Queue clientQueue = new Queue(queue, true);
        Binding binding = BindingBuilder.bind(clientQueue).to(exchange).with(ROUTING_KEY);
        amqpAdmin.declareExchange(exchange);
        amqpAdmin.declareQueue(clientQueue);
        amqpAdmin.declareBinding(binding);
    }

    @Override
    public void publish(DomainEvent event) {
        rabbitTemplate.convertAndSend(EXCHANGE, event.getClass().getSimpleName(), event);
    }

    @RabbitListener(queues = "${ticketwave.bus.event-queue}")
    public void onMessage(DomainEvent event) {
        for (Consumer<DomainEvent> consumer : handlers.getOrDefault(event.getClass(), List.of())) {
            consumer.accept(event);
        }
    }

    @Override
    public <E extends DomainEvent> void subscribe(Class<E> eventType, EventHandler<E> handler) {
        handlers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(event -> handler.handle(eventType.cast(event)));
    }
}