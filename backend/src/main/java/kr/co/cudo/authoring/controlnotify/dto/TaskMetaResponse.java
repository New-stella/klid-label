package kr.co.cudo.authoring.controlnotify.dto;

import java.util.List;

/**
 * 영상별 메타데이터 응답.
 * <p>CWE-359 Privacy: filePath, deidentFilePath 등 파일 경로 필드 미포함.
 */
public record TaskMetaResponse(
        Long rawSn,
        List<MetaItem> items
) {
    public record MetaItem(
            Long metaSn,
            String metaKey,
            String metaVal
    ) {
    }
}
