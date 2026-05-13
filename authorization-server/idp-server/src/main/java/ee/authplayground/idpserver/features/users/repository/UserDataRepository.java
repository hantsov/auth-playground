package ee.authplayground.idpserver.features.users.repository;

import ee.authplayground.idpserver.features.users.entity.UserData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserDataRepository extends JpaRepository<UserData, UUID> {

    Optional<UserData> findByUsername(String username);

    boolean existsByUsername(String username);
}
