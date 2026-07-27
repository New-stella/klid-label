package kr.co.cudo.authoring.common.security.webhook;

import java.time.Duration;

/**
 * 웹훅 서명 nonce 저장소 — replay 방지(A-ISSUE-12, CWE-294).
 *
 * <p>기존 방어는 timestamp 윈도우(±5분)뿐이라 <b>유효 콜백을 캡처해 윈도우 내 무제한 재전송</b>이
 * 전부 통과했다. 서명 자체를 1회성으로 소비해 같은 서명의 재사용을 차단한다.
 *
 * <h3>저장소 요건</h3>
 * <ul>
 *   <li><b>노드 공유</b>: 배포 토폴로지가 2노드 Active-Active 라 JVM-local 캐시는 replay 가
 *       다른 노드로 가면 그대로 통과한다(무효).</li>
 *   <li><b>트랜잭션 밖 단문</b>: PostgreSQL 은 UNIQUE 위반이 트랜잭션 전체를 abort(25P02) 시켜
 *       같은 트랜잭션에서 재시도가 불가능하다. 그래서 구현은 예외 경로 자체가 없는
 *       {@code INSERT ... ON CONFLICT DO NOTHING} + {@code updateCount} 판정을 쓴다(S-07).</li>
 * </ul>
 */
public interface WebhookNonceStore {

    /**
     * 서명 nonce 를 1회성 소비한다.
     *
     * <p><b>호출 시점 고정</b>: 반드시 <b>서명 검증 성공 이후</b>에만 호출한다. 검증 전에 호출하면
     * 무인증 공격자가 테이블을 무한 팽창시키는 pre-auth write DoS 가 된다(S-08).
     *
     * @param signatureHash 서명·타임스탬프·<b>정규화</b> 경로를 묶은 SHA-256 hex (64자)
     * @param path          로그·운영 추적용 경로
     * @param ttl           보존 기간 — 만료 후 정리 대상
     * @return {@code true} = 최초 소비(정상 진행), {@code false} = 이미 소비된 서명(replay)
     * @throws WebhookGuardUnavailableException 저장소 장애 — fail-closed 로 요청을 거부해야 함
     */
    boolean consume(String signatureHash, String path, Duration ttl);

    /**
     * 소비 예약을 <b>되돌린다</b> — 하류 처리가 실패(5xx/예외)했을 때만 호출한다 (DEV_FIX M-1, CWE-754).
     *
     * <p>nonce 는 서명 검증 직후·비즈니스 처리 <b>전</b>에 소비된다. 그 상태에서 하류가 5xx 로 실패하면
     * 벤더의 <b>바이트 동일 재전송</b>이 409(중복 흡수)로 응답돼 <b>적용되지 않은 결과가 영구 유실</b>된다.
     * 실패 시 예약을 해제해 동일 서명 재전송이 다시 처리되도록 한다.
     *
     * <p>해제 실패는 요청 처리 실패로 승격하지 않는다(이미 오류 응답 중). 최악의 경우 원래 동작(409)으로
     * 되돌아갈 뿐이다.
     */
    void release(String signatureHash);
}
