package com.poolguard.repository;

import com.poolguard.model.CheckPolicy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CheckPolicyRepository extends JpaRepository<CheckPolicy, UUID> {
    List<CheckPolicy> findByActiveTrueOrderBySortOrderAsc();
    List<CheckPolicy> findAllByOrderBySortOrderAsc();
}
