package com.innbucks.marketplaceservice.order;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.audit.AuditEventType;
import com.innbucks.marketplaceservice.audit.AuditService;
import com.innbucks.marketplaceservice.metrics.MarketplaceMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the complete order lifecycle map ({@link OrderStateMachine}) and the
 * refusal policy at the single transition chokepoint
 * ({@link OrderTransitionService}): every legal transition is allowed, every
 * illegal one is refused — counted on
 * {@code marketplace.orders.illegal_transitions} and NEVER applied — and the
 * terminals are immutable.
 */
class OrderStateMachineTest {

    /** The lifecycle map, restated independently of production code so any
     *  change to {@code OrderStateMachine.LEGAL_TRANSITIONS} fails this test. */
    private static final Map<OrderStatus, Set<OrderStatus>> EXPECTED_LEGAL = Map.of(
            OrderStatus.PENDING_PAYMENT,
                    EnumSet.of(OrderStatus.PAID, OrderStatus.CANCELLED, OrderStatus.EXPIRED),
            OrderStatus.PAID, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class),
            OrderStatus.EXPIRED, EnumSet.noneOf(OrderStatus.class));

    @Test
    void everyStatusPairMatchesTheLifecycleMap() {
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                assertEquals(EXPECTED_LEGAL.get(from).contains(to),
                        OrderStateMachine.isLegal(from, to),
                        from + " -> " + to);
            }
        }
    }

    @Test
    void pendingPaymentMayMoveToEveryTerminal() {
        assertTrue(OrderStateMachine.isLegal(OrderStatus.PENDING_PAYMENT, OrderStatus.PAID));
        assertTrue(OrderStateMachine.isLegal(OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED));
        assertTrue(OrderStateMachine.isLegal(OrderStatus.PENDING_PAYMENT, OrderStatus.EXPIRED));
    }

    @Test
    void terminalsAreImmutable() {
        for (OrderStatus terminal : EnumSet.of(OrderStatus.PAID, OrderStatus.CANCELLED,
                OrderStatus.EXPIRED)) {
            for (OrderStatus to : OrderStatus.values()) {
                assertFalse(OrderStateMachine.isLegal(terminal, to), terminal + " -> " + to);
            }
        }
    }

    @Test
    void selfTransitionsAreIllegal() {
        for (OrderStatus status : OrderStatus.values()) {
            assertFalse(OrderStateMachine.isLegal(status, status), status + " -> " + status);
        }
    }

    /**
     * Refusal policy at the chokepoint: illegal requests are counted and never
     * applied — {@code transition()} throws 409 for caller paths,
     * {@code transitionIfLegal()} returns false for sweeps.
     */
    @Nested
    class RefusalPolicy {

        private MarketOrderRepository orderRepository;
        private MarketOrderEventRepository eventRepository;
        private AuditService auditService;
        private SimpleMeterRegistry registry;
        private ApplicationEventPublisher eventPublisher;
        private OrderTransitionService transitions;

        @BeforeEach
        void setUp() {
            orderRepository = mock(MarketOrderRepository.class);
            eventRepository = mock(MarketOrderEventRepository.class);
            auditService = mock(AuditService.class);
            registry = new SimpleMeterRegistry();
            eventPublisher = mock(ApplicationEventPublisher.class);
            transitions = new OrderTransitionService(orderRepository, eventRepository,
                    auditService, new MarketplaceMetrics(registry), eventPublisher);
            when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        private MarketOrder order(OrderStatus status) {
            Instant now = Instant.now();
            return MarketOrder.builder()
                    .id(UUID.randomUUID())
                    .orderRef("MKT-4F9A1C22B7D3")
                    .buyerUuid(UUID.randomUUID())
                    .buyerMsisdn("+263771234567")
                    .status(status)
                    .totalCents(3550)
                    .currency("USD")
                    .expiresAt(now.plusSeconds(1800))
                    .stockReleased(false)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
        }

        private double illegalCount() {
            return registry.get("marketplace.orders.illegal_transitions").counter().count();
        }

        @Test
        void legalTransitionIsAppliedJournaledAuditedAndCounted() {
            MarketOrder order = transitions.transition(order(OrderStatus.PENDING_PAYMENT),
                    OrderStatus.PAID, "Payment confirmed");

            assertEquals(OrderStatus.PAID, order.getStatus());
            verify(orderRepository).save(order);

            ArgumentCaptor<MarketOrderEvent> journal = ArgumentCaptor.forClass(MarketOrderEvent.class);
            verify(eventRepository).save(journal.capture());
            assertEquals("PENDING_PAYMENT", journal.getValue().getFromStatus());
            assertEquals("PAID", journal.getValue().getToStatus());
            assertEquals("Payment confirmed", journal.getValue().getDetail());
            assertEquals(order.getId(), journal.getValue().getOrderId());

            // No JWT caller on this thread -> the audit actor is "system".
            verify(auditService).record(eq(AuditEventType.ORDER_PAID), eq("system"),
                    eq(order.getId().toString()), anyMap());
            assertEquals(1.0, registry.get("marketplace.orders")
                    .tag("outcome", "paid").counter().count());
            assertEquals(0.0, illegalCount());

            // The notification seam: PAID (and only PAID) publishes OrderPaid
            // from the chokepoint, in-tx, for the AFTER_COMMIT listener.
            ArgumentCaptor<OrderPaid> published = ArgumentCaptor.forClass(OrderPaid.class);
            verify(eventPublisher).publishEvent(published.capture());
            assertEquals(order.getId(), published.getValue().orderId());
            assertEquals(order.getOrderRef(), published.getValue().orderRef());
            assertEquals(order.getBuyerMsisdn(), published.getValue().buyerMsisdn());
            assertEquals(order.getTotalCents(), published.getValue().totalCents());
            assertEquals(order.getCurrency(), published.getValue().currency());
        }

        @Test
        void illegalTransitionThrows409CountsAndNeverApplies() {
            MarketOrder order = order(OrderStatus.PAID);

            ApiException ex = assertThrows(ApiException.class,
                    () -> transitions.transition(order, OrderStatus.CANCELLED, "buyer cancel"));

            assertEquals(HttpStatus.CONFLICT, ex.status());
            assertEquals("illegal_order_state", ex.code());
            assertEquals(OrderStatus.PAID, order.getStatus());
            verify(orderRepository, never()).save(any());
            verify(eventRepository, never()).save(any());
            verifyNoInteractions(auditService);
            assertEquals(1.0, illegalCount());
        }

        @Test
        void sweepPathRefusalReturnsFalseCountsAndNeverApplies() {
            MarketOrder order = order(OrderStatus.CANCELLED);

            assertFalse(transitions.transitionIfLegal(order, OrderStatus.EXPIRED, "sweep"));

            assertEquals(OrderStatus.CANCELLED, order.getStatus());
            verify(orderRepository, never()).save(any());
            verify(eventRepository, never()).save(any());
            verifyNoInteractions(auditService);
            assertEquals(1.0, illegalCount());
        }

        @Test
        void sweepPathAppliesALegalTransition() {
            MarketOrder order = order(OrderStatus.PENDING_PAYMENT);

            assertTrue(transitions.transitionIfLegal(order, OrderStatus.EXPIRED, "TTL lapsed"));

            assertEquals(OrderStatus.EXPIRED, order.getStatus());
            verify(orderRepository).save(order);
            verify(auditService).record(eq(AuditEventType.ORDER_EXPIRED), eq("system"),
                    eq(order.getId().toString()), anyMap());
            assertEquals(0.0, illegalCount());
            // Only PAID announces itself to the notification seam.
            verifyNoInteractions(eventPublisher);
        }

        @Test
        void everyIllegalPairIsRefusedCountedAndNeverApplied() {
            int refused = 0;
            for (OrderStatus from : OrderStatus.values()) {
                for (OrderStatus to : OrderStatus.values()) {
                    if (EXPECTED_LEGAL.get(from).contains(to)) {
                        continue;
                    }
                    MarketOrder order = order(from);
                    assertFalse(transitions.transitionIfLegal(order, to, "probe"),
                            from + " -> " + to);
                    assertEquals(from, order.getStatus(), from + " -> " + to + " was applied");
                    refused++;
                }
            }
            // 4x4 pairs minus the 3 legal ones — each refusal counted exactly once.
            assertEquals(13, refused);
            assertEquals(13.0, illegalCount());
            verify(orderRepository, never()).save(any());
            verify(eventRepository, never()).save(any());
            verifyNoInteractions(auditService);
        }

        @Test
        void everyLegalPairIsApplied() {
            for (OrderStatus to : EXPECTED_LEGAL.get(OrderStatus.PENDING_PAYMENT)) {
                MarketOrder order = order(OrderStatus.PENDING_PAYMENT);
                MarketOrder applied = transitions.transition(order, to, "legal move");
                assertEquals(to, applied.getStatus());
            }
            assertEquals(0.0, illegalCount());
        }

        @Test
        void journalCreationWritesTheBirthRowWithNullFrom() {
            MarketOrder order = order(OrderStatus.PENDING_PAYMENT);

            transitions.journalCreation(order);

            ArgumentCaptor<MarketOrderEvent> journal = ArgumentCaptor.forClass(MarketOrderEvent.class);
            verify(eventRepository).save(journal.capture());
            assertNull(journal.getValue().getFromStatus());
            assertEquals("PENDING_PAYMENT", journal.getValue().getToStatus());
            // Not a transition: no audit event, no order mutation.
            verifyNoInteractions(auditService);
            verify(orderRepository, never()).save(any());
        }
    }
}
