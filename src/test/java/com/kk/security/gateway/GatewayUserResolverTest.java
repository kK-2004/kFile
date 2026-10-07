package com.kk.security.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kk.security.entity.AdminUser;
import com.kk.security.gateway.GatewayTokenVerifier.GatewayIdentity;
import com.kk.security.repo.AdminUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** 网关身份 → kfile 账号：guid 优先、同名老账号首次绑定、不自动开户。 */
class GatewayUserResolverTest {

    private static final long GUID = 7351234567890123456L;
    private final AdminUserRepository repo = mock(AdminUserRepository.class);
    private final GatewayUserResolver resolver = new GatewayUserResolver(repo);
    private final GatewayIdentity alice = new GatewayIdentity(String.valueOf(GUID), "alice", "s1");

    private static AdminUser user(String username, Long guid, boolean enabled) {
        AdminUser u = new AdminUser();
        u.setUsername(username);
        u.setRole("ADMIN");
        u.setGuid(guid);
        u.setEnabled(enabled);
        return u;
    }

    @Test
    void boundByGuid() {
        when(repo.findByGuid(GUID)).thenReturn(Optional.of(user("alice-renamed", GUID, true)));
        var result = resolver.resolve(alice);
        assertThat(result).isInstanceOf(GatewayUserResolver.Bound.class);
        verify(repo, never()).findByUsername(any());
    }

    @Test
    void legacyAccountBoundByUsernameOnFirstLogin() {
        AdminUser legacy = user("alice", null, true);
        when(repo.findByGuid(GUID)).thenReturn(Optional.empty());
        when(repo.findByUsername("alice")).thenReturn(Optional.of(legacy));

        assertThat(resolver.resolve(alice)).isInstanceOf(GatewayUserResolver.Bound.class);
        assertThat(legacy.getGuid()).isEqualTo(GUID);
        verify(repo).save(legacy);
    }

    @Test
    void noAccountIsNotProvisionedAndNothingCreated() {
        when(repo.findByGuid(GUID)).thenReturn(Optional.empty());
        when(repo.findByUsername("alice")).thenReturn(Optional.empty());

        assertThat(resolver.resolve(alice)).isInstanceOf(GatewayUserResolver.NotProvisioned.class);
        verify(repo, never()).save(any());
    }

    @Test
    void sameNameBoundToAnotherGuidIsConflict() {
        when(repo.findByGuid(GUID)).thenReturn(Optional.empty());
        when(repo.findByUsername("alice")).thenReturn(Optional.of(user("alice", 1L, true)));
        assertThat(resolver.resolve(alice)).isInstanceOf(GatewayUserResolver.Conflict.class);
    }

    @Test
    void disabledAccountRejected() {
        when(repo.findByGuid(GUID)).thenReturn(Optional.of(user("alice", GUID, false)));
        assertThat(resolver.resolve(alice)).isInstanceOf(GatewayUserResolver.Disabled.class);
    }
}
