package kr.co.cudo.authoring.controlnotify.dto;

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
 * @param jobId        작업 ID — {@code String.valueOf(LS_DATA_RAW.RAW_SN)}
 * @param changedItems 변경 파일 목록 (이미지/JSON)
 */
public record TaskModifiedPayload(
        @JsonProperty("job_id") String jobId,
        @JsonProperty("changed_items") ChangedItems changedItems
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
