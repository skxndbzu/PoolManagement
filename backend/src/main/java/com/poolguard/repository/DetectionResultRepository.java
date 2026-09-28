package com.poolguard.repository;

import com.poolguard.model.DetectionResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DetectionResultRepository extends JpaRepository<DetectionResult, UUID> {
    java.util.List<DetectionResult> findByRunIdOrderByCreatedAtAsc(UUID runId);
}
