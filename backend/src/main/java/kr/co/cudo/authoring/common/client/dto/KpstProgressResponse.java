package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * KPST {@code GET /retrieve_progress} 응답 DTO.
 *
 * <p>규격: 22-deid-solution-api.md §22.3.6. 프로젝트별 전체 진행률(prjStatus)과 파일별 진행/상태
 * (dsStatus)를 중첩으로 보유한다. 응답 카멜케이스 그대로(서버 명세) 매핑되며, 명세에 없는 추가
 * 필드는 무시한다.
 *
 * <p>{@code dsStatus[].procState} 코드 {@code 2} 가 처리 완료(다운로드 가능) 상태이다(실서버 빌드
 * 기준 — §22.4 문서 표와 상이). 처리 미시작 시 실서버는 {@code procState:null}, {@code totalFrame:null},
 * {@code startTime:"None"} 을 반환하므로 두 숫자 필드는 boxed({@link Integer}) 로 받아 null 역직렬화
 * 실패(FAIL_ON_NULL_FOR_PRIMITIVES) 를 방지한다. 완료 판정 시 null(미시작)은 미완료로 취급해야 한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KpstProgressResponse(
        String result,
        Data data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            int prjCount,
            List<PrjStatus> prjStatus
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrjStatus(
            Long prjId,
            String prjName,
            double progressRate,
            int dsCount,
            List<DsStatus> dsStatus
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DsStatus(
            Long dsId,
            String fileName,
            Integer procState,
            double progressRate,
            Integer totalFrame,
            String startTime,
            String endTime
    ) {
    }
}
