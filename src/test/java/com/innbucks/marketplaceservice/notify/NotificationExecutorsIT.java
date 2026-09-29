package com.innbucks.marketplaceservice.notify;

import com.innbucks.marketplaceservice.catalog.Listing;
import com.innbucks.marketplaceservice.catalog.ListingRepository;
import com.innbucks.marketplaceservice.catalog.ListingStatus;
import com.innbucks.marketplaceservice.config.AsyncConfig;
import com.innbucks.marketplaceservice.support.PostgresTestContainer;
import com.innbucks.marketplaceservice.support.TestJwts;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The executor wiring in the REAL application context: the {@code @Scheduled}
 * jobs run on the multi-threaded {@code taskScheduler} (not Spring's
 * single-thread fallback), every pool is visible to Boot's executor metrics,
 * and a real restock and a real payment confirm land on the pools
 * {@link AsyncConfig} names — the restock fan-out on the bulk pool, the
 * order-paid SMS on the per-order one, the payout-destination warning on the
 * security pool. Shares {@link NotificationFlowIT}'s
 * mocked channels, so it reuses that cached context.
 */
@Import(NotificationFlowIT.MockNotifyChannels.class)
class NotificationExecutorsIT extends PostgresTestContainer {

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${innbucks.internal-api-token}")
    private String internalToken;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private ListingRepository listingRepository;

    @Autowired
    private SmsNotificationClient sms;

    @Autowired
    private WhatsAppNotificationClient whatsApp;

    @Autowired
    private UserNotifyGateway userNotifyGateway;

    @Autowired
    private MerchantAdminResolver merchantAdminResolver;

    private UUID buyerUuid;
    private String customerToken;

    @BeforeEach
    void resetMocks() {
        Mockito.reset(sms, whatsApp, userNotifyGateway, merchantAdminResolver);
        when(whatsApp.isConfigured()).thenReturn(false);
        buyerUuid = UUID.randomUUID();
        customerToken = TestJwts.customer(buyerUuid, jwtSecret);
    }

    @Test
    @DisplayName("The @Scheduled jobs run on the 4-thread taskScheduler, not a single-thread fallback")
    void scheduledJobsRunOnThePooledScheduler() {
        ThreadPoolTaskScheduler scheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(4);

        // ScheduledAnnotationBeanPostProcessor schedules the sweeps on the ONE
        // TaskScheduler bean it finds by type; finding none (or several) it
        // falls back to a single-thread scheduler of its own - exactly the
        // failure this pins against. (The test profile disables every cron, so
        // the registrar itself never resolves one here to be asserted.)
        assertThat(context.getBeansOfType(TaskScheduler.class)).containsOnlyKeys("taskScheduler");
        assertThat(context.getBeansOfType(ScheduledExecutorService.class)).isEmpty();
    }

    @Test
    @DisplayName("Every notification pool and the scheduler export executor metrics")
    void everyPoolIsMetered() {
        for (String name : new String[] {AsyncConfig.NOTIFICATION_EXECUTOR,
                AsyncConfig.BULK_NOTIFICATION_EXECUTOR, AsyncConfig.SECURITY_NOTIFICATION_EXECUTOR,
                "taskScheduler"}) {
            assertThat(meterRegistry.find("executor.queued").tag("name", name).gauge())
                    .as("executor.queued{name=%s}", name).isNotNull();
            assertThat(meterRegistry.find("executor.active").tag("name", name).gauge())
                    .as("executor.active{name=%s}", name).isNotNull();
        }
        assertThat(meterRegistry.find("marketplace.notifications.executor_rejected")
                .tag("executor", AsyncConfig.BULK_NOTIFICATION_EXECUTOR)
                .tag("reason", "saturated").counter())
                .isNotNull();
        assertThat(meterRegistry.find("marketplace.notifications.executor_rejected")
                .tag("executor", AsyncConfig.SECURITY_NOTIFICATION_EXECUTOR)
                .tag("policy", "caller_runs")
                .tag("reason", "saturated").counter())
                .isNotNull();
    }

    @Test
    @DisplayName("A payout-destination change warns the seller from the security pool")
    void payoutDestinationWarningRunsOnTheSecurityPool() throws Exception {
        UUID merchantId = UUID.randomUUID();
        UUID adminUuid = UUID.randomUUID();
        when(merchantAdminResolver.adminUserUuids(merchantId)).thenReturn(java.util.List.of(adminUuid));
        AtomicReference<String> warningThread = new AtomicReference<>();
        when(userNotifyGateway.notify(any(), anyString(), anyString())).thenAnswer(inv -> {
            warningThread.compareAndSet(null, Thread.currentThread().getName());
            return true;
        });

        mockMvc.perform(put("/marketplace/sellers/me/payout-destination")
                        .header("Authorization", "Bearer "
                                + TestJwts.merchantAdmin(UUID.randomUUID(), merchantId, jwtSecret))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"method":"MOBILE_MONEY","accountName":"Rudo Chikwanha",
                                 "msisdn":"0771234567"}"""))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(5)).until(() -> warningThread.get() != null);
        assertThat(warningThread.get()).startsWith("marketplace-notify-security-");
        Mockito.verify(userNotifyGateway).notify(Mockito.eq(adminUuid), anyString(), anyString());
    }

    @Test
    @DisplayName("A restock fan-out runs on the bulk pool; the order-paid SMS on the per-order pool")
    void eachListenerRunsOnItsOwnPool() throws Exception {
        AtomicReference<String> restockThread = new AtomicReference<>();
        when(userNotifyGateway.notify(any(), anyString(), anyString())).thenAnswer(inv -> {
            restockThread.compareAndSet(null, Thread.currentThread().getName());
            return true;
        });
        AtomicReference<String> smsThread = new AtomicReference<>();
        when(sms.isConfigured()).thenReturn(true);
        Mockito.doAnswer(inv -> {
            smsThread.compareAndSet(null, Thread.currentThread().getName());
            return null;
        }).when(sms).sendSms(anyString(), anyString(), anyString());

        // Favorite, buy the last unit, cancel: 0 -> 1 is a restock.
        UUID soldOut = seedActiveListing(1);
        mockMvc.perform(put("/marketplace/favorites/{id}", soldOut)
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
        String lastUnit = createOrder(soldOut, "executors-it-1");
        mockMvc.perform(post("/marketplace/orders/{id}/cancel", (String) JsonPath.read(lastUnit, "$.data.id"))
                        .header("Authorization", "Bearer " + customerToken))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(5)).until(() -> restockThread.get() != null);
        assertThat(restockThread.get()).startsWith("marketplace-notify-bulk-");

        // A paid order: the buyer SMS is a per-order notice.
        String paid = createOrder(seedActiveListing(5), "executors-it-2");
        mockMvc.perform(patch("/marketplace/internal/orders/{ref}/confirm-payment",
                                (String) JsonPath.read(paid, "$.data.orderRef"))
                        .header("X-Internal-Token", internalToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentRef\":\"INB-PAY-EXEC-1\",\"amountCents\":1550}"))
                .andExpect(status().isOk());
        await().atMost(Duration.ofSeconds(5)).until(() -> smsThread.get() != null);
        assertThat(smsThread.get()).startsWith("marketplace-notify-")
                .doesNotStartWith("marketplace-notify-bulk-");
    }

    private String createOrder(UUID listingId, String idempotencyKey) throws Exception {
        return mockMvc.perform(post("/marketplace/orders")
                        .header("Authorization", "Bearer " + customerToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buyerMsisdn":"+263771234567","items":[{"listingId":"%s","quantity":1}]}"""
                                .formatted(listingId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    private UUID seedActiveListing(int stockQty) {
        Instant now = Instant.now();
        Listing listing = Listing.builder()
                .id(UUID.randomUUID())
                .merchantId(UUID.randomUUID())
                .title("Solar Lantern 20W")
                .priceCents(1550L)
                .currency("USD")
                .stockQty(stockQty)
                .status(ListingStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
        listingRepository.save(listing);
        return listing.getId();
    }
}
