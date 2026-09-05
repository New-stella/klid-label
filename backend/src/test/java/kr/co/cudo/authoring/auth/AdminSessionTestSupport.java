package kr.co.cudo.authoring.auth;

import kr.co.cudo.authoring.auth.service.AdminPasswordVerifier;
import kr.co.cudo.authoring.auth.service.AdminSessionTokenService;
import kr.co.cudo.authoring.common.security.JwtKeyResolver;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 관리자 유효창 테스트 보조 — 토큰 서비스가 <b>현재 관리자 자격</b>에 매달려 있기 때문에 필요하다.
 *
 * <p>유효창 서명 키는 JWT 서명 키와 현재 자격에서 함께 파생된다. 그래서 자격이 하나도 없는 구성에서는
 * 토큰이 발급되지도 검증되지도 않는다(fail-closed). 자격 축을 다루지 <b>않는</b> 테스트가 그 사실 때문에
 * 깨지지 않도록, 여기서 <b>임의의 유효한 자격 하나</b>를 물려 준다.
 *
 * <p>⚠ 자격 교체가 유효창을 무효화하는지를 보는 테스트는 이 헬퍼를 쓰지 말고 자격을 직접 구성해
 * 바꿔 가며 확인해야 한다 — 여기 자격은 고정이라 그 축을 덮지 못한다.
 */
public final class AdminSessionTestSupport {

    /** 테스트 고정 자격의 평문. */
    public static final String ADMIN_PLAINTEXT = "test-admin-secret";

    /** 테스트 고정 자격의 BCrypt 해시. */
    public static final String ADMIN_HASH = new BCryptPasswordEncoder(12).encode(ADMIN_PLAINTEXT);

    private AdminSessionTestSupport() {
    }

    /** 배포 설정값만으로 판정하는 검증기(저장소 없음). */
    public static AdminPasswordVerifier verifier() {
        return new AdminPasswordVerifier(ADMIN_HASH);
    }

    /** 고정 자격이 물린 토큰 서비스. */
    public static AdminSessionTokenService tokenService(JwtKeyResolver keyResolver, long ttlMinutes) {
        return new AdminSessionTokenService(keyResolver, verifier(), ttlMinutes);
    }
}
