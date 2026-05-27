package com.swift.platform.repository;

import com.swift.platform.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByEmployeeId(String employeeId);
    Optional<User> findByEmail(String email);
    boolean existsByEmployeeId(String employeeId);
    boolean existsByEmail(String email);
    boolean existsByEmailAndEmployeeIdNot(String email, String employeeId);
    Page<User> findByRole(String role, Pageable pageable);
    Page<User> findByActive(boolean active, Pageable pageable);
    @Query("{ $or: [{'name':{$regex:?0,$options:'i'}},{'email':{$regex:?0,$options:'i'}},{'employeeId':{$regex:?0,$options:'i'}}] }")
    Page<User> searchUsers(String keyword, Pageable pageable);
    long countByRole(String role);
    long countByActive(boolean active);
}
