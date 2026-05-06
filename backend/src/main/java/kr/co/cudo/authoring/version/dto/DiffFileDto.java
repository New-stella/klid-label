package kr.co.cudo.authoring.version.dto;

import kr.co.cudo.authoring.common.client.dto.DiffFile;

public record DiffFileDto(
        String path,
        String change,
        int additions,
        int deletions,
        String patch
) {
    /** OWASP API4:2023 — 단일 파일 patch 최대 길이 (64KB). 초과 시 truncate. */
    public static final int MAX_PATCH_LEN = 65_536;

    public static DiffFileDto from(DiffFile f) {
        return new DiffFileDto(f.path(), f.change(), f.additions(), f.deletions(),
                truncatePatch(f.patch()));
    }

    /** patch 가 MAX_PATCH_LEN 초과 시 잘라내고 원본 길이 표기 suffix 추가. */
    private static String truncatePatch(String patch) {
        if (patch == null || patch.length() <= MAX_PATCH_LEN) {
            return patch;
        }
        int original = patch.length();
        return patch.substring(0, MAX_PATCH_LEN) + "... [TRUNCATED " + original + "b]";
    }
}
