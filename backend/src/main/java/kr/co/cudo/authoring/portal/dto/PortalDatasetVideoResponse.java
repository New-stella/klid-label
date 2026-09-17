package kr.co.cudo.authoring.portal.dto;

import java.time.LocalDateTime;

/**
 * 데이터셋 영상 한 건 — 라벨링으로 들어갈 대상. @design API-253
 *
 * <p>목록은 데이터셋 단위로 공유되지만 {@code entrySrcSn}·{@code lastSavedAt} 은 <b>요청 사용자 기준</b>이다.
 *
 * @param rawSn       원장에 등록된 영상 식별자
 * @param videoName   표시 이름 — 원본 파일명, 없으면 클립 식별자
 * @param frameCount  원장에 등록된 이 영상의 프레임 수
 * @param labelCount  배포본에서 원본 라벨로 등록된 라벨 건수 — <b>사용자가 저장한 라벨 수가 아니다</b>
 * @param entrySrcSn  라벨링 화면을 열 프레임. 요청 사용자가 저장한 라벨의 마지막 프레임 → 첫 프레임 →
 *                    프레임 0건이면 {@code null}
 * @param lastSavedAt 요청 사용자의 세 저작물(저장 라벨·메타 오버레이·이벤트 어노테이션 오버레이) 중 가장 늦은
 *                    저장 시각. 저장한 적이 없으면 {@code null}
 * @design ADR-068
 */
public record PortalDatasetVideoResponse(
        long rawSn,
        String videoName,
        int frameCount,
        int labelCount,
        Long entrySrcSn,
        LocalDateTime lastSavedAt) {
}
