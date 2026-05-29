package org.stagepass.userservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.stagepass.userservice.entity.AuthProvider;
import org.stagepass.userservice.entity.User;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByProviderIdAndAuthProvider(String providerId, AuthProvider authProvider);

}
