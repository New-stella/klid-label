package kr.co.cudo.authoring.batch.dto;

import java.util.List;

/**
 * GET /v1/batch/status 응답 DTO.
 *  - items: 최근 N건의 BatchStageProgress
 */
public record BatchStatusResponse(List<BatchStageProgress> items) {
}
