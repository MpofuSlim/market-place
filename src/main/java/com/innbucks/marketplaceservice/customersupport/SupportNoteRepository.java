package com.innbucks.marketplaceservice.customersupport;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SupportNoteRepository extends JpaRepository<SupportNote, UUID> {

    Page<SupportNote> findBySubjectKindAndSubjectId(String subjectKind, String subjectId, Pageable pageable);

    long countBySubjectKindAndSubjectId(String subjectKind, String subjectId);
}
