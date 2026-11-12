package com.judgex.web;

import com.judgex.auth.AuthFilter;
import com.judgex.auth.JwtService;
import com.judgex.auth.PasswordHasher;
import com.judgex.auth.UserPrincipal;
import com.judgex.common.ApiException;
import com.judgex.repo.UserMapper;
import com.judgex.web.dto.Dto;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserMapper users;
    private final JwtService jwt;

    public AuthController(UserMapper users, JwtService jwt) {
        this.users = users;
        this.jwt = jwt;
    }

    @PostMapping("/login")
    public Dto.LoginResponse login(@RequestBody Dto.LoginRequest req) {
        UserMapper.UserRow row = users.findByUsername(req.username() == null ? "" : req.username().trim())
                .orElseThrow(() -> new ApiException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码错误"));
        if (!PasswordHasher.matches(req.password() == null ? "" : req.password(), row.passwordHash())) {
            throw new ApiException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码错误");
        }
        UserPrincipal principal = new UserPrincipal(row.id(), row.username(), row.displayName(), row.role());
        return new Dto.LoginResponse(jwt.issue(principal), jwt.ttlSeconds(), principal);
    }

    @GetMapping("/me")
    public UserPrincipal me(@RequestAttribute(AuthFilter.USER_ATTR) UserPrincipal user) {
        return user;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of("ok", true);
    }
}
