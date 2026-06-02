package kr.co.cudo.authoring.version.dto;

import java.util.List;

/**
 * 두 버전(versionHash) 간 라벨 단위 diff 응답.
 *
 * <p>Phase 5 (DB 스냅샷 전환) — 파일 단위 diff 메타는 제거되고,
 * BE 가 두 버전의 라벨 JSON 스냅샷을 직접 파싱·비교한 라벨 단위 변경({@code labels}) 만 반환한다.
 */
public record DiffResponseDto(
        String fromHash,
        String toHash,
        List<LabelDiffDto> labels
) {
    /** OWASP API4:2023 — 라벨 단위 diff 최대 개수. 초과 시 잘라냄 (응답 크기 보호). */
    public static final int MAX_LABELS = 500;

    public static DiffResponseDto of(String fromHash, String toHash, List<LabelDiffDto> labels) {
        List<LabelDiffDto> limited = labels == null
                ? List.of()
                : labels.stream().limit(MAX_LABELS).toList();
        return new DiffResponseDto(fromHash, toHash, limited);
    }
}
