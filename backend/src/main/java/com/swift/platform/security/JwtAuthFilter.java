package com.swift.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                if (jwtUtil.isTokenValid(token)) {
                    String employeeId = jwtUtil.extractEmployeeId(token);
                    String role       = jwtUtil.extractRole(token);
                    String name       = jwtUtil.extractName(token);
                    String email      = jwtUtil.extractEmail(token);

                    req.setAttribute("employeeId", employeeId);
                    req.setAttribute("role",       role);
                    req.setAttribute("name",       name);
                    req.setAttribute("email",      email);

                    SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                            employeeId, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + role))
                        )
                    );
                }
            } catch (Exception ignored) {}
        }
        chain.doFilter(req, res);
    }
}
