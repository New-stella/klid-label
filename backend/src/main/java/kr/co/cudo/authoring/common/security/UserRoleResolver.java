package kr.co.cudo.authoring.common.security;

import kr.co.cudo.authoring.common.config.CacheConfig;
import kr.co.cudo.authoring.user.entity.LsUserRole;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 저작도구 인가 역할 해석기 (역할 분리 리팩토링 Phase 3).
 *
 * <p>실제 관제 JWT 의 {@code role} 클레임은 관제 역할(LEARN_MANAGER 등)이라 저작도구 역할
 * (REVIEWER/WORKER/PORTAL_USER)과 무관하다. 따라서 INTERNAL 채널 토큰의 인가 역할은
 * 토큰 {@code sub}(=userNo)로 {@link LsUserRoleRepository#findByUserNo(Long)} 를 직접 조회해
 * 결정한다. JWT 서명/exp/issuer 검증은 {@link JwtAuthenticationFilter} 가 그대로 수행하며, 본
 * 해석기는 "검증 통과 후 권한을 어디서 가져오는가"만 담당한다.
 *
 * <p><b>Fail-closed 원칙</b> — 모호하면 무권한(null)을 반환한다(절대 기본 역할 fail-open 금지).
 * <ul>
 *   <li>userNo == null → null (filter 가 비숫자/누락 sub 를 null 로 선처리).</li>
 *   <li>LS 매핑 없음 → null (미배정 = 무권한).</li>
 *   <li>DB 조회 예외({@link DataAccessException}) → null (DB 장애 시 무권한, 권한 상승 방지).</li>
 *   <li>ROLE_CD 가 {@link Role} enum 에 없음 → null (미상 역할 무권한).</li>
 * </ul>
 *
 * <p><b>캐시</b> — {@code @Cacheable(userRole, key=userNo)}. 키는 userNo(Long)이며
 * {@link #evict(Long)} 의 {@code @CacheEvict} 키와 동일해야 무효화가 적중한다. null 결과는
 * {@code unless="#result==null"} 로 캐시하지 않아(미배정/조회실패) 역할 부여 직후 또는 일시적
 * DB 장애 회복 시 다음 요청이 곧바로 최신 값을 본다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserRoleResolver {

    private final LsUserRoleRepository lsUserRoleRepository;
    private final UserRepository userRepository;

    /**
     * <b>관제 인계 토큰의 {@code userId} 클레임으로 사용자번호를 해석한다 — 단건 매칭일 때만</b>
     * (@design ADR-063 · UC-041).
     *
     * <p>2단 조인의 앞단이다: {@code userId → LS_ACNT_USER.USER_ID 조회 → userNo}. 뒤이어
     * 호출자가 {@link #resolve(Long)} 로 역할을 얻는다. 표준 필드 {@code sub} 가 아니라 관제가
     * 사용자 ID 로 의도해 넣은 {@code userId} 클레임을 조회 키로 쓴다.
     *
     * <p><b>Fail-closed</b> — 다중/0건 매칭이면 null(추측 매칭 금지, CWE-639). {@code null}/공백
     * {@code userId} 도 null. DB 장애({@link DataAccessException})도 null 로 닫는다.
     *
     * <p>캐시하지 않는다 — 관제 인계 경로는 드물고, {@code userId→userNo} 매핑은 운영자가 사용자
     * 마스터를 바꾸면 즉시 반영돼야 한다. 로그에 {@code userId} 를 남기지 않는다(PII/개행 주입 표면
     * 차단, CWE-359/117).
     */
    public Long resolveUserNoByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return userRepository.findUserNoByUserId(userId).orElse(null);
        } catch (DataAccessException e) {
            // fail-closed — DB 장애 시 무권한. 권한 상승(fail-open) 절대 금지.
            log.warn("[Auth] userId lookup failed (fail-closed, deny)");
            return null;
        }
    }

    /**
     * userNo 로 저작도구 인가 역할을 해석한다. 무권한이면 null.
     * 로그에는 토큰/PII 를 출력하지 않고 userNo(Long)·역할 코드만 남긴다.
     */
    @Cacheable(cacheNames = CacheConfig.CACHE_USER_ROLE, key = "#userNo", unless = "#result == null")
    public Role resolve(Long userNo) {
        if (userNo == null) {
            return null;
        }
        try {
            return lsUserRoleRepository.findByUserNo(userNo)
                    .map(LsUserRole::getRoleCd)
                    .map(roleCd -> toRole(userNo, roleCd))
                    .orElse(null);
        } catch (DataAccessException e) {
            // fail-closed — DB 장애 시 무권한. 권한 상승(fail-open) 절대 금지.
            log.warn("[Auth] role lookup failed userNo={} (fail-closed, deny)", userNo);
            return null;
        }
    }

    /**
     * 역할 변경/부여 후 캐시 무효화. 호출자는 트랜잭션 AFTER_COMMIT 에서 호출해야 한다
     * (커밋 전 evict 는 동시 요청이 old value 를 재캐싱할 수 있음).
     */
    @CacheEvict(cacheNames = CacheConfig.CACHE_USER_ROLE, key = "#userNo")
    public void evict(Long userNo) {
        // 캐시 무효화 부수효과만 — 본문 없음.
    }

    /** ROLE_CD → {@link Role}. enum 에 없으면 fail-closed 무권한(null). */
    private Role toRole(Long userNo, String roleCd) {
        try {
            return Role.valueOf(roleCd);
        } catch (IllegalArgumentException e) {
            log.warn("[Auth] unknown role code userNo={} (fail-closed, deny)", userNo);
            return null;
        }
    }
}
