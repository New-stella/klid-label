package kr.co.cudo.authoring.quality.dto;

import kr.co.cudo.authoring.common.util.AnnotationConflict;
import kr.co.cudo.authoring.common.util.ConflictType;

import java.util.List;

/**
 * Phase 10 — 품질 자동 검사 결과.
 *
 * <p>{@code blocked} = true 면 export 차단 (현재 정책: ERROR 유형 1건이라도 있으면 차단).
 * MISSING / EXTRA / MISMATCHING_LABEL 은 ERROR, LOW_OVERLAP 은 WARNING.
 */
public record QualityCheckResult(
        Long srcSn,
        List<AnnotationConflict> conflicts,
        List<AnnotationConflict> warnings,
        boolean blocked
) {

    public static QualityCheckResult of(Long srcSn, List<AnnotationConflict> all) {
        List<AnnotationConflict> errors = all.stream()
                .filter(c -> c.type() == ConflictType.MISSING
                        || c.type() == ConflictType.EXTRA
                        || c.type() == ConflictType.MISMATCHING_LABEL)
                .toList();
        List<AnnotationConflict> warnings = all.stream()
                .filter(c -> c.type() == ConflictType.LOW_OVERLAP)
                .toList();
        return new QualityCheckResult(srcSn, errors, warnings, !errors.isEmpty());
    }
}
