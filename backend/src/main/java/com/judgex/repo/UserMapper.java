package com.judgex.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

/**
 * Users table. The interface holds the SQL contract; the statements live in
 * {@code resources/mapper/UserMapper.xml}.
 */
@Mapper
public interface UserMapper {

    record UserRow(long id, String username, String passwordHash, String displayName, String role) {
    }

    UserRow selectByUsername(@Param("username") String username);

    UserRow selectById(@Param("id") long id);

    int insertRow(@Param("username") String username,
                  @Param("passwordHash") String passwordHash,
                  @Param("displayName") String displayName,
                  @Param("role") String role);

    long count();

    default Optional<UserRow> findByUsername(String username) {
        return Optional.ofNullable(selectByUsername(username));
    }

    default Optional<UserRow> findById(long id) {
        return Optional.ofNullable(selectById(id));
    }

    default void insert(String username, String passwordHash, String displayName, String role) {
        insertRow(username, passwordHash, displayName, role);
    }
}
