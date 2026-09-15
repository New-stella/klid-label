package kr.co.cudo.authoring.portal.dto;

import kr.co.cudo.authoring.portal.service.PortalMaterialsFailureReason;
import kr.co.cudo.authoring.portal.service.PortalMaterialsSummary;

/**
 * 조달 착수·상태 조회의 응답.
 *
 * <h3>★ 내부 경로를 싣지 않는다</h3>
 * <p>포털 응답의 {@code localPath} 와 {@code repoRootDir} 는 <b>공유 저장소 절대경로</b>이고 우리
 * 작업영역 경로도 마찬가지다. 어느 것도 이 응답에 담지 않는다(CWE-209). 담는 것은 <b>상태 · 사유
 * 분류 · 개수와 크기</b>뿐이다.
 *
 * @param datasetId     데이터셋 숫자 식별자(요청 그대로)
 * @param state         진행 상태
 * @param failureReason {@link PortalMaterialsState#FAILED} 일 때만 non-null
 * @param materials     {@link PortalMaterialsState#READY} 일 때의 해제본 요약.
 *                      요약 파일을 읽지 못하면 {@code null} 일 수 있다 — <b>준비 완료 판정은 그대로다</b>
 * @design INT-014
 */
public record PortalMaterialsStatusResponse(
        long datasetId,
        PortalMaterialsState state,
        PortalMaterialsFailureReason failureReason,
        PortalMaterialsSummary materials) {

    public static PortalMaterialsStatusResponse of(long datasetId, PortalMaterialsState state) {
        return new PortalMaterialsStatusResponse(datasetId, state, null, null);
    }

    public static PortalMaterialsStatusResponse ready(long datasetId, PortalMaterialsSummary summary) {
        return new PortalMaterialsStatusResponse(datasetId, PortalMaterialsState.READY, null, summary);
    }

    public static PortalMaterialsStatusResponse failed(long datasetId,
                                                       PortalMaterialsFailureReason reason) {
        return new PortalMaterialsStatusResponse(datasetId, PortalMaterialsState.FAILED, reason, null);
    }
}
