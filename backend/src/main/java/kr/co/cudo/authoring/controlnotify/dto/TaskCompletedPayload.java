package kr.co.cudo.authoring.controlnotify.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 관제지원시스템 완료 통지({@code POST /api/data-set/v2/jobs/{job_id}/notify-completed}) 본문.
 *
 * <p><b>관제 계약(API-251 v17) 9필드 평면 구조</b>(required 8 + optional 1) + <b>선택 필드
 * {@code output_ver_no}</b> — 구 {@code {eventType, payload:{...}}} 2단 중첩은 폐기했다. 관제는 이
 * 통지로 1차 판단만 하고, 상세는 저작도구 조회 API({@code /v1/tasks/**})·데이터마트 뷰로 가져간다.
 * 정본은 {@code docs/관제-저작도구-데이터연동-규격서-20260805.md} §4-1 이다.
 *
 * <p>JSON 필드명은 계약대로 <b>snake_case</b> 이며 {@link JsonProperty} 로 고정한다(전역 naming
 * strategy 변경에 영향받지 않도록 필드 단위로 명시).
 *
 * <p>CWE-359: PII·토큰·원본(비-비식별) 파일 경로를 담지 않는다. 값은 모두 DB 실측 조회분이며
 * 상수 self-fill 을 금지한다(D-ISSUE-41).
 *
 * <h3>★ 필드 유무 규약 — 의도된 비대칭 (되돌리지 말 것)</h3>
 * <ul>
 *   <li><b>기존 required 필드</b>(9필드): 값이 {@code null} 이어도 <b>키를 남긴다</b>. 키를 빼면
 *       관제 검증에서 전량 {@code 422 VALIDATION_FAILED} 다.</li>
 *   <li><b>{@code output_ver_no}</b>(신설): 값이 없으면 <b>키를 생략</b>한다. 관제가 "키 없음 =
 *       산출물 변경 없음 → 재픽업 불요" 로 읽기 때문이다.</li>
 * </ul>
 * 따라서 {@link JsonInclude} 는 반드시 <b>필드 레벨</b>로만 건다 — 클래스 레벨에 걸면 required
 * 필드까지 생략되어 관제 통지가 전량 깨진다(회귀 가드:
 * {@code TaskPayloadStructureTest.requiredFieldsKeepExplicitNullKeysAfterOptionalFieldAdded}).
 *
 * @param jobId       작업 ID — {@code String.valueOf(LS_DATA_RAW.RAW_SN)}
 * @param eventTypeCd 영상 이벤트 유형 코드 — {@code LS_DATA_RAW.EVNT_TYPE_CD} (통지 종류가 아님)
 * @param evntClsCd   이벤트 분류 코드 — 관제 인입 {@code LS_DATA_INGEST.EVNT_CLSF_CD} (미송신 시 null).
 *                    <b>JSON 키가 우리 컬럼명({@code CLSF})이 아니라 {@code evnt_cls_cd} 인 것은 오타가
 *                    아니라 관제 계약</b>이다 — 키를 우리 컬럼명에 맞추면 관제가 422 로 거부한다.
 * @param evntCtgryCd 이벤트 카테고리 코드 — 관제 인입 {@code LS_DATA_INGEST.EVNT_CTGRY_CD} (미송신 시 null)
 * @param lclgvCd     지자체 코드 — {@code LS_DATA_RAW.LCLGV_CD}
 * @param lclgvNm     지자체명 — 관제 인입 {@code LS_DATA_INGEST.LCLGV_NM} (관제 미송신 시 null).
 *                    <b>계약 필드명·타입은 불변</b>이며 조달처만 바뀌었다(V167 — 구 관제 공유
 *                    지자체 마스터는 실DB 0행이라 이 값은 이전에도 사실상 항상 null 이었다).
 * @param durationSec 영상 길이(초) — {@code LS_DATA_RAW.VDO_LEN_SEC} (미상 시 null)
 * @param imageCount  프레임(이미지) 수 — {@code COUNT(LS_DATA_SRC WHERE RAW_SN=?)} 실측
 * @param genAiYn     생성형AI여부({@code Y}/{@code N}) — 라이브 {@code LS_DATA_RAW.SRC_TYPE} 에서 도출.
 *                    판정 단일 원천은 {@code LsDataRaw.genAiYnOf} 이며 required 라 null 을 싣지 않는다.
 * @param outputVerNo 이 통지가 대응하는 <b>산출 폴더 버전 번호</b>({@code v{n}} 의 {@code n}) —
 *                    {@code LS_DATASET_EXPORT.OUTPUT_VER_NO}. 관제가 "어느 통지가 어느 산출 버전에
 *                    대응하는지 알 수 없다"고 요청해 추가한 <b>선택 필드</b>다(2026-08-12 회신 수용).
 *                    <b>null 이면 키 자체를 내보내지 않으며</b> 관제는 그것을 "산출물 변경 없음 —
 *                    재픽업 불요"로 처리한다. 조달 기준은 데이터마트 뷰 {@code V_COMPLETED_VIDEO} 가
 *                    {@code OUTPUT_PATH_NM} 을 고르는 기준과 같아야 한다
 *                    ({@link kr.co.cudo.authoring.controlnotify.service.ControlNotifyPayloadFactory} 참조).
 * @design INT-007
 */
public record TaskCompletedPayload(
        @JsonProperty("job_id") String jobId,
        @JsonProperty("event_type_cd") String eventTypeCd,
        @JsonProperty("evnt_cls_cd") String evntClsCd,
        @JsonProperty("evnt_ctgry_cd") String evntCtgryCd,
        @JsonProperty("lclgv_cd") String lclgvCd,
        @JsonProperty("lclgv_nm") String lclgvNm,
        @JsonProperty("duration_sec") Integer durationSec,
        @JsonProperty("image_count") int imageCount,
        @JsonProperty("gen_ai_yn") String genAiYn,
        @JsonProperty("output_ver_no") @JsonInclude(JsonInclude.Include.NON_NULL) Integer outputVerNo
) {
}
