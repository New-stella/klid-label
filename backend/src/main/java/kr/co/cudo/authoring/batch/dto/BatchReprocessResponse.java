package kr.co.cudo.authoring.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 배치 재처리(재기동) <b>접수</b> 응답 DTO (B3). [@design API-167]
 *
 * <p><b>이 응답은 "접수했다"까지만 말한다.</b> 요청 안에서 하는 일은 실패 상태 선점(FAILED→PROCESSING
 * 원자 클레임)까지이고 파이프라인 실행은 비동기다 — 200 은 파이프라인이 끝났다는 뜻이 <b>아니다</b>.
 * 진행 상황·최종 결과는 영상 상세 조회의 단계 표시로 확인한다.
 *
 * <p>스키마(필드명·타입)는 종전과 같다. 달라진 것은 {@code stage} 가 담는 값의 의미로,
 * 이제 <b>접수 시점 단계</b>(PROCESSING)이며 파이프라인 종료 단계(COMPLETED/FAILED)가 아니다.
 *
 * @param rawSn 재처리 대상 영상 단위 식별자
 * @param stage 접수 시점 배치 단계 — 항상 {@code PROCESSING}
 */
@Schema(description = "배치 재처리 접수 응답 — 실행은 비동기다. 200 은 접수 사실이지 완료가 아니다")
public record BatchReprocessResponse(
        @Schema(description = "영상 단위 식별자", example = "1") Long rawSn,
        @Schema(description = "접수 시점 배치 단계(파이프라인 종료 단계가 아니다)", example = "PROCESSING")
        String stage) {
}
