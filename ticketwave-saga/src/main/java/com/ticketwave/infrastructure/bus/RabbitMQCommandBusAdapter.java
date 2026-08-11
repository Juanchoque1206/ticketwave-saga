package com.ticketwave.infrastructure.bus;

import com.ticketwave.domain.bus.CommandBus;
import com.ticketwave.domain.commands.Command;
import com.ticketwave.domain.commands.CommandHandler;
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
 * Production command transport. Commands are routed through a dedicated
 * {@code ticketwave.commands} topic exchange (separate from the event exchange)
 * and consumed through an application-specific bound queue so every service
 * instance that should react to them receives a full copy. Used when the
 * rabbitmq profile is active.
 */
public class RabbitMQCommandBusAdapter implements CommandBus {

    public static final String EXCHANGE = "ticketwave.commands";
    public static final String ROUTING_KEY = "#";

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final String queue;
    private final Map<Class<?>, List<Consumer<Command>>> handlers = new ConcurrentHashMap<>();

    public RabbitMQCommandBusAdapter(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin, String queue) {
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
    public void send(Command command) {
        rabbitTemplate.convertAndSend(EXCHANGE, command.getClass().getSimpleName(), command);
    }

    @RabbitListener(queues = "${ticketwave.bus.command-queue}")
    public void onMessage(Command command) {
        for (Consumer<Command> consumer : handlers.getOrDefault(command.getClass(), List.of())) {
            consumer.accept(command);
        }
    }

    @Override
    public <C extends Command> void subscribe(Class<C> commandType, CommandHandler<C> handler) {
        handlers.computeIfAbsent(commandType, k -> new CopyOnWriteArrayList<>())
                .add(command -> handler.handle(commandType.cast(command)));
    }
}