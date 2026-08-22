package ee.authplayground.userdatamaster.features.users.repository;

import ee.authplayground.userdatamaster.features.users.entity.UserData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserDataRepository extends JpaRepository<UserData, UUID> {

    /**
     * Both halves, because a national ID is only unique within a country.
     * There is deliberately no {@code findByNationalId} — offering one would
     * invite a caller to look up a person by a number that two people can share.
     */
    Optional<UserData> findByNationalityAndNationalId(String nationality, String nationalId);

    boolean existsByUsername(String username);
}
