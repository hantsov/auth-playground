package ee.authplayground.userdatamaster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * user-data-master — the golden record for user identity.
 * <p>
 * Owns two things and nothing else: who each person is ({@code users}) and what
 * they can present to prove it ({@code user_credentials}). It authenticates
 * nobody. It hands credential records to the one service allowed to ask, and
 * person attributes to services that need them.
 * <p>
 * <b>This is tier-0 infrastructure.</b> idp-server has no database of its own,
 * so this service being down means nobody logs in, anywhere. That is the
 * standard trade in the user-federation pattern — an IdP reading from an
 * external directory — and it is the same classification an LDAP directory
 * carries in a real deployment.
 */
@SpringBootApplication
public class UserDataMasterApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserDataMasterApplication.class, args);
    }

}
