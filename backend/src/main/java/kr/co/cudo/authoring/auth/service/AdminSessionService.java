package kr.co.cudo.authoring.auth.service;

import kr.co.cudo.authoring.auth.dto.AdminSessionRequest;
import kr.co.cudo.authoring.auth.dto.AdminSessionResponse;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 관리자 단기 유효창 발급 서비스 (R11).
 *
 * <h3>왜 {@code RoleClaimService.claim} 을 재사용하지 않는가</h3>
 * <p>그 서비스는 <b>관리자 부트스트랩 전용</b>이다(관리자가 1명 이상이면 409). 이 흐름의 사용자는
 * <b>이미 ADMIN</b> 이므로 그대로 부르면 항상 409 가 난다. 게다가 그 API 는 성공 시
 * <b>역할을 부여하고 새 인증 토큰을 발급</b>하는데, 여기서 필요한 것은 "주소를 바꿔도 되는 짧은 창"
 * 하나뿐이다.
 *
 * <p>그래서 <b>진입점만 분리</b>하고 방어는 그대로 공유한다 — 같은 패스워드 해시
 * ({@link AdminPasswordVerifier}), 같은 속도 제한({@link RoleClaimRateLimiter}).
 *
 * <h3>★ 속도 제한 버킷을 역할 승격과 공유하는 것은 의도다 — 축을 분리하지 말 것</h3>
 * <p>{@link RoleClaimRateLimiter} 는 단일 컴포넌트이고 전역 축 식별자가 엔드포인트와 무관한 고정
 * 문자열이라, 이 흐름과 역할 승격({@code RoleClaimService})은 <b>같은 전역(GLOBAL) 버킷을 실제로
 * 나눠 쓴다</b>. 두 진입점이 <b>같은 관리자 패스워드</b>를 검증하므로 버킷을 나누면 공격자가 진입점을
 * 번갈아 써서 <b>실효 한도를 두 배</b>로 늘릴 수 있다. 그래서 공유가 방어의 일부다.
 *
 * <p><b>대가 — 가용성이 서로 묶인다.</b> 기능적으로 무관한 두 작업이 한 전역 한도를 나눠 쓰므로,
 * 부트스트랩 호출이 몰리면 그 순간 관리자의 유효창 발급이 429 로 막힌다. <b>알고 받아들인 트레이드오프</b>이며, 이걸 없애려고 축을 분리하면
 * 위 실효 한도 2배 문제가 되살아난다.
 *
 * <p>⚠ <b>계정(ACCOUNT) 축은 이제 겹칠 수 있다</b>(구 서술 정정). 부트스트랩 창구가 역할 보유자를
 * 더 이상 거절하지 않으므로(ADR-055 · AC-127) <b>관리자도 두 진입점을 모두 호출할 수 있다</b> —
 * 다만 그 구간은 "아직 관리자가 0명" 일 때뿐이라 실제로는 부트스트랩 직후 한 사람에게만 잠깐
 * 성립한다. 전역 축 공유가 방어의 핵심이라는 점은 그대로다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>CWE-307</b> — 패스워드 비교 <b>전에</b> 속도 제한을 소비한다(429).</li>
 *   <li><b>CWE-203</b> — 상수시간 비교({@link AdminPasswordVerifier}).</li>
 *   <li><b>CWE-522/532</b> — 패스워드 평문은 응답·로그·예외 어디에도 남기지 않는다. 실패 로그에는
 *       actor 식별자와 사유 코드만 남는다.</li>
 *   <li><b>CWE-863</b> — <b>ADMIN 이 아니면</b> 패스워드를 보기도 전에 403 이다. 이 토큰은 인가를
 *       대체하지 않고 그 위에 더해진다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSessionService {

    private final AdminPasswordVerifier passwordVerifier;
    private final RoleClaimRateLimiter rateLimiter;
    private final AdminSessionTokenService tokenService;

    public AdminSessionResponse open(AdminSessionRequest request, TokenClaims actor) {
        if (actor == null || actor.sub() == null || actor.sub().isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        // 인가를 먼저 본다 — 권한 없는 사람의 시도가 정상 사용자의 속도 제한 쿼터를 갉아먹지 않게.
        //
        // ★★이 비교는 <역할 계층을 타지 않는다>. `hasRole` 이나 매처와 달리 enum 동등 비교라
        //   ROLE_ADMIN > ROLE_REVIEWER 계층이 적용되지 않는다 — 그래서 여기 REVIEWER 를 남겨 두면
        //   컨트롤러의 @PreAuthorize("hasRole('ADMIN')") 를 통과한 관리자가 <서비스에서> 403 을
        //   받는다(구현 당시 실제로 그렇게 났다). 컨트롤러 게이트를 바꿀 때 이 줄을 함께 보라.
        // 유효창 발급은 관리 권한 경계 자체라 관리자 전용이다(AC-072 · ROLE-004 · ADR-055).
        if (actor.role() != Role.ADMIN) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
        // CWE-307 — 패스워드 검증 전에 소비한다.
        rateLimiter.consumeOrReject(actor.sub());

        if (!passwordVerifier.matches(request == null ? null : request.adminPassword())) {
            // 패스워드 값은 남기지 않는다. 실패했다는 사실과 누구인지만.
            log.warn("[AdminSession] denied actor={} reason=invalid_password",
                    LogSanitizer.sanitize(actor.sub()));
            throw new CustomException(ErrorCode.UNAUTHORIZED,
                    "관리자 패스워드가 일치하지 않습니다.");
        }

        AdminSessionTokenService.Issued issued = tokenService.issue(actor.sub(), Instant.now());
        log.info("[AdminSession] opened actor={} ttlMinutes={}",
                LogSanitizer.sanitize(actor.sub()), tokenService.ttl().toMinutes());
        return new AdminSessionResponse(issued.token(), issued.expiresAt());
    }
}
