package com.swift.platform.service;

import com.swift.platform.config.AppConfig;
import com.swift.platform.dto.LoginRequest;
import com.swift.platform.dto.LoginResponse;
import com.swift.platform.model.User;
import com.swift.platform.repository.UserRepository;
import com.swift.platform.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final JwtUtil        jwtUtil;
    private final UserService    userService;
    private final AppConfig      appConfig;

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmployeeId(request.getEmployeeId())
                .orElseThrow(() -> new RuntimeException("Invalid Employee ID or password"));

        if (!user.isActive())
            throw new RuntimeException("Account is disabled. Contact your administrator.");

        if (!request.getPassword().equals(user.getPassword()))
            throw new RuntimeException("Invalid Employee ID or password");

        String requestedMode = request.getLoginMode() != null
                ? request.getLoginMode().toUpperCase() : "EMPLOYEE";

        if (!user.getRole().equalsIgnoreCase(requestedMode))
            throw new RuntimeException(
                    "Access denied: your account does not have " + requestedMode + " privileges.");

        userService.updateLastLogin(user.getEmployeeId());

        String token = jwtUtil.generateToken(
                user.getEmployeeId(), user.getRole(), user.getName(), user.getEmail());

        log.info("Login successful: {}", user.getEmployeeId());

        return new LoginResponse(
                token,
                user.getEmployeeId(),
                user.getName(),
                user.getRole(),
                user.getEmail(),
                appConfig.getJwtExpiration()
        );
    }
}
