package com.kk.security.service;

import com.kk.security.entity.AdminUser;
import com.kk.security.repo.AdminUserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AdminUserDetailsService implements UserDetailsService {
    @Autowired
    private AdminUserRepository adminUserRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AdminUser u = adminUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
        String role = u.getRole() == null ? "" : u.getRole().toUpperCase();
        GrantedAuthority auth = new SimpleGrantedAuthority("ROLE_" + role);
        return new User(u.getUsername(), u.getPassword(), u.getEnabled(), true, true, true, List.of(auth));
    }
}
