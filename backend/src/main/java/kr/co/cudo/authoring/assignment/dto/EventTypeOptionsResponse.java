package kr.co.cudo.authoring.assignment.dto;

import java.util.List;

/**
 * 이벤트유형 셀렉트 옵션 응답 — REVIEWER 작업목록({@code GET /v1/tasks/board/event-types})과
 * WORKER 배정목록({@code GET /v1/assignments/event-types}) <b>공용</b>.
 *
 * <p>두 경로가 같은 레코드를 쓰는 이유는 FE 가 같은 셀렉트 컴포넌트로 역할에 따라 엔드포인트만 바꿔
 * 호출하기 때문이다 — 형태가 갈라지면 화면에 분기가 하나 더 생긴다. 적용 필터 축은 경로마다 다르지만
 * (board=배치 상태 / assignments=인가+검색어+워크플로 상태) <b>응답 계약은 동일</b>하다.
 *
 * <p><b>왜 {@code List<String>} 이 아니라 래퍼인가</b> — 옵션 개수에는 상한이 있고 초과 시 잘라서
 * 반환한다(무제한 응답 차단, OWASP API4). 벌거벗은 배열로 반환하면 <b>절단이 응답에 드러나지 않아</b>
 * 소비자는 "그 이벤트유형 영상이 없다"고 오인한다 — 목록 필터({@code eventTypeCd})는 잘린 코드로도
 * 정상 동작하므로 "데이터는 있는데 UI 로 도달할 수 없는" 조용한 손실이 된다. {@code truncated} 를
 * 함께 내려 화면이 "옵션이 너무 많아 일부만 표시됩니다" 같은 안내나 검색형 입력으로 대체할 수 있게 한다.
 *
 * @param items     이벤트유형 코드 목록 — 중복 없이 오름차순, 앞뒤 공백은 제거된 값
 * @param truncated 상한 초과로 잘려 반환됐는지 여부 (true 면 {@code items} 는 전체가 아니다)
 */
public record EventTypeOptionsResponse(
        List<String> items,
        boolean truncated
) {

    /** 전량 반환 (절단 없음). */
    public static EventTypeOptionsResponse of(List<String> items) {
        return new EventTypeOptionsResponse(List.copyOf(items), false);
    }

    /** 상한 초과로 잘라 낸 결과. */
    public static EventTypeOptionsResponse truncated(List<String> items) {
        return new EventTypeOptionsResponse(List.copyOf(items), true);
    }
}
