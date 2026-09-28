package com.poolguard.service;

import com.poolguard.dto.PolicyDtos;
import com.poolguard.model.CheckPolicy;
import com.poolguard.repository.CheckPolicyRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class PolicyService {
    private final CheckPolicyRepository repository;
    private final OperationLock lock;
    private final jakarta.validation.Validator validator;
    private final org.springframework.transaction.support.TransactionTemplate transactions;

    public PolicyService(CheckPolicyRepository repository, OperationLock lock, org.springframework.transaction.PlatformTransactionManager manager, jakarta.validation.Validator validator) {
        this.repository = repository;
        this.lock = lock;
        this.validator = validator;
        this.transactions = new org.springframework.transaction.support.TransactionTemplate(manager);
    }

    public List<PolicyDtos.PolicyResponse> list() {
        return repository.findAllByOrderBySortOrderAsc().stream().map(this::toResponse).toList();
    }

    public PolicyDtos.PolicyResponse create(PolicyDtos.PolicyRequest request) {
        return change(() -> {
        validateAnswer(request.answer());
        CheckPolicy policy = new CheckPolicy(UUID.randomUUID(), request.title(), request.answer(), request.model(),
            request.sortOrder() == null ? repository.findAll().size() + 1 : request.sortOrder(),
            request.active() == null || request.active(), 1, now(), now());
        return toResponse(repository.save(policy));
        });
    }

    public PolicyDtos.PolicyResponse update(UUID id, PolicyDtos.PolicyRequest request) {
        return change(() -> {
        validateAnswer(request.answer());
        CheckPolicy policy = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "检测策略不存在"));
        policy.setTitle(request.title());
        policy.setExpectedAnswer(request.answer());
        policy.setCapabilityTag(request.model());
        if (request.sortOrder() != null) policy.setSortOrder(request.sortOrder());
        if (request.active() != null) policy.setActive(request.active());
        policy.setVersion(policy.getVersion() + 1);
        policy.setUpdatedAt(now());
        return toResponse(repository.save(policy));
        });
    }

    public void delete(UUID id) {
        change(() -> {
        if (!repository.existsById(id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "检测策略不存在");
        repository.deleteById(id);
        return null;
        });
    }

    public List<PolicyDtos.PolicyResponse> replace(List<PolicyDtos.PolicySave> items) {
        if (items == null || items.size() > 200) throw new IllegalArgumentException("问题集最多支持 200 道题");
        for (var item : items) {
            if (item == null) throw new IllegalArgumentException("题目不能为空");
            var violations = validator.validate(item);
            if (!violations.isEmpty()) throw new IllegalArgumentException(violations.iterator().next().getMessage());
        }
        return change(() -> {
            var keep = new java.util.HashSet<UUID>();
            int order = 1;
            for (var item : items) {
                var request = item.policy();
                validateAnswer(request.answer());
                var policy = item.id() == null
                    ? CheckPolicy.builder().id(UUID.randomUUID()).createdAt(now()).version(0).build()
                    : repository.findById(item.id()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "检测策略不存在"));
                if (!keep.add(policy.getId())) throw new IllegalArgumentException("问题集包含重复 ID");
                policy.setTitle(request.title()); policy.setExpectedAnswer(request.answer());
                policy.setCapabilityTag(request.model()); policy.setSortOrder(order++);
                policy.setActive(request.active() == null || request.active());
                policy.setVersion(policy.getVersion() + 1); policy.setUpdatedAt(now());
                repository.save(policy);
            }
            for (var policy : repository.findAll()) if (!keep.contains(policy.getId())) repository.delete(policy);
            return list();
        });
    }

    private void validateAnswer(String answer) {
        if (answer.startsWith("keywords:") && answer.substring(9).isBlank())
            throw new IllegalArgumentException("keywords: 后必须填写至少一个关键词");
    }

    private <T> T change(java.util.function.Supplier<T> operation) {
        try (var lease = lock.acquire()) {
            lease.assertHeld();
            return transactions.execute(status -> operation.get());
        }
    }

    private PolicyDtos.PolicyResponse toResponse(CheckPolicy policy) {
        return new PolicyDtos.PolicyResponse(policy.getId(), policy.getTitle(), policy.getExpectedAnswer(),
            policy.getCapabilityTag(), policy.getSortOrder(), Boolean.TRUE.equals(policy.getActive()), policy.getVersion());
    }

    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
}
