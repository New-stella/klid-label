package kr.co.cudo.authoring.common.client.dto;

/**
 * 외부 VLM 서비스(시계열 메타 분석) 위탁 요청 DTO — Phase 1 신설, Phase 3 마킹 필드 확장.
 *
 * <p>ccarch {@code if-vlm-timeseries-spi} (Outbound 비동기 시계열 분석 위탁) 정의에 따라
 * 영상 1건의 시계열 메타 추출을 외부 시스템에 위탁할 때 사용하는 요청 페이로드다.
 *
 * <p>실제 외부 서비스 계약(URL/스키마/인증)이 미확정이므로 본 DTO는 최소 필드만 정의한다.
 * 외부 계약 확정 시 필드 추가는 가능하나 기존 필드 제거/이름 변경은 금지(backward-compat).
 *
 * @param rawSn          저작도구 내 영상 식별자 (LS_DATA_RAW.RAW_SN). 결과 매핑에 사용.
 * @param videoUri       외부 시스템이 접근 가능한 영상 위치 (HTTPS / S3 / NFS 경로 등).
 *                       외부 시스템이 본 URI 로 영상을 fetch 한다.
 * @param idempotencyKey 멱등 키 — 외부 시스템 중복 인계 방지. 본 도구가 발급해 헤더에도 동일 값 사용.
 * @param callbackUrl    결과 수신용 webhook URL — Phase 2 결과 수신 콜백 컨트롤러 주소.
 * @param eventName      Phase 3 신규 — 마킹 이벤트명 (null 가능 — 마킹 없으면 null).
 * @param marks          Phase 3 신규 — 마킹 JSON [{frameIndex, timestamp}] (null 가능 — 마킹 없으면 null).
 */
public record VlmTimeseriesRequest(
        Long rawSn,
        String videoUri,
        String idempotencyKey,
        String callbackUrl,
        String eventName,
        String marks
) {
    /**
     * 기존 4파라미터 호환 생성자 — eventName, marks 가 null 인 요청 생성.
     */
    public VlmTimeseriesRequest(Long rawSn, String videoUri, String idempotencyKey, String callbackUrl) {
        this(rawSn, videoUri, idempotencyKey, callbackUrl, null, null);
    }
}
