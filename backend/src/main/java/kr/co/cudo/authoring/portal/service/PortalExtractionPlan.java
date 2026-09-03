package kr.co.cudo.authoring.portal.service;

import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;

import java.util.List;

/**
 * 포털 프레임 추출이 <b>어디를 뽑을지</b>. 마킹이 정한 지점 목록과 그 자산 스냅샷.
 *
 * <p>순서 반전(2026-09-02) 이전에는 러너가 설정된 고정 간격으로 지점을 스스로 계산했다. 이제
 * 지점은 <b>사용자가 저장한 마킹</b>이 정하며 러너는 그것을 그대로 뽑는다 — 추출 장수 상한 반영도
 * 저장 시점에 이미 끝나 있다.
 *
 * @param asset        추출 대상 자산(후처리 중 상태여야 한다)
 * @param frameNumbers 뽑을 프레임 번호 — 오름차순·중복 없음
 * @design API-240
 */
public record PortalExtractionPlan(PortalUploadAsset asset, List<Integer> frameNumbers) {
}
