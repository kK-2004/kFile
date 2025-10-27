package com.kk.security;

import com.kk.security.entity.UserAccount;
import com.kk.security.service.UserAccountService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class SiteJwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserAccountService userAccountService;

    public SiteJwtAuthConverter(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        // 1) 从 token roles 映射站点权限（兼容数值型：1=ADMIN, 0=USER/NORMAL；兼容单值 role 与数组 roles）
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        boolean adminAdded = false;
        boolean userAdded = false;
        for (String r : extractRolesFlexible(jwt)) {
            switch (r.toUpperCase()) {
                case "ADMIN", "1" -> {
                    if (!adminAdded) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_SITE_ADMIN"));
                        adminAdded = true;
                    }
                }
                case "USER", "NORMAL", "0" -> {
                    if (!userAdded) {
                        authorities.add(new SimpleGrantedAuthority("ROLE_SITE_USER"));
                        userAdded = true;
                    }
                }
            }
        }
        // 站点 ADMIN 同时映射为本地 ROLE_SUPER，以复用 hasRole('SUPER') 保护
        if (adminAdded) {
            authorities.add(new SimpleGrantedAuthority("ROLE_SUPER"));
        }
//         2) 追加本地角色(已移至)userAccountService.findOrCreateByJwt()
//        UserAccount ua = userAccountService.findOrCreateByJwt(jwt,null);
//        String local = ua.getRole() == null ? "KF_USER" : ua.getRole();
//        switch (local) {
//            case "KF_SUPER" -> authorities.add(new SimpleGrantedAuthority("ROLE_KF_SUPER"));
//            case "KF_ADMIN" -> authorities.add(new SimpleGrantedAuthority("ROLE_KF_ADMIN"));
//            default -> authorities.add(new SimpleGrantedAuthority("ROLE_KF_USER"));
//        }

        return new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRolesFlexible(Jwt jwt) {
        List<String> out = new ArrayList<>();
        Object v = jwt.getClaims().get("roles");
        if (v instanceof List<?> list) {
            for (Object o : list) out.add(String.valueOf(o));
        } else if (v instanceof String s) {
            for (String it : s.split(",")) out.add(it.trim());
        }
        // 兼容单值 role
        Object single = jwt.getClaims().get("role");
        if (single != null) out.add(String.valueOf(single));
        return out;
    }
}
