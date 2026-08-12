package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Immutable semantic parent section within a published knowledge version. */
@Entity
@Table(
        name = "knowledge_version_sections",
        indexes = @Index(
                name = "idx_kv_section_source",
                columnList = "knowledge_version_id,source,section_index"),
        uniqueConstraints = @UniqueConstraint(
                name = "uk_kv_section_parent_key",
                columnNames = {"knowledge_version_id", "parent_key"}))
public class KnowledgeVersionSection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "knowledge_version_id", nullable = false)
    private Long knowledgeVersionId;

    @Column(name = "parent_key", nullable = false, length = 64)
    private String parentKey;

    @Column(nullable = false, length = 180)
    private String source;

    @Column(name = "section_index", nullable = false)
    private int sectionIndex;

    @Column(name = "section_path", nullable = false, length = 500)
    private String sectionPath;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "start_offset", nullable = false)
    private int startOffset;

    @Column(name = "end_offset", nullable = false)
    private int endOffset;

    @Column(name = "page_start")
    private Integer pageStart;

    @Column(name = "page_end")
    private Integer pageEnd;

    public Long getId() { return id; }
    public Long getKnowledgeVersionId() { return knowledgeVersionId; }
    public void setKnowledgeVersionId(Long value) { knowledgeVersionId = value; }
    public String getParentKey() { return parentKey; }
    public void setParentKey(String value) { parentKey = value; }
    public String getSource() { return source; }
    public void setSource(String value) { source = value; }
    public int getSectionIndex() { return sectionIndex; }
    public void setSectionIndex(int value) { sectionIndex = value; }
    public String getSectionPath() { return sectionPath; }
    public void setSectionPath(String value) { sectionPath = value; }
    public String getContent() { return content; }
    public void setContent(String value) { content = value; }
    public int getStartOffset() { return startOffset; }
    public void setStartOffset(int value) { startOffset = value; }
    public int getEndOffset() { return endOffset; }
    public void setEndOffset(int value) { endOffset = value; }
    public Integer getPageStart() { return pageStart; }
    public void setPageStart(Integer value) { pageStart = value; }
    public Integer getPageEnd() { return pageEnd; }
    public void setPageEnd(Integer value) { pageEnd = value; }
}
