package com.ticketwave.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.ticketwave.domain.bus.CommandBus;
import com.ticketwave.domain.bus.EventBus;
import com.ticketwave.infrastructure.bus.InMemoryCommandBus;
import com.ticketwave.infrastructure.bus.InMemoryEventBus;
import com.ticketwave.infrastructure.bus.RabbitMQCommandBusAdapter;
import com.ticketwave.infrastructure.bus.RabbitMQEventBusAdapter;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Bus transport wiring. RabbitMQ is the production transport: events are routed
 * on the ticketwave.events exchange and commands on the dedicated
 * ticketwave.commands exchange; these beans are only created under the
 * rabbitmq profile. The local and test profiles use in-memory buses so the
 * application runs without a broker.
 */
@Configuration
public class EventBusConfig {

    @Bean
    @Profile("rabbitmq")
    public EventBus rabbitMqEventBus(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin,
                                     @Value("${ticketwave.bus.event-queue}") String eventQueue) {
        return new RabbitMQEventBusAdapter(rabbitTemplate, amqpAdmin, eventQueue);
    }

    @Bean
    @Profile("rabbitmq")
    public CommandBus rabbitMqCommandBus(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin,
                                         @Value("${ticketwave.bus.command-queue}") String commandQueue) {
        return new RabbitMQCommandBusAdapter(rabbitTemplate, amqpAdmin, commandQueue);
    }

    @Bean
    @Profile("local")
    public EventBus inMemoryEventBus() {
        return new InMemoryEventBus();
    }

    @Bean
    @Profile("local")
    public CommandBus inMemoryCommandBus() {
        return new InMemoryCommandBus();
    }

    @Bean
    @Profile("rabbitmq")
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter(busObjectMapper());
    }

    /**
     * ObjectMapper with open polymorphic typing so that records implementing the
     * DomainEvent and Command interfaces can be serialized and deserialized over
     * AMQP.
     */
    private static ObjectMapper busObjectMapper() {
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.ticketwave.domain.events.")
                .allowIfSubType("com.ticketwave.domain.commands.")
                .build();
        ObjectMapper mapper = new ObjectMapper();
        mapper.activateDefaultTyping(typeValidator,
                ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        return mapper;
    }
}