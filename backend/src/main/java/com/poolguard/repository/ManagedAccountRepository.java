package com.poolguard.repository;

import com.poolguard.model.AccountStatus;
import com.poolguard.model.ManagedAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ManagedAccountRepository extends JpaRepository<ManagedAccount, UUID> {
    @Query("""
        select a from ManagedAccount a
        where (cast(:project as string) is null or a.projectCode = cast(:project as string))
          and (cast(:search as string) is null or lower(a.emailMasked) like lower(concat('%', cast(:search as string), '%'))
               or lower(a.externalAccountId) like lower(concat('%', cast(:search as string), '%'))
               or lower(a.projectCode) like lower(concat('%', cast(:search as string), '%'))
               or lower(a.model) like lower(concat('%', cast(:search as string), '%'))
               or lower(a.detectionModels) like lower(concat('%', cast(:search as string), '%')))
        order by a.updatedAt desc
        """)
    List<ManagedAccount> search(String project, String search);

    List<ManagedAccount> findByNextCheckAtLessThanEqualAndStatusNot(OffsetDateTime time, AccountStatus status);

    java.util.List<ManagedAccount> findByProjectCode(String projectCode);

    long countByStatus(AccountStatus status);

    java.util.Optional<ManagedAccount> findByProjectCodeAndExternalAccountId(String projectCode, String externalAccountId);
}
