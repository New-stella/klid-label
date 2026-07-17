package kr.co.cudo.authoring.portal.event;

/**
 * 포털 영상 업로드 완료 이벤트 — 프레임 추출 트리거 전용.
 *
 * <p><b>관제 {@code VideoIngestedEvent} 와 완전 분리(시나리오 #18).</b> 본 이벤트는 포털 패키지에만
 * 존재하며 관제 비식별/마킹/오토라벨링 파이프라인(IngestDeidentifyBridge 등)에 <b>절대 연결되지
 * 않는다</b>. 포털 전용 프레임 추출({@code PortalFrameExtractBridge})만 이 이벤트를 소비한다.
 *
 * @param uldSn 완료된 포털 업로드 마스터(LS_PORTAL_ULD) 순번
 */
public record PortalVideoUploadedEvent(Long uldSn) {
}
