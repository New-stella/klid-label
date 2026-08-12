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
 * <p>그 서비스는 <b>역할이 없는 사람 전용</b>이다({@code actor.role() != null} 이면 409). 이 흐름의
 * 사용자는 <b>이미 REVIEWER</b> 이므로 그대로 부르면 항상 409 가 난다. 게다가 그 API 는 성공 시
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
 * 역할 승격 호출이 몰리면(신규 사용자 온보딩 같은 <b>정상 사용</b>) 그 순간 REVIEWER 의 관리자 설정
 * 유효창 발급이 429 로 막힌다. <b>알고 받아들인 트레이드오프</b>이며, 이걸 없애려고 축을 분리하면
 * 위 실효 한도 2배 문제가 되살아난다.
 *
 * <p>⚠ <b>계정(ACCOUNT) 축은 실질 충돌이 없다</b> — 겹친다고 오해하지 말 것. 역할 승격은
 * <b>역할이 없는 계정 전용</b>이고(보유자는 409, 그 판정이 속도 제한 소비보다 <b>앞</b>이다) 이
 * 흐름은 <b>REVIEWER 전용</b>이다(비보유자는 403, 역시 소비보다 앞이다). 따라서 같은 계정이 두
 * 계정 카운터를 동시에 소모할 수 없고, 실제로 겹치는 것은 <b>전역 축 하나뿐</b>이다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>CWE-307</b> — 패스워드 비교 <b>전에</b> 속도 제한을 소비한다(429).</li>
 *   <li><b>CWE-203</b> — 상수시간 비교({@link AdminPasswordVerifier}).</li>
 *   <li><b>CWE-522/532</b> — 패스워드 평문은 응답·로그·예외 어디에도 남기지 않는다. 실패 로그에는
 *       actor 식별자와 사유 코드만 남는다.</li>
 *   <li><b>CWE-863</b> — REVIEWER 가 아니면 패스워드를 보기도 전에 403 이다. 이 토큰은 인가를
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
        if (actor.role() != Role.REVIEWER) {
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
