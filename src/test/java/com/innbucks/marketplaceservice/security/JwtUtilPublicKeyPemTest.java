package com.innbucks.marketplaceservice.security;

import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the accepted shapes of {@code jwt.public-key}. The cell provisions it
 * from a k8s Secret built with {@code --from-env-file}, where a PEM sits on one
 * line with its breaks written as literal two-character "\n" escapes. The
 * backslash is not Base64, so before the parser dropped the escapes that value
 * failed the boot ("Illegal base64 character 5c"). That is how user-service's
 * federation key crash-looped on the production cell (2026-10-02), and it would
 * have done the same to every service at step 1 of the RS256 migration.
 */
class JwtUtilPublicKeyPemTest {

    private static final String SECRET = "test-test-test-test-test-test-test-test";
    private static KeyPair keyPair;
    private static String pem;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        keyPair = gen.generateKeyPair();
        pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keyPair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    private static JwtUtil verifierWith(String publicKeyPem) {
        JwtUtil u = new JwtUtil();
        ReflectionTestUtils.setField(u, "secret", SECRET);
        ReflectionTestUtils.setField(u, "publicKeyPem", publicKeyPem);
        return u;
    }

    private static String rs256Token() {
        return Jwts.builder()
                .subject("rs@example.com")
                .issuer(JwtUtil.TOKEN_ISSUER)
                .audience().add(JwtUtil.TOKEN_AUDIENCE).and()
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(keyPair.getPrivate(), Jwts.SIG.RS256)
                .compact();
    }

    @Test
    void everyProvisionedShapeOfTheKeyVerifiesRs256() {
        String body = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        String[] shapes = {
                pem,  // real line breaks
                pem.replace("\n", "\\n"),  // one line, literal \n escapes
                pem.replace("\n", "\\r\\n"),  // one line, literal \r\n escapes
                pem.replace("\n", ""),  // armour, no breaks at all
                body  // bare Base64 body
        };
        String token = rs256Token();
        for (String shape : shapes) {
            JwtUtil u = verifierWith(shape);
            assertDoesNotThrow(u::initKeyMaterial, "boot must accept: " + shape.substring(0, 40));
            assertEquals("rs@example.com", u.extractSubject(token));
        }
    }

    @Test
    void aKeyThatIsStillNotBase64FailsTheBoot() {
        // Only the two-character escapes are dropped: any other stray backslash
        // sequence is still a malformed key and must stop the service at boot.
        JwtUtil u = verifierWith(pem.replace("\n", "\\t"));
        assertThrows(IllegalStateException.class, u::initKeyMaterial);
    }
}
