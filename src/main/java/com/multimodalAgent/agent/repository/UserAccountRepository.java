package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.UserAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 用户账号的数据访问接口。
 */
public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    /** 登录认证时按用户名加载账号。 */
    Optional<UserAccount> findByUsername(String username);

    @Query("select distinct account from UserAccount account join account.roles role "
            + "where role = :role and account.enabled = true order by account.displayName, account.username")
    List<UserAccount> findEnabledByRole(@Param("role") String role);
}
