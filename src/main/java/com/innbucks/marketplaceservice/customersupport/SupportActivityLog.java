package com.innbucks.marketplaceservice.customersupport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.innbucks.marketplaceservice.customersupport.dto.SupportActivityPageResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportActivityResponse;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The support activity log (V22): every support search, every view of a buyer,
 * order or seller, and every support action.
 *
 * <p><b>An ACTION's row joins the action's transaction</b>, so an action that
 * rolls back leaves no row claiming it happened. <b>A VIEW's row is written in
 * its own short transaction BEFORE the view is rendered</b>, after the subject
 * is known to exist: views that show seller names must render outside any
 * transaction ({@code @NameResolvingRead} — the name registry is never called
 * while a pooled connection is held), and writing the row first means that if
 * it cannot be written the view fails — customer data is never shown without
 * a record that it was.
 *
 * <p>{@code detail} holds ids and enums only. A phone number is masked before
 * it gets here, and free text (a note, a message, a reason) never does.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SupportActivityLog {

    static final int MAX_PAGE_SIZE = 100;
    static final int MAX_DETAIL_LENGTH = 500;

    private static final TypeReference<LinkedHashMap<String, Object>> DETAIL_TYPE = new TypeReference<>() {
    };

    private final SupportActivityRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public void record(SupportAgent agent, String action, SubjectKind subjectKind, Object subjectId,
                       Map<String, ?> detail) {
        repository.save(new SupportActivity(agent, action,
                subjectKind == null ? null : subjectKind.name(),
                subjectId == null ? null : subjectId.toString(),
                serialize(detail), Instant.now()));
    }

    /** The supervisor feed. Every filter optional, each appended only when
     *  present — never a nullable bind. */
    public record ActivityQuery(String agentUuid, String action, SubjectKind subjectKind, String subjectId,
                                Instant from, Instant to, int page, int size) {
    }

    @Transactional(readOnly = true)
    public SupportActivityPageResponse list(ActivityQuery query) {
        Specification<SupportActivity> spec = (root, q, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (query.agentUuid() != null && !query.agentUuid().isBlank()) {
                where.add(cb.equal(root.get("agentUuid"), query.agentUuid().trim()));
            }
            if (query.action() != null && !query.action().isBlank()) {
                where.add(cb.equal(root.get("action"), query.action().trim()));
            }
            if (query.subjectKind() != null) {
                where.add(cb.equal(root.get("subjectKind"), query.subjectKind().name()));
            }
            if (query.subjectId() != null && !query.subjectId().isBlank()) {
                where.add(cb.equal(root.get("subjectId"), query.subjectId().trim()));
            }
            if (query.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.from()));
            }
            if (query.to() != null) {
                where.add(cb.lessThan(root.get("createdAt"), query.to()));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        PageRequest pageable = PageRequest.of(Math.max(query.page(), 0),
                Math.clamp(query.size(), 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id")));
        Page<SupportActivity> page = repository.findAll(spec, pageable);
        List<SupportActivityResponse> items = page.getContent().stream().map(this::toResponse).toList();
        return new SupportActivityPageResponse(items, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    private SupportActivityResponse toResponse(SupportActivity row) {
        return new SupportActivityResponse(row.getId(),
                new SupportAgent(row.getAgentUuid(), row.getAgentLogin()),
                row.getAction(), row.getSubjectKind(), row.getSubjectId(),
                deserialize(row.getDetail()), row.getCreatedAt());
    }

    private String serialize(Map<String, ?> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(detail);
            if (json.length() > MAX_DETAIL_LENGTH) {
                // Built from ids and enums, so this is a programming error, not
                // input — but the row must still be written.
                log.warn("support activity detail over {} chars; keys kept only", MAX_DETAIL_LENGTH);
                return objectMapper.writeValueAsString(Map.of("truncated", true));
            }
            return json;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("support activity detail is not serialisable", ex);
        }
    }

    private Map<String, Object> deserialize(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(detail, DETAIL_TYPE);
        } catch (JsonProcessingException ex) {
            return Map.of("unreadable", true);
        }
    }
}
