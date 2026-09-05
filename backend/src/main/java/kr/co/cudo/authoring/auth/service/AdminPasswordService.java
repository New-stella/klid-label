package kr.co.cudo.authoring.auth.service;

import kr.co.cudo.authoring.auth.dto.AdminPasswordChangeRequest;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 관리자 공유 패스워드 교체. [@design API-223] [@design AC-121] [@design AC-123]
 *
 * <h3>요건이 셋이고 <b>함께</b> 성립해야 한다</h3>
 * <ol>
 *   <li>검수자 권한 — 컨트롤러의 {@code @PreAuthorize} 와 여기의 이중 검증.</li>
 *   <li>유효한 관리자 유효창 — 컨트롤러에 붙은
 *       {@link kr.co.cudo.authoring.common.security.adminsession.RequiresAdminSession} 표식을 보고
 *       인터셉터가 강제한다.</li>
 *   <li><b>현재 패스워드 재확인</b> — 이 서비스가 한다. 유효창만으로 바꿀 수 있으면 <b>잠깐 열린 창을
 *       가로챈 사람이 자격 자체를 갈아 치워 정당한 운영자를 잠글 수 있다</b>. 이 한 수를 막는 것이
 *       세 번째 요건의 전부다.</li>
 * </ol>
 *
 * <h3>판정 순서 — 인가 → 유효창 → 속도 제한 → 현재 패스워드</h3>
 * <p>속도 제한을 패스워드 비교보다 <b>앞</b>에 두는 것이 핵심이다(CWE-307). 반대로 두면 이 창구가
 * 관리자 패스워드에 대한 무제한 대입 창이 된다.
 *
 * <p>속도 제한 축은 유효창 발급과 <b>공유</b>한다({@link RoleClaimRateLimiter}). 축을 나누면 공격자가
 * 두 창구를 번갈아 써서 실효 한도를 두 배로 늘린다.
 *
 * <h3>교체하면 자기 유효창까지 끊긴다 — 결함이 아니라 요구다</h3>
 * <p>무효화를 별도 장부나 세대 값이 아니라 <b>유효창 서명이 현재 자격에 의존</b>하게 만들어 이루므로
 * (자세히는 {@link AdminSessionTokenService}), 예외를 두어 자기 것만 살려 두는 갈래가 없다. 이어서
 * 관리 기능을 쓰려면 <b>새 패스워드로 다시</b> 열어야 한다.
 *
 * <h3>새 값이 현재 값과 같으면 거부한다</h3>
 * <p>바뀌지도 않았는데 열려 있던 유효창만 전부 끊기는 일을 막기 위해서다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>CWE-256</b> — 평문은 저장되지 않는다. BCrypt 해시만 저장된다.</li>
 *   <li><b>CWE-203</b> — 두 비교 모두 상수시간({@link AdminPasswordVerifier}).</li>
 *   <li><b>CWE-209</b> — 현재 패스워드 불일치는 401 이며 <b>어느 쪽이 틀렸는지 이상은 알리지 않는다</b>.</li>
 *   <li><b>CWE-532</b> — 평문·해시는 응답·로그·예외 메시지 어디에도 남기지 않는다. 로그에는 행위자와
 *       사유 코드만 남는다 — <b>값을 남기지 않는 것과 행위를 남기지 않는 것은 다르다</b>. 공유 자격이라
 *       값만으로는 누가 썼는지 알 수 없으므로 "누가 언제 바꿨는가"는 오히려 반드시 남아야 한다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPasswordService {

    private final AdminPasswordVerifier passwordVerifier;
    private final RoleClaimRateLimiter rateLimiter;

    @Transactional
    public void change(AdminPasswordChangeRequest request, TokenClaims actor) {
        if (actor == null || actor.sub() == null || actor.sub().isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED);
        }
        // 인가를 먼저 본다 — 권한 없는 사람의 시도가 정상 사용자의 속도 제한 쿼터를 갉아먹지 않게.
        //
        // ★★이 비교는 <역할 계층을 타지 않는다>(enum 동등 비교). 컨트롤러의
        //   @PreAuthorize("hasRole('ADMIN')") 와 <같은 값>을 유지해야 하며, 한쪽만 바꾸면
        //   통과한 사용자가 다른 쪽에서 403 을 받는다.
        // 관리자 패스워드 교체는 관리 권한 경계 자체를 바꾸는 일이라 관리자 전용이다
        //   (AC-072 · ROLE-004 SCREEN-041 · ADR-055).
        if (actor.role() != Role.ADMIN) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
        if (request == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT);
        }
        // CWE-307 — 패스워드 검증 전에 소비한다.
        rateLimiter.consumeOrReject(actor.sub());

        if (!passwordVerifier.matches(request.currentPassword())) {
            // 패스워드 값은 남기지 않는다. 실패했다는 사실과 누구인지만.
            log.warn("[AdminPassword] denied actor={} reason=invalid_current_password",
                    LogSanitizer.sanitize(actor.sub()));
            throw new CustomException(ErrorCode.UNAUTHORIZED,
                    "관리자 패스워드가 일치하지 않습니다.");
        }
        if (passwordVerifier.matches(request.newPassword())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "새 관리자 패스워드가 현재 값과 같습니다.");
        }

        passwordVerifier.replace(request.newPassword(), actor.sub(), LocalDateTime.now());
        // 값도 해시도 남기지 않는다 — 누가 언제 바꿨는지만.
        log.info("[AdminPassword] changed actor={}", LogSanitizer.sanitize(actor.sub()));
    }
}
