package kr.co.cudo.authoring.version.dto;

import kr.co.cudo.authoring.common.client.dto.DiffResponse;

import java.util.List;

public record DiffResponseDto(
        String fromSha,
        String toSha,
        List<DiffFileDto> files
) {
    /** OWASP API4:2023 — diff 응답 최대 파일 개수. 초과 시 잘라냄 (메모리/응답 크기 보호). */
    public static final int MAX_FILES = 100;

    public static DiffResponseDto of(String fromSha, String toSha, DiffResponse resp) {
        List<DiffFileDto> files = resp.files().stream()
                .limit(MAX_FILES)
                .map(DiffFileDto::from)
                .toList();
        return new DiffResponseDto(fromSha, toSha, files);
    }
}
