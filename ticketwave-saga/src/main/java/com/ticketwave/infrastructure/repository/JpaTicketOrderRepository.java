package com.ticketwave.infrastructure.repository;

import com.ticketwave.domain.order.TicketOrder;
import com.ticketwave.domain.order.TicketOrderRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface JpaTicketOrderRepository extends TicketOrderRepository, JpaRepository<TicketOrder, UUID> {
}