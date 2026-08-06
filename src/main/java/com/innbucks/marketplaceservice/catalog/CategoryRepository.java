package com.innbucks.marketplaceservice.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CategoryRepository extends JpaRepository<Category, String> {

    /** Children of a top-level code — the browse parent-expansion read. */
    List<Category> findByParentCode(String parentCode);

    /** Full tree in one query, display-ordered; the tree endpoint groups
     *  parents/children in memory (the table is a few dozen rows). */
    List<Category> findAllByOrderByNameAsc();
}
