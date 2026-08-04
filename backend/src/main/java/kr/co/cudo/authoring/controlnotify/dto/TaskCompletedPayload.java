package kr.co.cudo.authoring.controlnotify.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 관제지원시스템 완료 통지({@code POST /api/data-set/v2/jobs/{job_id}/notify-completed}) 본문.
 *
 * <p><b>관제 계약(API-251) 6필드 평면 구조</b> — 구 {@code {eventType, payload:{...}}} 2단 중첩은 폐기했다.
 * 관제는 이 통지로 1차 판단만 하고, 상세는 저작도구 조회 API({@code /v1/tasks/**})·데이터마트 뷰로 가져간다.
 *
 * <p>JSON 필드명은 계약대로 <b>snake_case</b> 이며 {@link JsonProperty} 로 고정한다(전역 naming
 * strategy 변경에 영향받지 않도록 필드 단위로 명시).
 *
 * <p>CWE-359: PII·토큰·원본(비-비식별) 파일 경로를 담지 않는다. 값은 모두 DB 실측 조회분이며
 * 상수 self-fill 을 금지한다(D-ISSUE-41).
 *
 * @param jobId       작업 ID — {@code String.valueOf(LS_DATA_RAW.RAW_SN)}
 * @param eventTypeCd 영상 이벤트 유형 코드 — {@code LS_DATA_RAW.EVNT_TYPE_CD} (통지 종류가 아님)
 * @param lclgvCd     지자체 코드 — {@code LS_DATA_RAW.LCLGV_CD}
 * @param lclgvNm     지자체명 — 관제 인입 {@code LS_DATA_INGEST.RGN_NM} (관제 미송신 시 null).
 *                    <b>계약 필드명·타입은 불변</b>이며 조달처만 바뀌었다(V167 — 구 관제 공유
 *                    지자체 마스터는 실DB 0행이라 이 값은 이전에도 사실상 항상 null 이었다).
 * @param durationSec 영상 길이(초) — {@code LS_DATA_RAW.VDO_LEN_SEC} (미상 시 null)
 * @param imageCount  프레임(이미지) 수 — {@code COUNT(LS_DATA_SRC WHERE RAW_SN=?)} 실측
 */
public record TaskCompletedPayload(
        @JsonProperty("job_id") String jobId,
        @JsonProperty("event_type_cd") String eventTypeCd,
        @JsonProperty("lclgv_cd") String lclgvCd,
        @JsonProperty("lclgv_nm") String lclgvNm,
        @JsonProperty("duration_sec") Integer durationSec,
        @JsonProperty("image_count") int imageCount
) {
}
