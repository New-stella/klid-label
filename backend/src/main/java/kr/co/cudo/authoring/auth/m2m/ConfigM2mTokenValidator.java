package kr.co.cudo.authoring.auth.m2m;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.Map;

/**
 * 설정 기반 M2M 토큰 검증기 — scope 별 토큰을 분리 보유.
 *
 * <p>{@link MessageDigest#isEqual(byte[], byte[])} 사용으로 타이밍 공격 방어.
 * 환경변수 미설정 시 빈 배열 보관 → 모든 토큰 검증 실패 (fail-closed, CWE-285).
 */
@Component
public class ConfigM2mTokenValidator implements M2mTokenValidator {

    private final Map<Scope, byte[]> expectedByScope;

    public ConfigM2mTokenValidator(
            @Value("${authoring.integration.control-server.m2m-token}") String controlToken,
            @Value("${authoring.integration.learning-data.m2m-token}") String learningDataToken) {
        this.expectedByScope = new EnumMap<>(Scope.class);
        this.expectedByScope.put(Scope.CONTROL, toBytes(controlToken));
        this.expectedByScope.put(Scope.LEARNING_DATA, toBytes(learningDataToken));
    }

    @Override
    public boolean isValid(String token, Scope scope) {
        if (token == null || token.isBlank() || scope == null) {
            return false;
        }
        byte[] expected = expectedByScope.get(scope);
        if (expected == null || expected.length == 0) {
            return false;
        }
        byte[] actual = token.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] toBytes(String s) {
        return s == null ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
    }
}
