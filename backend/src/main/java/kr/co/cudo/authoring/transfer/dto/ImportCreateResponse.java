package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 외부 산출물 적재 결과(API-206).
 *
 * @param rawSn      만들어진 영상의 식별번호
 * @param trnsfSn    이번 이관의 식별번호
 * @param frameCount 적재된 프레임 수 — <b>실제 파일 기준</b>이며 문서 선언 건수가 아니다(AC-047)
 * @param labelCount 적재된 라벨 수
 * @design API-206
 */
@Schema(description = "외부 산출물 적재 결과")
public record ImportCreateResponse(long rawSn, long trnsfSn, int frameCount, int labelCount) {
}
