package kr.co.cudo.authoring.webhook.service;

/**
 * 추가 질문(describe-sub) 결과를 이벤트 어노테이션의 질의응답 축 초안으로 반영하는 협력자.
 *
 * <p>콜백 수신부는 <b>어느 창구의 결과인지 되짚어 넘기는 일</b>까지만 하고, 이벤트 어노테이션의
 * 구조·동결·재검수 시맨틱은 그 도메인이 소유한다. 수신부에 그 규칙을 복제하면 한쪽만 갱신돼
 * 조용히 어긋난다.
 *
 * <p><b>자동 채움의 경계</b>(구현이 반드시 지켜야 하는 계약):
 * <ul>
 *   <li>사람이 이미 고친 값과 검수 승인으로 동결된 값은 <b>덮지 않는다</b>.</li>
 *   <li>자동 채움 자체는 재검수를 발화시키지 않는다 — 사람이 고치는 것과 구분된다.</li>
 *   <li>같은 결과를 여러 번 받아도 결과가 같아야 한다(규격상 콜백은 중복 수신될 수 있다).</li>
 * </ul>
 */
public interface TimeseriesSubResultApplier {

    /**
     * 추가 질문 서술을 초안으로 반영한다.
     *
     * @param rawSn       대상 영상.
     * @param description 분석 결과 서술(자연어 평문 — 형식이 고정돼 있지 않으므로 파싱에 의존하지 않는다).
     * @return 실제로 초안이 채워졌으면 true, 이미 사람이 손댔거나 동결돼 건너뛰었으면 false.
     */
    boolean applySubDescription(Long rawSn, String description);
}
