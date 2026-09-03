package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 마킹 산출물 일괄 적재 접수 결과 (API-217).
 *
 * <p>이 응답이 뜻하는 것은 <b>작업이 등록되었다</b>는 것뿐이다. 적재는 아직 끝나지 않았으며 진행은
 * 작업 식별번호로 따로 조회한다(API-218). 백 건의 복사와 적재는 한 요청이 기다릴 수 있는 시간을
 * 넘기 때문이다.
 *
 * @param jobSn       등록된 일괄 적재 작업의 식별번호 — 진행 조회에 쓴다
 * @param targetCount 이 작업이 다루기로 한 항목 수 — 진행률의 분모다
 * @design DOMAIN-017
 * @design API-217
 */
@Schema(description = "마킹 산출물 일괄 적재 접수 결과")
public record MarkingImportCreateResponse(long jobSn, int targetCount) {
}
