package kr.co.cudo.authoring.video.dto;

import java.util.List;

/**
 * 오토라벨 요약 응답 (SCR-AUTO-001) — 실데이터 집계.
 *
 * <p>FE {@code features/auto/types.ts AutoLabelSummary} 와 정합. placeholder 시절 필드명을 유지하되
 * 실집계 값을 채운다.
 *
 * <ul>
 *   <li>{@code status='PENDING'} — 배치 미완료(프레임 0)일 때만. 이 경우 FE 는 buckets/classDistribution
 *       부재로 placeholder UI 를 렌더하므로 두 필드를 비워 둔다.</li>
 *   <li>{@code status='COMPLETED'} — 프레임이 1건 이상이면 풀 응답. buckets/classDistribution/
 *       lowConfidenceFrames 가 채워진다.</li>
 * </ul>
 *
 * @param videoId             영상 RAW_SN
 * @param totalFrames         총 프레임 수 (LS_DATA_SRC)
 * @param totalLabels         총 라벨 수 (LS_DATA_LBL)
 * @param averageConfidence   평균 신뢰도 (0~1). 신뢰도 보유 라벨 기준. 없으면 0.
 * @param buckets             신뢰도 분포 (high/mid/low). PENDING 일 때 null.
 * @param classDistribution   클래스(라벨명)별 분포. PENDING 일 때 null.
 * @param lowConfidenceFrames 저신뢰(임계 미만) 프레임 목록. PENDING 일 때 null.
 * @param status              "PENDING"(프레임 0) / "COMPLETED"(집계 완료)
 * @param message             status 보조 메시지
 */
public record AutoLabelSummaryResponse(
        Long videoId,
        long totalFrames,
        long totalLabels,
        double averageConfidence,
        List<ConfidenceBucket> buckets,
        List<ClassDistribution> classDistribution,
        List<LowConfidenceFrame> lowConfidenceFrames,
        String status,
        String message
) {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** 프레임 0건 — 배치 미완료 placeholder (FE 는 buckets/classDistribution 부재로 분기). */
    public static AutoLabelSummaryResponse pending(Long videoId) {
        return new AutoLabelSummaryResponse(
                videoId, 0L, 0L, 0.0, null, null, null,
                STATUS_PENDING, "배치 처리 대기 중 — 프레임이 추출되지 않았습니다.");
    }

    public record ConfidenceBucket(
            /** high(>=0.9) / mid(0.7~0.9) / low(<0.7) */
            String bucket,
            long count,
            /** 신뢰도 보유 라벨 대비 비율 (0~1) */
            double ratio
    ) {}

    public record ClassDistribution(
            int classId,
            String className,
            long count
    ) {}

    public record LowConfidenceFrame(
            Long srcSn,
            int frameNo,
            double confidence,
            String thumbnailUrl
    ) {}
}
