package kr.co.cudo.authoring.video.dto;

/**
 * 검수완료 영상 재비식별(Approved Re-deidentification) 요청 수락 응답 (Phase 3 / UC018).
 *
 * <p>재비식별은 KPST 위탁 후 폴링 잡이 비동기로 완료를 이어받으므로, 요청 시점에는 수락(ACCEPTED)
 * 사실과 위탁 식별자(procLogSn/kpstPrjId)만 반환한다. 완료(프레임 attach + DE_IDNTF_YN='Y')는 폴링
 * 완료 시점에 별도로 처리된다.
 *
 * @param rawSn      대상 영상 ID
 * @param procLogSn  생성된 비식별 처리 로그(REQ_KIND=REDEIDENT) ID
 * @param kpstPrjId  KPST 프로젝트 ID (위탁 식별자)
 * @param status     수락 상태 — 항상 {@code ACCEPTED}
 */
public record RedeidentResponse(Long rawSn, Long procLogSn, Long kpstPrjId, String status) {

    public static final String STATUS_ACCEPTED = "ACCEPTED";

    public static RedeidentResponse accepted(Long rawSn, Long procLogSn, Long kpstPrjId) {
        return new RedeidentResponse(rawSn, procLogSn, kpstPrjId, STATUS_ACCEPTED);
    }
}
