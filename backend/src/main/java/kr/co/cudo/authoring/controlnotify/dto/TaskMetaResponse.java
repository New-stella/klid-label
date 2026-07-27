package kr.co.cudo.authoring.controlnotify.dto;

import java.util.List;

/**
 * 영상별 메타데이터 응답.
 * <p>CWE-359 Privacy: filePath, deidentFilePath 등 파일 경로 필드 미포함.
 *
 * <p><b>페이징(B-4)</b>: {@code items} 는 요청 페이지 분량만 담는다. 전량 반환은 CWE-770 위험이라
 * 기본 20 / 최대 100 으로 제한되며, 총 건수는 {@code totalElements} 로 알린다(하위호환 — 기존
 * {@code rawSn}/{@code items} 필드는 유지하고 메타 필드만 추가).
 *
 * @param rawSn         영상 PK
 * @param items         현재 페이지의 메타 항목
 * @param page          현재 페이지 번호(0-base)
 * @param size          페이지 크기
 * @param totalElements 전체 메타 건수
 * @param totalPages    전체 페이지 수
 */
public record TaskMetaResponse(
        Long rawSn,
        List<MetaItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public record MetaItem(
            Long metaSn,
            String metaKey,
            String metaVal
    ) {
    }
}
