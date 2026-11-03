package com.judgex.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class UserRepo {

    public record UserRow(long id, String username, String passwordHash, String displayName, String role) {
    }

    private final JdbcTemplate jdbc;

    public UserRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UserRow> findByUsername(String username) {
        List<UserRow> rows = jdbc.query(
                "SELECT id, username, password_hash, display_name, role FROM users WHERE username = ?",
                (rs, i) -> new UserRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                username);
        return rows.stream().findFirst();
    }

    public Optional<UserRow> findById(long id) {
        List<UserRow> rows = jdbc.query(
                "SELECT id, username, password_hash, display_name, role FROM users WHERE id = ?",
                (rs, i) -> new UserRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                id);
        return rows.stream().findFirst();
    }

    public void insert(String username, String passwordHash, String displayName, String role) {
        jdbc.update("INSERT INTO users (username, password_hash, display_name, role) VALUES (?,?,?,?)",
                username, passwordHash, displayName, role);
    }

    public long count() {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        return n == null ? 0 : n;
    }
}
