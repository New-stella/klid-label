package kr.co.cudo.authoring.auth.m2m;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ConfigM2mTokenValidator implements M2mTokenValidator {

    private final byte[] expectedBytes;

    public ConfigM2mTokenValidator(@Value("${authoring.integration.control-server.m2m-token}") String expected) {
        this.expectedBytes = expected == null ? new byte[0] : expected.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean isValid(String token) {
        if (token == null || token.isBlank() || expectedBytes.length == 0) {
            return false;
        }
        byte[] actual = token.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actual);
    }
}
