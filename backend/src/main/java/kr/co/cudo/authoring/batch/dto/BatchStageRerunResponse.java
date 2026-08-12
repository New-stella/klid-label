package kr.co.cudo.authoring.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 작업 묶음 지목 재수행 <b>접수</b> 응답. [@design API-201]
 *
 * <p><b>이 응답은 "접수했다"까지만 말한다.</b> 요청 안에서 하는 일은 배치 단계 상태 선점
 * (COMPLETED→PROCESSING 원자 클레임)까지이고 파이프라인 실행은 비동기다 — 200 은 재수행이 끝났다는
 * 뜻이 <b>아니다</b>. 진행 상황·최종 결과는 영상 상세 조회의 단계 표시로 확인한다.
 *
 * <p>{@code stage} 는 <b>요청을 되비추는 값</b>이며 서버가 해석한 결과가 아니다(어떤 묶음으로 접수됐는지
 * 화면이 즉시 표시하기 위한 것이다). 필드명이 {@code stage} 인 것은 경로 변수명과 맞춘 것이고, 값은
 * 개별 단계가 아니라 <b>작업 묶음 코드</b>({@code VLM}/{@code AUTOLABEL})다.
 *
 * <p>★구 {@code scope} 필드는 폐기됐다 — 묶음이 곧 범위라 고를 것이 없다.
 *
 * @param rawSn    대상 영상 단위 식별자
 * @param stage    재수행 대상 작업 묶음 — {@code VLM} / {@code AUTOLABEL}
 * @param accepted 접수 여부 — 접수에 실패하면 예외로 나가므로 성공 응답에서는 항상 {@code true}
 */
@Schema(description = "작업 묶음 지목 재수행 접수 응답 — 실행은 비동기다. 200 은 접수 사실이지 완료가 아니다")
public record BatchStageRerunResponse(
        @Schema(description = "영상 단위 식별자", example = "1") Long rawSn,
        @Schema(description = "재수행 대상 작업 묶음", example = "AUTOLABEL") String stage,
        @Schema(description = "접수 여부(성공 응답은 항상 true)", example = "true") boolean accepted) {
}
