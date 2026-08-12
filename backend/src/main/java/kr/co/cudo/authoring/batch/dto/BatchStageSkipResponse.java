package kr.co.cudo.authoring.batch.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 배치 <b>작업 묶음</b> 수동 스킵 결과. [@design API-198]
 *
 * <p>화면이 스킵 직후 재조회 없이 상태를 반영할 수 있도록 <b>표식의 사실</b>만 돌려준다 —
 * 내부 로그 식별자·예외 원문·파일 경로는 담지 않는다(CWE-209).
 *
 * <p>필드명이 {@code stage} 인 것은 경로 변수명과 맞춘 것이고, 값은 개별 단계가 아니라 <b>작업 묶음
 * 코드</b>({@code VLM}/{@code AUTOLABEL})다.
 *
 * @param rawSn    영상 식별자
 * @param stage    건너뛴 작업 묶음 코드(VLM/AUTOLABEL)
 * @param skipped  현재 스킵 상태(스킵 API 응답에서는 항상 {@code true})
 * @param reason   저장된 사유(접두 포함) — 사용자가 적은 문구가 정제되어 그대로 보인다
 * @param skippedAt 표식이 적재된 시각
 */
@Schema(description = "배치 작업 묶음 수동 스킵 결과")
public record BatchStageSkipResponse(
        Long rawSn,
        String stage,
        boolean skipped,
        String reason,
        LocalDateTime skippedAt) {
}
