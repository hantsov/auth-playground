package ee.authplayground.resourceserver.features.user.repository;

import ee.authplayground.resourceserver.features.user.entity.UserData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserDataRepository extends JpaRepository<UserData, Long> {

    Optional<UserData> findByKeycloakUserId(String keycloakUserId);

    Optional<UserData> findByUsername(String username);
}
