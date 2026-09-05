package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.dto.MarkingResponse;

import java.util.List;

/**
 * 마킹 저장 결과 — 채널 공통 응답({@link MarkingResponse})에 <b>절단 정보</b>를 곁들인 것.
 *
 * <p>관제 응답 계약에는 절단 축이 없으므로 그 채널의 컨트롤러는 {@link #response()} 만 쓴다(무변경).
 * 포털 응답은 잘렸는지·잘리기 전 지점 수를 함께 내려야 해서 이 두 값을 소비한다.
 *
 * @design API-240
 */
public record MarkingOutcome(MarkingResponse response, List<MarkItem> marks, int requestedMarkCount) {

    /** 상한에 걸려 지점이 잘렸는가. */
    public boolean truncated() {
        return requestedMarkCount > marks.size();
    }

    /** 배치 트리거 결과를 응답에 덧입힌 사본. */
    public MarkingOutcome withBatchOutcome(Boolean triggered, String skipReason) {
        return new MarkingOutcome(response.withBatchOutcome(triggered, skipReason), marks, requestedMarkCount);
    }
}
