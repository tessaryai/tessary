// SPDX-License-Identifier: Apache-2.0
package ai.tessary.git.github;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.PrivateKey;
import org.junit.jupiter.api.Test;

/**
 * Private-key parsing accepts both the PKCS#1 form GitHub Apps download
 * ({@code BEGIN RSA PRIVATE KEY}) and PKCS#8 ({@code BEGIN PRIVATE KEY}).
 * Both fixtures below are the SAME throwaway 2048-bit key in the two formats,
 * so parsing either must yield byte-identical key material.
 */
class GithubTokenServiceKeyTest {

    // Throwaway test key — generated for this test only, not used anywhere.
    private static final String PKCS1 = """
            -----BEGIN RSA PRIVATE KEY-----
            MIIEowIBAAKCAQEAr3MXg0vD10E1ZD3ig6feglhRXI5CjwLWFY4wbL0LXwL1cNGk
            ITblJwz6Zo3dHCvhAo8RT+l3PFK1QDDLBwA/lWFNVX7+V39sfpl7eNGc2ifGLQfl
            LTURDPaekXeSmTuCzvemOTstRVge3kHgdn0w1EFjYfrEPggTZTimPNNWqPKJdLfq
            J5ILY+bUgjvFlZdnZP3aG6yVpqvkrTAHI9yayFQYTqTXSUg54bK7RAG9mSNNvHeP
            NSCKsB5GoQ/wOuP2Yn4H1RNyzICRcJRqmcDtlYVAgHb+SvY2NqaORPnjGdVJCTax
            Grl9QoCYUNpyUwOgf/8m2vKOUMOy5NVWr31XtwIDAQABAoIBACdbLOIR48tsYDqt
            OxuHU31vrEiTzNBhtX4+WBR8T4mvkzkMfcHJm5un6J+KoRfJq/6z8xE28L1CXFAa
            4eAfKSQsIHnWM92SwtafPTg75PB2zZiaVclPRxTeWXGOHf9rfwtIOCxMXvpYKwx2
            QJCzwnlKda2r38t+akNhcsDgM2dQKIdYTrgXt6TIFVlw4n4vaDI5QQyiuGz5kqeF
            3OMAgD/WLo8cRwIkxcMCj9mszqn6e3BOeVgM102zTDAI5gIu8zlwIjNKJONQv5PW
            oHC+FsfyofFHzWMlpATRPg4opDIua6NjQmpgP/EA4WIzr3shR4qjih6Q7Wtd4DvR
            7s+LUGkCgYEA9BdEyRVU5vfH0m3UHWmlxbD5Cd970aJK1L2SrGJ6aZzI33IosYRc
            U4sklJyE/0pmvQfYZK1hs2qKXuJe2OAMRG6ODMMKgCdioKdOBXXeVmECyRJ6s8W2
            CayP6guUSPArJ9xuLONudhiGTeJey8+lmsB8dYk+3GtxVgJ8AhZnGxsCgYEAuAJ7
            pLZkFYZeUoWx5J0ZsbILD2oLhDxY2XJUtulXaJefqLR0Ga0+GC4dZjFpD1ZuUWAU
            UJH7RynPScdYTC/UzB55GmhrMXu7ESXyk+PGmPsN6wGkM1f5b7JGX/j9FBjd8FCx
            OGKkTYvLREzs6kpyH6lFjpyUk6SW7YdBKqPPw5UCgYEAy2p40HsOfk0QIbWVh817
            cPzDDg9IyLNPWWuCyFZpXYpjfJNOhmGf75+NpKurynTembnoBD7ZpQOsvNY55NMS
            ZhUcHFaOca5g6zGCO9q2p5XBFIBp8VdbFUTRymJL+VztGhMBXjdK3vAhPNbh2Uf6
            4rR3BByUXmzLzrnTTooM8vECgYAbq4vqPd8Y+YavhBk3FoSpmutc2wZ2URjPQgkJ
            JFeXi81fchtPTSTcP/r9xgpVWxrls7v6TalqjxfzsT1O35ZxR1fQp8kOvNtpfbpX
            kNXpZT83ipyld+IzcKxyfB+aaQ7et1Oe9f5dBtt/Hs41gRJePncONe+FsCdd+ovL
            HKz+bQKBgH3uaEh2xphkFbidFLF6e5eaTAQol0uyooJwNha/JlWvqIWHES8HfnZ5
            2Kl5xnmbbZGayKUXjdCHKwBSp4u9UH8oj55gyNVC1LU4/6/CLc+p4rQlea9HuPSc
            9tYaP/xGP5nautRBujmLtKO0TvXcNwSZbEGSZclA8EFgOzHyi8K8
            -----END RSA PRIVATE KEY-----
            """;

    private static final String PKCS8 = """
            -----BEGIN PRIVATE KEY-----
            MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCvcxeDS8PXQTVk
            PeKDp96CWFFcjkKPAtYVjjBsvQtfAvVw0aQhNuUnDPpmjd0cK+ECjxFP6Xc8UrVA
            MMsHAD+VYU1Vfv5Xf2x+mXt40ZzaJ8YtB+UtNREM9p6Rd5KZO4LO96Y5Oy1FWB7e
            QeB2fTDUQWNh+sQ+CBNlOKY801ao8ol0t+onkgtj5tSCO8WVl2dk/dobrJWmq+St
            MAcj3JrIVBhOpNdJSDnhsrtEAb2ZI028d481IIqwHkahD/A64/ZifgfVE3LMgJFw
            lGqZwO2VhUCAdv5K9jY2po5E+eMZ1UkJNrEauX1CgJhQ2nJTA6B//yba8o5Qw7Lk
            1VavfVe3AgMBAAECggEAJ1ss4hHjy2xgOq07G4dTfW+sSJPM0GG1fj5YFHxPia+T
            OQx9wcmbm6fon4qhF8mr/rPzETbwvUJcUBrh4B8pJCwgedYz3ZLC1p89ODvk8HbN
            mJpVyU9HFN5ZcY4d/2t/C0g4LExe+lgrDHZAkLPCeUp1ravfy35qQ2FywOAzZ1Ao
            h1hOuBe3pMgVWXDifi9oMjlBDKK4bPmSp4Xc4wCAP9YujxxHAiTFwwKP2azOqfp7
            cE55WAzXTbNMMAjmAi7zOXAiM0ok41C/k9agcL4Wx/Kh8UfNYyWkBNE+DiikMi5r
            o2NCamA/8QDhYjOveyFHiqOKHpDta13gO9Huz4tQaQKBgQD0F0TJFVTm98fSbdQd
            aaXFsPkJ33vRokrUvZKsYnppnMjfciixhFxTiySUnIT/Sma9B9hkrWGzaope4l7Y
            4AxEbo4MwwqAJ2Kgp04Fdd5WYQLJEnqzxbYJrI/qC5RI8Csn3G4s4252GIZN4l7L
            z6WawHx1iT7ca3FWAnwCFmcbGwKBgQC4AnuktmQVhl5ShbHknRmxsgsPaguEPFjZ
            clS26Vdol5+otHQZrT4YLh1mMWkPVm5RYBRQkftHKc9Jx1hML9TMHnkaaGsxe7sR
            JfKT48aY+w3rAaQzV/lvskZf+P0UGN3wULE4YqRNi8tETOzqSnIfqUWOnJSTpJbt
            h0Eqo8/DlQKBgQDLanjQew5+TRAhtZWHzXtw/MMOD0jIs09Za4LIVmldimN8k06G
            YZ/vn42kq6vKdN6ZuegEPtmlA6y81jnk0xJmFRwcVo5xrmDrMYI72ranlcEUgGnx
            V1sVRNHKYkv5XO0aEwFeN0re8CE81uHZR/ritHcEHJRebMvOudNOigzy8QKBgBur
            i+o93xj5hq+EGTcWhKma61zbBnZRGM9CCQkkV5eLzV9yG09NJNw/+v3GClVbGuWz
            u/pNqWqPF/OxPU7flnFHV9CnyQ6822l9uleQ1ellPzeKnKV34jNwrHJ8H5ppDt63
            U571/l0G238ezjWBEl4+dw4174WwJ136i8scrP5tAoGAfe5oSHbGmGQVuJ0UsXp7
            l5pMBCiXS7KignA2Fr8mVa+ohYcRLwd+dnnYqXnGeZttkZrIpReN0IcrAFKni71Q
            fyiPnmDI1ULUtTj/r8Itz6nitCV5r0e49Jz21ho//EY/mdq61EG6OYu0o7RO9dw3
            BJlsQZJlyUDwQWA7MfKLwrw=
            -----END PRIVATE KEY-----
            """;

    @Test
    void parsesPkcs1GitHubKey() throws Exception {
        PrivateKey key = GithubTokenService.parsePrivateKey(PKCS1);
        assertEquals("RSA", key.getAlgorithm());
    }

    @Test
    void parsesPkcs8Key() throws Exception {
        PrivateKey key = GithubTokenService.parsePrivateKey(PKCS8);
        assertEquals("RSA", key.getAlgorithm());
    }

    @Test
    void pkcs1AndPkcs8YieldSameKey() throws Exception {
        // Same key, two encodings — the PKCS#1 wrapping must reconstruct the
        // exact PKCS#8 PrivateKeyInfo the JDK produces for the PKCS#8 input.
        PrivateKey fromPkcs1 = GithubTokenService.parsePrivateKey(PKCS1);
        PrivateKey fromPkcs8 = GithubTokenService.parsePrivateKey(PKCS8);
        assertArrayEquals(fromPkcs8.getEncoded(), fromPkcs1.getEncoded());
    }

    @Test
    void rejectsGarbage() {
        assertThrows(
                Exception.class,
                () -> GithubTokenService.parsePrivateKey(
                        "-----BEGIN PRIVATE KEY-----\nnot-base64!!!\n-----END PRIVATE KEY-----"));
    }
}
