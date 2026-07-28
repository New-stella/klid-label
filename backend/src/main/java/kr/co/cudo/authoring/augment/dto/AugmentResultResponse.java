package kr.co.cudo.authoring.augment.dto;

import java.util.List;

/**
 * 증강 작업 결과 조회 응답 — {@code GET /v1/augments/{jobId}/result}.
 *
 * <p><b>기존 응답 키({@code jobId}/{@code status}/{@code results}/{@code message})를 그대로 유지</b>한다
 * (FE {@code AugmentResultPage} 계약). {@code page}/{@code size} 는 프레임 쌍 페이징을 위한 additive 필드로,
 * 구 FE 는 무시해도 동작한다.
 *
 * @param jobId   증강 jobId (= 원본 RAW_SN)
 * @param status  집계 상태 {@code COMPLETED|FAILED|PROCESSING}
 * @param results 유형별 결과 항목 (해상도 파생만 채워짐)
 * @param message 안내 문구
 * @param page    프레임 쌍 페이지 번호 (0-based)
 * @param size    프레임 쌍 페이지 크기
 */
public record AugmentResultResponse(
        Long jobId,
        String status,
        List<AugmentResultItemResponse> results,
        String message,
        int page,
        int size
) {
}
