package com.ticketwave.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.ticketwave.domain.bus.CommandBus;
import com.ticketwave.domain.bus.EventBus;
import com.ticketwave.infrastructure.bus.RabbitMQCommandBusAdapter;
import com.ticketwave.infrastructure.bus.RabbitMQEventBusAdapter;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bus wiring for the orchestrator. Events and commands travel over the shared
 * RabbitMQ exchanges; each service uses an app-specific queue so every
 * instance receives a full copy of the stream.
 */
@Configuration
public class OrchestratorBusConfig {

    @Bean
    public EventBus rabbitMqEventBus(RabbitTemplate rabbitTemplate,
                                     AmqpAdmin amqpAdmin,
                                     @Value("${ticketwave.bus.event-queue}") String eventQueue) {
        return new RabbitMQEventBusAdapter(rabbitTemplate, amqpAdmin, eventQueue);
    }

    @Bean
    public CommandBus rabbitMqCommandBus(RabbitTemplate rabbitTemplate,
                                         AmqpAdmin amqpAdmin,
                                         @Value("${ticketwave.bus.command-queue}") String commandQueue) {
        return new RabbitMQCommandBusAdapter(rabbitTemplate, amqpAdmin, commandQueue);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter(busObjectMapper());
    }

    /**
     * ObjectMapper with open polymorphic typing so that records implementing the
     * DomainEvent and Command interfaces can be serialized and deserialized over
     * AMQP. Must match the mapper used by the API service to keep messages
     * interoperable.
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