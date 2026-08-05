package com.learning.security.repository;

import com.learning.security.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    List<Document> findByOwnerUsername(String ownerUsername);

    List<Document> findByClassification(String classification);
}
