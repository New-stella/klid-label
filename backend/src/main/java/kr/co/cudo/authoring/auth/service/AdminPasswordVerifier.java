package kr.co.cudo.authoring.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 관리자 공유 패스워드 검증의 <b>단일 판정 지점</b>.
 *
 * <p>패스워드 해시({@code authoring.auth.admin-claim-password-hash} ← 환경변수
 * {@code ADMIN_CLAIM_PASSWORD_HASH})를 읽는 곳과 BCrypt 비교를 하는 곳이 둘로 갈리면, 한쪽만
 * 강화·수정되어 다른 쪽이 조용히 약해진다. 그래서 <b>이 클래스만</b> 해시를 들고 있고
 * {@link RoleClaimService}(권한 자가부여)와 관리자 세션 발급이 <b>같은 판정</b>을 쓴다.
 *
 * <p>새 패스워드 체계를 만들지 않는다 — 기존 해시·기존 정책을 그대로 재사용한다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>CWE-256</b> — 평문은 어디에도 저장되지 않는다. 설정에는 BCrypt 해시만 있다.</li>
 *   <li><b>CWE-203</b> — {@link BCryptPasswordEncoder#matches} 가 상수시간 비교를 보장한다.</li>
 *   <li><b>CWE-532</b> — 이 클래스는 패스워드·해시를 <b>로그에 출력하지 않는다</b>(성공 여부만 반환).</li>
 *   <li><b>fail-closed</b> — 해시 미설정이면 항상 {@code false}. 즉 관련 기능이 열리는 일이 없다.</li>
 * </ul>
 */
@Component
public class AdminPasswordVerifier {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final String adminPasswordHash;

    public AdminPasswordVerifier(
            @Value("${authoring.auth.admin-claim-password-hash:}") String adminPasswordHash) {
        this.adminPasswordHash = normalizeHash(adminPasswordHash);
    }

    /**
     * 설정값을 검증해 보관한다. 평문이 설정에 들어오는 사고를 <b>기동 시점에</b> 막는다.
     *
     * <p>미설정(빈 값)은 기동을 막지 않는다 — 관련 기능만 항상 거절되면 되고, 이 값이 없다고 앱 전체가
     * 못 뜨면 이 기능과 무관한 배포까지 함께 막힌다.
     */
    private static String normalizeHash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (!isBcryptHash(trimmed)) {
            throw new IllegalStateException(
                    "authoring.auth.admin-claim-password-hash 는 BCrypt 해시여야 합니다 (시작 prefix $2a$/$2b$/$2y$).");
        }
        return trimmed;
    }

    private static boolean isBcryptHash(String value) {
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }

    /** 해시가 설정돼 있는가. 미설정이면 {@link #matches}가 항상 false 다. */
    public boolean isConfigured() {
        return !adminPasswordHash.isEmpty();
    }

    /**
     * 평문이 관리자 공유 패스워드와 일치하는가 (상수시간 비교).
     *
     * @return 미설정이거나 불일치면 {@code false}
     */
    public boolean matches(String rawPassword) {
        if (adminPasswordHash.isEmpty() || rawPassword == null) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, adminPasswordHash);
    }
}
