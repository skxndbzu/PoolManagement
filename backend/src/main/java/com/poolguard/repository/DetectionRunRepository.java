package com.poolguard.repository;

import com.poolguard.model.DetectionRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DetectionRunRepository extends JpaRepository<DetectionRun, UUID> {
    List<DetectionRun> findTop20ByOrderByStartedAtDesc();
    java.util.List<DetectionRun> findByStatus(com.poolguard.model.RunStatus status);
}
