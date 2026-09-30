package com.innbucks.marketplaceservice.customersupport.messaging;

import com.innbucks.marketplaceservice.customersupport.SubjectKind;
import com.innbucks.marketplaceservice.customersupport.dto.SupportMessagePageResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportMessageResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportMessagesSummary;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads of the support message log: one customer's conversation with support,
 * and the supervisors' feed of every agent's messages. The number is masked on
 * every row; a secret-bearing message carries no text.
 */
@Component
@RequiredArgsConstructor
public class SupportMessageHistory {

    static final int MAX_PAGE_SIZE = 100;

    private final SupportMessageRepository repository;

    /**
     * A subject's messages, newest first. A BUYER's history includes every
     * message about any of their orders — support reaches a buyer through an
     * order far more often than through their profile, and "what have we told
     * this customer" is one question.
     */
    @Transactional(readOnly = true)
    public SupportMessagePageResponse forSubject(SubjectKind kind, UUID subjectId, int page, int size) {
        return toPage(find(kind, subjectId, PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE))));
    }

    /** The head of a subject's history, for a 360 view. Not transactional:
     *  called from views that hold no connection while they render names. */
    public SupportMessagesSummary recent(SubjectKind kind, UUID subjectId, int limit) {
        Page<SupportMessage> page = find(kind, subjectId, PageRequest.of(0, limit));
        return new SupportMessagesSummary(page.getTotalElements(),
                page.getContent().stream().map(SupportMessageResponse::from).toList());
    }

    /** Every agent's messages (supervisors). Filters optional, each appended
     *  only when present — never a nullable bind. */
    public record FeedQuery(String agentUuid, String kind, String outcome, Instant from, Instant to,
                            int page, int size) {
    }

    @Transactional(readOnly = true)
    public SupportMessagePageResponse feed(FeedQuery query) {
        Specification<SupportMessage> spec = (root, q, cb) -> {
            List<Predicate> where = new ArrayList<>();
            if (query.agentUuid() != null && !query.agentUuid().isBlank()) {
                where.add(cb.equal(root.get("agentUuid"), query.agentUuid().trim()));
            }
            if (query.kind() != null && !query.kind().isBlank()) {
                where.add(cb.equal(root.get("kind"), query.kind().trim()));
            }
            if (query.outcome() != null && !query.outcome().isBlank()) {
                where.add(cb.equal(root.get("outcome"), query.outcome().trim()));
            }
            if (query.from() != null) {
                where.add(cb.greaterThanOrEqualTo(root.get("createdAt"), query.from()));
            }
            if (query.to() != null) {
                where.add(cb.lessThan(root.get("createdAt"), query.to()));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
        return toPage(repository.findAll(spec, PageRequest.of(Math.max(query.page(), 0),
                Math.clamp(query.size(), 1, MAX_PAGE_SIZE), newestFirst())));
    }

    private Page<SupportMessage> find(SubjectKind kind, UUID subjectId, PageRequest pageable) {
        if (kind == SubjectKind.BUYER) {
            // The native query orders itself (newest first, id as tiebreak).
            return repository.findForBuyer(subjectId, subjectId.toString(), pageable);
        }
        return repository.findBySubjectKindAndSubjectId(kind.name(), subjectId.toString(),
                pageable.withSort(newestFirst()));
    }

    private static SupportMessagePageResponse toPage(Page<SupportMessage> page) {
        return new SupportMessagePageResponse(page.getContent().stream().map(SupportMessageResponse::from).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    private static Sort newestFirst() {
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    }
}
