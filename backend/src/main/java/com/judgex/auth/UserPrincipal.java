package com.judgex.auth;

/** Authenticated caller, resolved from the JWT on every request. */
public record UserPrincipal(long id, String username, String displayName, String role) {
    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
