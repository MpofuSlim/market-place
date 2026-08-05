package com.innbucks.marketplaceservice.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins {@link AuditService}'s HMAC sealing + hash-chaining (OWASP A09): the
 * canonicalisation is recomputed INDEPENDENTLY in the test (raw
 * {@link Mac}), so a silent change to the sealed-field set or separator fails
 * here, not in a forensic investigation. Repositories and the transaction
 * manager are mocked — the FOR UPDATE serialisation itself is DB behaviour
 * outside this unit's scope.
 */
class AuditServiceTest {

    private static final String SECRET =
            "audit-hmac-unit-secret-0123456789abcdefghijklmnop";
    /** The documented canonical field separator (ASCII Unit Separator). */
    private static final String SEP = "\u001F";

    private AuditEventRepository repository;
    private AuditChainHeadRepository chainHeadRepository;
    private AuditService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        chainHeadRepository = mock(AuditChainHeadRepository.class);
        PlatformTransactionManager ptm = mock(PlatformTransactionManager.class);
        when(ptm.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new AuditService(repository, chainHeadRepository,
                new ObjectMapper(), ptm, SECRET);
    }

    /** Independent HMAC-SHA256 recompute — deliberately NOT via AuditService. */
    private static String hmacHex(String secret, String canonical) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static AuditEvent event(Instant createdAt, String actor, String target,
                                    String metadata) {
        return AuditEvent.builder()
                .eventType(AuditEventType.ORDER_CREATED.name())
                .actorUuid(actor)
                .targetId(target)
                .metadata(metadata)
                .createdAt(createdAt)
                .build();
    }

    @Test
    void rowHmacMatchesDocumentedCanonicalisation() throws Exception {
        Instant at = Instant.parse("2026-08-05T10:15:30.123456Z");
        AuditEvent e = event(at, "actor-uuid", "target-id", "{\"k\":\"v\"}");

        String expected = hmacHex(SECRET,
                at + SEP + "ORDER_CREATED" + SEP + "actor-uuid" + SEP + "target-id"
                        + SEP + "{\"k\":\"v\"}");

        assertThat(service.computeHmac(e)).isEqualTo(expected);
    }

    @Test
    void nullFieldsCanonicaliseAsEmptyStrings() throws Exception {
        Instant at = Instant.parse("2026-08-05T10:15:30Z");
        AuditEvent e = event(at, null, null, null);

        String expected = hmacHex(SECRET,
                at + SEP + "ORDER_CREATED" + SEP + "" + SEP + "" + SEP + "");

        assertThat(service.computeHmac(e)).isEqualTo(expected);
    }

    @Test
    void rowHmacIsStableAndKeyDependent() {
        AuditEvent e = event(Instant.parse("2026-08-05T10:15:30Z"), "a", "t", null);

        // Deterministic: same row, same tag.
        assertThat(service.computeHmac(e)).isEqualTo(service.computeHmac(e));

        // Keyed: a different secret yields a different tag — an attacker
        // without the key cannot re-seal a tampered row.
        PlatformTransactionManager ptm = mock(PlatformTransactionManager.class);
        AuditService otherKey = new AuditService(repository, chainHeadRepository,
                new ObjectMapper(), ptm, "different-secret-0123456789abcdefghijklmn");
        assertThat(otherKey.computeHmac(e)).isNotEqualTo(service.computeHmac(e));
    }

    @Test
    void chainHmacMatchesDocumentedForm() throws Exception {
        String prev = "aa".repeat(32);
        String rowHmac = "bb".repeat(32);

        assertThat(service.computeChainHmac(prev, rowHmac))
                .isEqualTo(hmacHex(SECRET, prev + SEP + rowHmac));
    }

    @Test
    void genesisChainLinkTreatsNullPredecessorAsEmpty() throws Exception {
        String rowHmac = "cc".repeat(32);

        assertThat(service.computeChainHmac(null, rowHmac))
                .isEqualTo(hmacHex(SECRET, "" + SEP + rowHmac));
    }

    @Test
    void recordSealsChainsAndAdvancesTheHead() {
        AuditChainHead head = new AuditChainHead((short) 1, "prev-chain-tag", 41L);
        when(chainHeadRepository.lockHead()).thenReturn(Optional.of(head));
        when(repository.save(any())).thenAnswer(inv -> {
            AuditEvent e = inv.getArgument(0);
            e.setId(42L);
            return e;
        });

        service.record(AuditEventType.ORDER_PAID, "actor-uuid", "MKT-abc123def456",
                Map.of("amountCents", 1500));

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        AuditEvent saved = captor.getValue();

        // Sealed over the content fields only — recomputing on the persisted row
        // (id + chain now set) still matches, which is what lets the verifier
        // recompute later.
        assertThat(saved.getRowHmac()).isEqualTo(service.computeHmac(saved));
        // Chained onto the locked head's last link.
        assertThat(saved.getChainHmac())
                .isEqualTo(service.computeChainHmac("prev-chain-tag", saved.getRowHmac()));
        assertThat(saved.getMetadata()).isEqualTo("{\"amountCents\":1500}");

        // Head advanced to this row so the NEXT write chains onto it.
        assertThat(head.getLastChainHmac()).isEqualTo(saved.getChainHmac());
        assertThat(head.getLastEventId()).isEqualTo(42L);
        verify(chainHeadRepository).save(head);
    }

    @Test
    void recordTruncatesOversizeActorAndTargetToColumnLength() {
        when(chainHeadRepository.lockHead())
                .thenReturn(Optional.of(new AuditChainHead((short) 1, null, null)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String oversize = "x".repeat(80);
        service.record(AuditEventType.LISTING_CREATED, oversize, oversize, null);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActorUuid()).hasSize(64);
        assertThat(captor.getValue().getTargetId()).hasSize(64);
    }

    @Test
    void recordNeverPropagatesAWriteFailure() {
        // A broken audit path must not break the caller's flow (deliberate
        // trade-off: audit gap over hard outage).
        when(chainHeadRepository.lockHead())
                .thenReturn(Optional.of(new AuditChainHead((short) 1, null, null)));
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.record(AuditEventType.ORDER_CREATED,
                "actor", "target", Map.of("k", "v")))
                .doesNotThrowAnyException();
    }
}
