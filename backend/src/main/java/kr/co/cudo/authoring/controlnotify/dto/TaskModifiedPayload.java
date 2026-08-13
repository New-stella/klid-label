package kr.co.cudo.authoring.controlnotify.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 관제지원시스템 수정 통지({@code POST /api/data-set/v2/jobs/{job_id}/notify-updated}) 본문.
 *
 * <p><b>관제 계약(API-285)</b> — 변경된 <b>파일명 리스트</b>를 전달한다. 내부 식별자({@code SRC_SN})가
 * 아니라 산출 폴더의 실제 파일명({@code {FRM_NO 4자리 zero-pad}.jpg} / {@code ....json},
 * 예: {@code 0338.jpg})이라야 관제 워커가 그대로 픽업할 수 있다. 규칙 단일 지점은
 * {@link kr.co.cudo.authoring.dataset.export.ExportFileNaming} 이며 export writer 와 공유한다.
 *
 * <p>CWE-359: 라벨 좌표·메타 본문, PII, 토큰, 절대 경로를 담지 않는다(파일 <b>이름</b>만).
 *
 * <p><b>{@code data_info} 는 싣지 않는다</b> — 관제 명세에 키 스키마가 없다(규격서 §7-E, 회신 대기).
 * 추정 스키마로 필드를 만들면 관제가 422 로 거부하거나 잘못된 값을 적재한다.
 *
 * @param jobId        작업 ID — {@code String.valueOf(LS_DATA_RAW.RAW_SN)}
 * @param changedItems 변경 파일 목록 (이미지/JSON)
 * @param verExpln     이번 버전 설명 — 관제 {@code dataset_versions.ver_expln}(NOT NULL) 조달용.
 *                     관제 계약상 <b>optional</b> 이며 문구 판정의 단일 원천은
 *                     {@link kr.co.cudo.authoring.controlnotify.service.VersionExplanationPolicy} 다.
 *                     null 이면 <b>키 자체를 내보내지 않는다</b>({@code @JsonInclude(NON_NULL)}) —
 *                     이 필드 도입 <b>이전에</b> 폴백 큐에 적재된 JSON 을 재시도 Job 이 역직렬화하면
 *                     null 이 되는데, NOT NULL 컬럼에 명시적 null 을 밀어 넣는 것보다 "미전송"(관제가
 *                     스스로 채우는 기존 동작)이 안전하다.
 * @param outputVerNo  이 통지가 대응하는 <b>산출 폴더 버전 번호</b>({@code v{n}} 의 {@code n}) —
 *                     {@code LS_DATASET_EXPORT.OUTPUT_VER_NO}. 관제가 "어느 통지가 어느 산출 버전에
 *                     대응하는지 알 수 없다"고 요청해 추가한 <b>선택 필드</b>다(2026-08-12 회신 수용).
 *                     {@code ver_expln} 과 같은 규약으로 <b>null 이면 키 자체를 내보내지 않고</b>,
 *                     관제는 그것을 "산출물 변경 없음 — 재픽업 불요"로 처리한다. 값의 유무는
 *                     {@code changed_items} 와 <b>같은 축</b>(export 재생성 동반 여부)에서 갈린다.
 * @design INT-007
 */
public record TaskModifiedPayload(
        @JsonProperty("job_id") String jobId,
        @JsonProperty("changed_items") ChangedItems changedItems,
        @JsonProperty("ver_expln") @JsonInclude(JsonInclude.Include.NON_NULL) String verExpln,
        @JsonProperty("output_ver_no") @JsonInclude(JsonInclude.Include.NON_NULL) Integer outputVerNo
) {

    /**
     * 변경 파일 목록.
     *
     * <p>두 리스트는 항상 non-null 이다(빈 리스트 허용). 범위 규칙은 <b>"export 재생성을 동반했는가"</b>
     * 로 결정한다(A-2, {@code ControlNotifyService.sendModified} 참조):
     * <ul>
     *   <li>재생성 동반(재승인·event_annotation 지연 승인) — 전 프레임 이미지·JSON 을 싣는다.
     *       비우면 관제가 아무것도 재픽업하지 않아 보유본이 stale 로 고착된다.</li>
     *   <li>재생성 없음(촬영환경 메타 수정 등) — 디스크가 그대로이므로 <b>빈 리스트</b>가 정상이다.
     *       관제는 통지를 받고 {@code V_COMPLETED_META} 등 뷰로 메타를 재조회한다. 없는 파일을
     *       실어 보내면 관제가 404 를 맞는다.</li>
     * </ul>
     * 어느 경우든 <b>통지 자체는 발송된다</b>(D-ISSUE-43 유실 금지).
     *
     * @param images 변경된 프레임 이미지 파일명 — {@code {FRM_NO 4자리 zero-pad}.jpg} (예: {@code 0338.jpg})
     * @param jsons  변경된 프레임 라벨/메타 JSON 파일명 — {@code {FRM_NO 4자리 zero-pad}.json}
     */
    public record ChangedItems(
            @JsonProperty("images") List<String> images,
            @JsonProperty("jsons") List<String> jsons
    ) {
        public ChangedItems {
            images = images == null ? List.of() : List.copyOf(images);
            jsons = jsons == null ? List.of() : List.copyOf(jsons);
        }

        public static ChangedItems empty() {
            return new ChangedItems(List.of(), List.of());
        }
    }
}
