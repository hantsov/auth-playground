package ee.authplayground.idpserver.appcore.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.UUID;

/**
 * Persists the RSA signing key for issued ID/access tokens to a local file.
 * <p>
 * On startup:
 * <ul>
 *   <li>If the file at {@code playground.idp.signing-key-path} exists, load
 *       it as a JWKS JSON document (private key included).</li>
 *   <li>Otherwise generate a fresh 2048-bit RSA key pair, serialize it as
 *       JWKS JSON (including the private key), and write it to that path.</li>
 * </ul>
 * <p>
 * Why JWKS JSON rather than PEM/PKCS12: it's the same shape Spring
 * Authorization Server publishes at the public {@code /oauth2/jwks}
 * endpoint, so the on-disk file is literally "the private side of our
 * published JWKS." Nimbus does the round-trip serialization in one call.
 * <p>
 * Key rotation, for this playground, is "delete the file and restart."
 * The keys directory is gitignored; for Docker, bind-mount it so the key
 * survives container rebuilds.
 */
@Configuration
@Slf4j
public class JwkConfig {

    @Value("${playground.idp.signing-key-path:./keys/signing-key.json}")
    private String signingKeyPath;

    @Bean
    public JWKSource<SecurityContext> jwkSource() throws IOException, ParseException {
        Path path = Path.of(signingKeyPath);
        JWKSet jwkSet;

        if (Files.exists(path)) {
            log.info("Loading existing signing key from {}", path.toAbsolutePath());
            jwkSet = JWKSet.parse(Files.readString(path));
        } else {
            log.info("No signing key found at {} — generating a fresh RSA key pair", path.toAbsolutePath());
            jwkSet = generateAndPersist(path);
        }

        return new ImmutableJWKSet<>(jwkSet);
    }

    private JWKSet generateAndPersist(Path path) throws IOException {
        KeyPair keyPair = generateRsa();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .algorithm(JWSAlgorithm.RS256)
                .keyUse(KeyUse.SIGNATURE)
                .build();
        JWKSet jwkSet = new JWKSet(rsaKey);

        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        // JWKSet.toString(boolean publicKeysOnly) — passing `false` means
        // "do NOT restrict to public keys", i.e. include private params.
        // Without this, the file is unusable for signing on next startup.
        Files.writeString(path, jwkSet.toString(false));
        log.info("Wrote new signing key (kid={}) to {}", rsaKey.getKeyID(), path.toAbsolutePath());

        return jwkSet;
    }

    private static KeyPair generateRsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key pair", e);
        }
    }
}
