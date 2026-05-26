package kr.co.cudo.authoring.controlnotify.dto;

import java.util.List;

/**
 * 프레임 단위 라벨 목록 응답.
 * <p>CWE-359 Privacy: filePath, deidentFilePath 등 파일 경로 필드 미포함.
 */
public record TaskLabelsResponse(
        Long srcSn,
        int frameNo,
        List<LabelItem> labels
) {
    public record LabelItem(
            Long lblSn,
            String lblTypeCd,
            String label,
            String points
    ) {
    }
}
