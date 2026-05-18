package kr.co.cudo.authoring.version.dto;

import kr.co.cudo.authoring.common.client.dto.DiffResponse;

import java.util.List;

/**
 * 두 SHA 간 diff 응답.
 *
 * <p>{@code files} 는 Gitea 파일 단위 변경 (backward compat — 내부 API/감사 로그).
 * <p>{@code labels} 는 라벨 단위 변경 (Phase 8 보강, 2026-05-19) — FE 작업이력 패널이 사용.
 */
public record DiffResponseDto(
        String fromSha,
        String toSha,
        List<DiffFileDto> files,
        List<LabelDiffDto> labels
) {
    /** OWASP API4:2023 — diff 응답 최대 파일 개수. 초과 시 잘라냄 (메모리/응답 크기 보호). */
    public static final int MAX_FILES = 100;
    /** OWASP API4:2023 — 라벨 단위 diff 최대 개수. 초과 시 잘라냄 (응답 크기 보호). */
    public static final int MAX_LABELS = 500;

    public static DiffResponseDto of(String fromSha, String toSha, DiffResponse resp) {
        List<DiffFileDto> files = resp.files().stream()
                .limit(MAX_FILES)
                .map(DiffFileDto::from)
                .toList();
        return new DiffResponseDto(fromSha, toSha, files, List.of());
    }

    public static DiffResponseDto of(String fromSha, String toSha,
                                     DiffResponse resp, List<LabelDiffDto> labels) {
        List<DiffFileDto> files = resp.files().stream()
                .limit(MAX_FILES)
                .map(DiffFileDto::from)
                .toList();
        List<LabelDiffDto> limited = labels == null
                ? List.of()
                : labels.stream().limit(MAX_LABELS).toList();
        return new DiffResponseDto(fromSha, toSha, files, limited);
    }
}
