package kr.co.cudo.authoring.auth.service;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 권한 자가부여(role-claim) 시도 횟수 공유 저장소 (A-ISSUE-18).
 *
 * <p>2노드 Active-Active 배포에서 계정 축·전역 축 카운터가 노드 간 공유되어야 시스템 전체 기준의
 * 무차별 대입 억제(CWE-307)가 성립한다. JVM-local 카운터만으로는 임계가 노드 수만큼 곱해진다.
 *
 * <p><b>장애 정책</b>: 구현체는 저장소 장애 시 예외를 던지지 않고 {@link #UNAVAILABLE} 을 반환한다.
 * 호출자({@link RoleClaimRateLimiter})는 그 경우에도 <b>로컬 카운터를 최종 방어선으로 유지</b>하므로
 * 저장소 장애가 곧 rate limit 전면 해제가 되지 않는다(완전 fail-open 금지).
 */
public interface RoleClaimAttemptStore {

    /** 공유 카운터를 읽을 수 없음(저장소 장애) — 로컬 카운터로 폴백하라는 신호. */
    int UNAVAILABLE = -1;

    /**
     * 시도 1회를 기록하고 해당 윈도우의 누적 횟수를 반환한다.
     *
     * @param seCd        시도 구분 코드 (ACCOUNT / GLOBAL)
     * @param idntfr      시도 식별자 (계정 축=요청자 sub, 전역 축=고정값)
     * @param windowStart 집계 윈도우 시작 시각(분 단위 버킷 — 노드 공통 키)
     * @param ttl         보존 기간 (만료 행 정리 기준)
     * @return 누적 시도 횟수. 저장소 장애 시 {@link #UNAVAILABLE}
     */
    int recordAttempt(String seCd, String idntfr, LocalDateTime windowStart, Duration ttl);
}
