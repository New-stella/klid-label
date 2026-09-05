package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.common.security.TokenClaims;

/**
 * 마킹 가드의 <b>두 물음</b> — 「이 사람이 이 영상을 마킹할 수 있는가」와 「지금 마킹할 수 있는
 * 단계인가」. 답은 <b>채널별 상태 원천</b>에서 얻는다.
 *
 * <table>
 *   <caption>채널별 판정 원천</caption>
 *   <tr><th>채널</th><th>접근</th><th>단계</th></tr>
 *   <tr><td>관제</td><td>배정 보유(검수자 이상은 전체)</td><td>배치 파이프라인 단계 표식</td></tr>
 *   <tr><td>포털</td><td><b>본인 자산</b></td><td><b>포털 업로드 상태</b>(메타 원장)</td></tr>
 * </table>
 *
 * <p>★ <b>예외를 붙이지 않는다.</b> 관제 가드에 「포털이면 건너뛴다」를 더하는 안은 기각됐다 —
 * 예외가 처음부터 셋(비식별 완료·배치 단계 표식·관제 이벤트 유형)이라 조건이 늘 때마다 다시 붙는다.
 * 대신 두 물음을 판정기로 세우고 채널이 각자 답한다. <b>마킹 로직 자체는 한 벌</b>로 남는다.
 *
 * <p>★ <b>접근은 영상 존재 확인보다 먼저 평가된다</b> — 두 채널 모두 접근 권한 없는 요청자가
 * 「그 영상이 있는지 없는지」를 상태코드로 알아내지 못하게 한다.
 *
 * @design ADR-058
 * @design API-240
 */
public interface MarkingChannelGuard {

    /**
     * ① 접근 — 이 사람이 이 영상을 마킹할 수 있는가. 위반이면 채널 규약대로 예외를 던진다.
     */
    void requireAccess(Long rawSn, TokenClaims actor);

    /**
     * ② 단계 — 지금 마킹할 수 있는 단계인가. 통과하면 응답에 실을 조달값을 돌려준다.
     */
    MarkingTarget requireMarkable(Long rawSn, TokenClaims actor);
}
