package com.innbucks.marketplaceservice.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Writes append-only rows to the {@code audit_events} table for
 * security-sensitive actions (listing lifecycle, order money-movement states,
 * S2S trust-boundary probes).
 *
 * <h2>Transactional isolation</h2>
 * Every {@code record(...)} call runs in a REQUIRES_NEW transaction managed by
 * {@link TransactionTemplate}. Two properties follow:
 * <ul>
 *   <li><b>Survives caller rollback</b> — an order flow that throws and rolls
 *       back STILL persists the audit row, so an attacker can't suppress their
 *       own audit trail by crashing the request mid-flight.</li>
 *   <li><b>Doesn't fail caller</b> — exceptions from the audit write are caught
 *       and logged inside {@code record(...)} so a transient DB hiccup on the
 *       audit path doesn't bubble up and reject an otherwise-valid order.</li>
 * </ul>
 *
 * <p>This is a deliberate trade-off (same stance as payment-service): an audit
 * gap is preferable to a hard outage. Operators reading the application logs
 * will see the {@code AUDIT_WRITE_FAILED} signal and can reconcile from other
 * sources (gateway access log, OTel spans).
 *
 * <h2>Sensitive data</h2>
 * Callers MUST NOT pass tokens, secrets, or full MSISDNs in {@code metadata}.
 * UUIDs and order refs are fine; phone numbers should use the last-4 masking
 * the rest of the codebase uses for log lines.
 */
@Service
@Slf4j
public class AuditService {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** Field separator for the canonical HMAC input — the ASCII Unit Separator,
     *  which cannot appear in any of the hashed values, so field boundaries are
     *  unambiguous (no delimiter-injection across fields). Same canonicalisation
     *  as user-service/payment-service so the A09 design stays uniform. */
    private static final char SEP = '\u001F';

    private final AuditEventRepository repository;
    private final AuditChainHeadRepository chainHeadRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final SecretKeySpec hmacKey;

    public AuditService(AuditEventRepository repository,
                        AuditChainHeadRepository chainHeadRepository,
                        ObjectMapper objectMapper,
                        PlatformTransactionManager transactionManager,
                        @Value("${marketplace.audit.hmac-secret}") String hmacSecret) {
        this.repository = repository;
        this.chainHeadRepository = chainHeadRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.hmacKey = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    /**
     * Persist one audit row. Any exception during the write is caught, logged,
     * and swallowed — the caller's flow is never interrupted by an audit
     * failure. Call AFTER the state change it describes (the business tx is the
     * source of truth; the audit row is evidence, not a gate).
     */
    public void record(AuditEventType type,
                       String actorUuid,
                       String targetId,
                       Map<String, Object> metadata) {
        AuditEvent event = AuditEvent.builder()
                .eventType(type.name())
                .actorUuid(truncate(actorUuid, 64))
                .targetId(truncate(targetId, 64))
                .metadata(serialiseMetadata(metadata))
                .createdAt(Instant.now())
                .build();
        // Tamper-evidence: seal the row with an HMAC over its immutable fields
        // before it is persisted (OWASP A09). See computeHmac / AuditIntegrityVerifier.
        event.setRowHmac(computeHmac(event));
        try {
            transactionTemplate.execute(status -> appendChained(event));
        } catch (RuntimeException ex) {
            // Don't propagate: a broken audit path must not break the order flow.
            // Operators reading logs will see this marker and can pivot to
            // gateway access logs / OTel for reconstruction.
            log.error("AUDIT_WRITE_FAILED type={} actorUuid={} targetId={} reason={}",
                    type.name(), actorUuid, targetId, ex.getMessage(), ex);
        }
    }

    /**
     * Persist one already-sealed row as the next link in the hash-chain (OWASP
     * A09). Runs inside the caller's REQUIRES_NEW audit transaction.
     *
     * <p>Takes a {@code SELECT ... FOR UPDATE} on the single {@code audit_chain_head}
     * row FIRST, so concurrent audit writers serialise here and each appends to a
     * single, un-forked chain. Then it links this row to its predecessor —
     * {@code chain_hmac = HMAC(key, prev_chain_hmac || row_hmac)} — persists the
     * row, and advances the head. The lock is released when the transaction
     * commits. The head row is seeded by migration V1; the {@code orElseGet}
     * genesis fallback only fires if it is somehow absent (keeps a fresh/legacy
     * DB writable rather than wedging the audit path).
     */
    private AuditEvent appendChained(AuditEvent event) {
        AuditChainHead head = chainHeadRepository.lockHead()
                .orElseGet(() -> new AuditChainHead((short) 1, null, null));
        String chainHmac = computeChainHmac(head.getLastChainHmac(), event.getRowHmac());
        event.setChainHmac(chainHmac);
        AuditEvent saved = repository.save(event);
        head.setLastChainHmac(chainHmac);
        head.setLastEventId(saved.getId());
        chainHeadRepository.save(head);
        return saved;
    }

    private String serialiseMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            // A serialisation failure shouldn't drop the row entirely — record a
            // marker so the rest of the audit data still lands and operators
            // know to investigate.
            log.warn("AUDIT_METADATA_SERIALISATION_FAILED keys={} reason={}",
                    metadata.keySet(), ex.getMessage());
            return "{\"_error\":\"metadata-serialisation-failed\"}";
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        if (value.length() <= max) return value;
        return value.substring(0, max);
    }

    /**
     * HMAC-SHA256 over the row's immutable fields, hex-encoded (OWASP A09
     * tamper-evidence). Deterministic — the same row always yields the same tag,
     * so {@code AuditIntegrityVerifier} can recompute and compare to detect any
     * post-write modification. Fields are joined with the {@link #SEP} unit
     * separator so no value can forge a boundary. Excludes {@code id}
     * (DB-assigned) and {@code rowHmac} itself.
     */
    public String computeHmac(AuditEvent e) {
        String canonical = String.valueOf(e.getCreatedAt()) + SEP
                + nz(e.getEventType()) + SEP
                + nz(e.getActorUuid()) + SEP
                + nz(e.getTargetId()) + SEP
                + nz(e.getMetadata());
        return hmacHex(canonical);
    }

    /**
     * Chain link tag: {@code HMAC-SHA256(key, prev_chain_hmac || row_hmac)},
     * hex-encoded (OWASP A09 deletion/reorder-evidence). Binding each row to its
     * predecessor's chain tag makes any deletion, reordering, or tail-truncation
     * break the link at the next surviving row — and the attacker can't repair
     * the downstream chain without the HMAC key. {@code prevChainHmac} is null
     * for the genesis row (first ever chained write).
     */
    public String computeChainHmac(String prevChainHmac, String rowHmac) {
        return hmacHex(nz(prevChainHmac) + SEP + nz(rowHmac));
    }

    private String hmacHex(String canonical) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            byte[] tag = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(tag);
        } catch (GeneralSecurityException ex) {
            // HmacSHA256 is a JCE standard algorithm — always present. A failure
            // here is unrecoverable and must not silently persist an unsealed row.
            throw new IllegalStateException("Failed to compute audit HMAC", ex);
        }
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }
}
