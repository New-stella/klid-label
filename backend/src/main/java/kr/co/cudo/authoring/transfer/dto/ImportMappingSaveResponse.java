package kr.co.cudo.authoring.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 분류 대응 확정 결과.
 *
 * @param created 새로 저장된 건수
 * @param updated 기존 대응을 바꿔 쓴 건수
 * @design API-210
 */
@Schema(description = "분류 대응 확정 결과")
public record ImportMappingSaveResponse(int created, int updated) {
}
