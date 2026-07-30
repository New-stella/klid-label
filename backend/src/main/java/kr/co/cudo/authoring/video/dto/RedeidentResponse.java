package kr.co.cudo.authoring.video.dto;

/**
 * 검수완료 영상 재비식별(Approved Re-deidentification) 요청 수락 응답 (Phase 3 / UC018).
 *
 * <p>재비식별은 KPST 위탁 후 폴링 잡이 비동기로 완료를 이어받으므로, 요청 시점에는 수락(ACCEPTED)
 * 사실과 위탁 추적 키({@code procLogSn})만 반환한다. 완료(프레임 attach + DE_IDNTF_YN='Y')는 폴링
 * 완료 시점에 별도로 처리된다.
 *
 * <h3>{@code kpstPrjId} 필드 제거 (M4)</h3>
 * <p>Phase C-2 논블로킹 제출로 <b>응답 시점에는 프로젝트 ID 가 존재할 수 없게</b> 됐다(ACK 를 기다리지
 * 않으므로 항상 null). 값이 절대 채워지지 않는 필드를 응답 계약에 남겨두면 "언젠가는 온다"는 오해를
 * 만들고, 소비자가 null 분기를 하게 만든다. 프론트엔드 사용처가 0건임을 확인하고(전역 grep) 필드를
 * 제거했다. 위탁 추적의 안정 식별자는 {@code procLogSn} 이며 항상 채워진다 — KPST 프로젝트 ID 는
 * ACK 수신 시 원장({@code LS_DEIDENT_PROC_LOG.DE_IDNTF_PJT_ID})에 기록되므로, 필요하면 procLogSn 으로
 * 원장을 조회한다.
 *
 * @param rawSn      대상 영상 ID
 * @param procLogSn  생성된 비식별 처리 로그(REQ_KIND=REDEIDENT) ID — 위탁 추적 키
 * @param status     수락 상태 — 항상 {@code ACCEPTED}
 */
public record RedeidentResponse(Long rawSn, Long procLogSn, String status) {

    public static final String STATUS_ACCEPTED = "ACCEPTED";

    public static RedeidentResponse accepted(Long rawSn, Long procLogSn) {
        return new RedeidentResponse(rawSn, procLogSn, STATUS_ACCEPTED);
    }
}
