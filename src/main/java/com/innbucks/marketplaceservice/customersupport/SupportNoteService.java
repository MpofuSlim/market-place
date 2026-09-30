package com.innbucks.marketplaceservice.customersupport;

import com.innbucks.marketplaceservice.api.ApiException;
import com.innbucks.marketplaceservice.catalog.util.TextSanitizer;
import com.innbucks.marketplaceservice.customersupport.dto.SupportNotePageResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportNoteRequest;
import com.innbucks.marketplaceservice.customersupport.dto.SupportNoteResponse;
import com.innbucks.marketplaceservice.customersupport.dto.SupportNotesSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal support notes (V22) — the case log agents leave for each other.
 * Append-only: there is deliberately no update or delete here, and a database
 * trigger refuses both.
 */
@Service
@RequiredArgsConstructor
public class SupportNoteService {

    static final int MAX_BODY = 2000;
    static final int MAX_PAGE_SIZE = 100;

    private final SupportNoteRepository noteRepository;
    private final SupportSubjects subjects;
    private final SupportActivityLog activityLog;

    @Transactional
    public SupportNoteResponse add(SupportAgent agent, SupportNoteRequest request) {
        String body = TextSanitizer.sanitize(request.body());
        if (body == null || body.isBlank()) {
            // Blank AFTER stripping: "<b></b>" passes @NotBlank and is nothing.
            throw ApiException.badRequest("note_required", "Write something in the note");
        }
        if (body.length() > MAX_BODY) {
            throw ApiException.badRequest("note_too_long", "A note is at most " + MAX_BODY + " characters");
        }
        subjects.require(request.subjectKind(), request.subjectId());
        SupportNote note = noteRepository.save(
                new SupportNote(request.subjectKind(), request.subjectId(), body, agent, Instant.now()));
        activityLog.record(agent, SupportActions.NOTE_ADDED, request.subjectKind(), request.subjectId(),
                Map.of("noteId", note.getId().toString()));
        return toResponse(note);
    }

    /**
     * The note a support ACTION leaves with its reason, in the action's own
     * transaction — so the case log says why support did it, and an action
     * that is refused leaves no note claiming it happened. The action has
     * already proven the subject and writes its own activity row.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SupportNoteResponse addForAction(SupportAgent agent, SubjectKind kind, UUID subjectId, String body) {
        String text = body.length() > MAX_BODY ? body.substring(0, MAX_BODY) : body;
        return toResponse(noteRepository.save(new SupportNote(kind, subjectId, text, agent, Instant.now())));
    }

    @Transactional(readOnly = true)
    public SupportNotePageResponse list(SubjectKind kind, UUID subjectId, int page, int size) {
        Page<SupportNote> notes = noteRepository.findBySubjectKindAndSubjectId(kind.name(), subjectId.toString(),
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE), newestFirst()));
        return new SupportNotePageResponse(notes.getContent().stream().map(SupportNoteService::toResponse).toList(),
                notes.getNumber(), notes.getSize(), notes.getTotalElements(), notes.getTotalPages());
    }

    /** The newest few notes plus the total, for the head of a 360 view. Not
     *  transactional: it is called from views that must hold no connection
     *  while they render names, and two committed reads are all it needs. */
    public SupportNotesSummary recent(SubjectKind kind, UUID subjectId, int limit) {
        List<SupportNoteResponse> notes = noteRepository.findBySubjectKindAndSubjectId(kind.name(),
                        subjectId.toString(), PageRequest.of(0, limit, newestFirst()))
                .getContent().stream().map(SupportNoteService::toResponse).toList();
        return new SupportNotesSummary(
                noteRepository.countBySubjectKindAndSubjectId(kind.name(), subjectId.toString()), notes);
    }

    private static Sort newestFirst() {
        return Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    }

    static SupportNoteResponse toResponse(SupportNote note) {
        return new SupportNoteResponse(note.getId(), note.getSubjectKind(), note.getSubjectId(), note.getBody(),
                new SupportAgent(note.getAgentUuid(), note.getAgentLogin()), note.getCreatedAt());
    }
}
