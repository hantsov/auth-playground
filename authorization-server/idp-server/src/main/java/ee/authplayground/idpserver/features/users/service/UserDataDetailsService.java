package ee.authplayground.idpserver.features.users.service;

import ee.authplayground.idpserver.features.users.repository.UserDataRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Bridges Spring Security's form-login filter to our JPA user store.
 * <p>
 * The form-login filter calls {@link #loadUserByUsername(String)} during
 * authentication. We translate our {@link ee.authplayground.idpserver.features.users.entity.UserData}
 * row into Spring Security's {@link UserDetails} contract — the password
 * is the stored BCrypt hash, which the {@code BCryptPasswordEncoder} bean
 * verifies against the submitted plaintext.
 */
@Service
@RequiredArgsConstructor
public class UserDataDetailsService implements UserDetailsService {

    private final UserDataRepository userDataRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userDataRepository.findByUsername(username)
                .map(user -> User.builder()
                        .username(user.getUsername())
                        .password(user.getPasswordHash())
                        .disabled(!user.isEnabled())
                        .roles("USER")
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }
}
