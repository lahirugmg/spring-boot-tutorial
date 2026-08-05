package com.learning.security.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/** Used to demonstrate ownership-based authorisation with {@code @PreAuthorize}/{@code @PostAuthorize}. */
@Entity
@Table(name = "document")
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "document_seq_gen")
    @SequenceGenerator(name = "document_seq_gen", sequenceName = "document_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "owner_username", nullable = false, length = 100)
    private String ownerUsername;

    @Column(nullable = false, length = 20)
    private String classification;

    @Column(columnDefinition = "text")
    private String content;

    protected Document() {
    }

    public Document(String title, String ownerUsername, String classification, String content) {
        this.title = title;
        this.ownerUsername = ownerUsername;
        this.classification = classification;
        this.content = content;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getOwnerUsername() { return ownerUsername; }
    public String getClassification() { return classification; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
