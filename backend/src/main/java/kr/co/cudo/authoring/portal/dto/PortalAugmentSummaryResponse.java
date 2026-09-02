package kr.co.cudo.authoring.portal.dto;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 포털 증강 요청 현황 목록의 한 행.
 *
 * <h3>화면은 {@link #augSttsCd} 로 분기하지 않는다</h3>
 * <p>요청 상태의 개별 표시 값을 창구가 정하지 않으므로(값역이 열려 있다) 화면이 그 값 자체로 분기할
 * 수 없다. 그래서 <b>결과 도착 여부를 별도 값으로</b> 함께 싣는다({@link #resultReady}).
 *
 * @param augSn               증강 요청 식별자
 * @param uldSn               요청 대상이 된 업로드 영상 자산 식별자
 * @param orgnlFileNm         대상 영상의 원본 파일명(표시용). 보관값이 없으면 {@code null}
 * @param requestedAt         요청을 접수한 일시. 목록은 이 값의 내림차순이다
 * @param generationCondition 요청할 때 지정한 생성 조건 — 접수 시 보관한 값 그대로
 * @param augSttsCd           요청 상태
 * @param resultReady         결과 도착 여부
 * @param resultArrivedAt     결과가 도착한 일시. 대기·실패는 {@code null}
 * @design API-232
 */
public record PortalAugmentSummaryResponse(
        Long augSn,
        Long uldSn,
        String orgnlFileNm,
        LocalDateTime requestedAt,
        Map<String, Object> generationCondition,
        String augSttsCd,
        boolean resultReady,
        LocalDateTime resultArrivedAt
) {
}
