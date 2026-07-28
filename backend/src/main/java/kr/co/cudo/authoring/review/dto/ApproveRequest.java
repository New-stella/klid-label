package kr.co.cudo.authoring.review.dto;

/**
 * 검수 승인 요청 — <b>선택 바디</b>(미첨부 시 기존과 동일하게 동작).
 *
 * <h3>noLabelConfirmed — negative sample 승인 확인 (D-ISSUE-04 / DEV_FIX H6)</h3>
 * 라벨이 1건도 없는 영상의 승인은 기본적으로 409 로 차단한다(빈 스냅샷·빈 export·데이터마트 NULL 행 방지).
 * 그런데 <b>객체가 실제로 없는 정상 영상(negative sample)</b> 도 존재하며(실측 {@code raw_data_id}=4·7·9·10·12),
 * 게이트만 있으면 검수자는 반려밖에 못 해 <b>더미 라벨을 넣도록 유도</b>되어 데이터가 오염된다.
 *
 * <p>그래서 검수자가 "이 영상에는 라벨이 없음"을 <b>명시적으로 확인</b>한 요청({@code noLabelConfirmed=true})
 * 만 통과시킨다.
 *
 * <h3>플래그가 무의미해지지 않게 하는 장치</h3>
 * <ul>
 *   <li><b>기본값이 켜지지 않는다</b>: 필드는 {@code Boolean} 이며 미첨부(null)는 곧 미확인(false)이다.
 *       바디 자체를 안 보내도 동작하는 기존 경로가 그대로 유지된다.</li>
 *   <li><b>습관적으로 항상 보낼 수 없다</b>: 라벨이 <b>있는</b> 영상에 {@code true} 를 보내면 400 으로
 *       거부한다({@code ReviewService.approve}). "항상 true 를 붙이는" 클라이언트는 정상 승인부터
 *       깨지므로, 플래그가 게이트를 상시 무력화하는 형태로 굳지 않는다.</li>
 *   <li><b>감사 가능</b>: 이 확인으로 승인되면 기존 통합 이벤트 로그({@code LS_TASK_EVENT_LOG})의
 *       APPROVE 이벤트에 사유를 남겨 <b>누가 언제</b> 라벨 없음 확인으로 승인했는지 추적된다(신규 테이블 없음).</li>
 * </ul>
 *
 * @param noLabelConfirmed 라벨 0건 영상임을 검수자가 확인했는가. null/false = 미확인(기본).
 */
public record ApproveRequest(
        Boolean noLabelConfirmed
) {

    /** 미첨부(null) 를 false 로 정규화 — 확인 플래그의 기본값은 항상 "미확인". */
    public boolean confirmedNoLabel() {
        return Boolean.TRUE.equals(noLabelConfirmed);
    }
}
