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
 * <p>{@code dsStatus[].procState} 코드 {@code 2} 가 처리 완료(다운로드 가능) 상태이다(§22.4).
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
            int procState,
            double progressRate,
            int totalFrame,
            String startTime,
            String endTime
    ) {
    }
}
