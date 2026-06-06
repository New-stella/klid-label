package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.dto.AutoLabelSummaryResponse;
import kr.co.cudo.authoring.video.dto.AutoLabelSummaryResponse.ClassDistribution;
import kr.co.cudo.authoring.video.dto.AutoLabelSummaryResponse.ConfidenceBucket;
import kr.co.cudo.authoring.video.dto.AutoLabelSummaryResponse.LowConfidenceFrame;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * SCR-AUTO-001 오토라벨 요약 실데이터 집계 (G-2).
 *
 * <p>기존 placeholder({@code status='PENDING'} 고정 + 카운트 0)를 대체한다.
 * <ul>
 *   <li>영상(rawSn) 미존재 → {@link ErrorCode#NOT_FOUND} 404.</li>
 *   <li>프레임 0건(배치 미완료) → {@code status='PENDING'} placeholder 의미 유지.</li>
 *   <li>프레임 1건 이상 → LS_DATA_SRC/LS_DATA_LBL 실집계 (총 프레임/라벨/클래스 분포/신뢰도 분포/저신뢰 프레임).</li>
 * </ul>
 *
 * <p>성능: 모든 집계는 GROUP BY/COUNT 단일 쿼리 — 라벨 단위 N+1 금지 (rules/performance.md).
 */
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class AutoLabelSummaryService {

    /** 저신뢰 프레임 임계값 — FE 가 70% 미만을 강조하므로 0.7 미만을 저신뢰로 수집. */
    private static final BigDecimal LOW_CONFIDENCE_THRESHOLD = new BigDecimal("0.7");

    /** 저신뢰 프레임 목록 상한 — 과도한 응답 크기 방지 (CWE-770). */
    private static final int LOW_CONFIDENCE_LIMIT = 60;

    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;

    public AutoLabelSummaryResponse summarize(Long rawSn) {
        if (!videoRepository.existsById(rawSn)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다.");
        }

        long totalFrames = srcRepository.countByRawSn(rawSn);
        if (totalFrames == 0) {
            // 배치 미완료 — placeholder 의미 유지 (FE 는 buckets/classDistribution 부재로 분기).
            return AutoLabelSummaryResponse.pending(rawSn);
        }

        long totalLabels = lblRepository.countByRawSn(rawSn);
        double averageConfidence = computeAverageConfidence(rawSn);
        List<ClassDistribution> classDistribution = aggregateClasses(rawSn);
        List<ConfidenceBucket> buckets = aggregateBuckets(rawSn);
        List<LowConfidenceFrame> lowFrames = collectLowConfidenceFrames(rawSn);

        return new AutoLabelSummaryResponse(
                rawSn,
                totalFrames,
                totalLabels,
                averageConfidence,
                buckets,
                classDistribution,
                lowFrames,
                AutoLabelSummaryResponse.STATUS_COMPLETED,
                null);
    }

    private double computeAverageConfidence(Long rawSn) {
        Object[] row = lblRepository.aggregateConfidenceSum(rawSn);
        // JPA 가 SELECT a, b 를 단일 row 로 반환 시 Object[] 안에 [sum, cnt] 가 또 한 번 래핑될 수 있어 정규화.
        Object[] cell = normalizeRow(row);
        if (cell == null) {
            return 0.0;
        }
        BigDecimal sum = (BigDecimal) cell[0];
        long cnt = ((Number) cell[1]).longValue();
        if (sum == null || cnt == 0) {
            return 0.0;
        }
        return sum.divide(BigDecimal.valueOf(cnt), 4, RoundingMode.HALF_UP).doubleValue();
    }

    private List<ClassDistribution> aggregateClasses(Long rawSn) {
        List<ClassDistribution> result = new ArrayList<>();
        for (Object[] r : lblRepository.aggregateClassDistribution(rawSn)) {
            int classId = r[0] == null ? 0 : ((Number) r[0]).intValue();
            String className = r[1] == null ? "(미지정)" : String.valueOf(r[1]);
            long count = ((Number) r[2]).longValue();
            result.add(new ClassDistribution(classId, className, count));
        }
        return result;
    }

    private List<ConfidenceBucket> aggregateBuckets(Long rawSn) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("high", 0L);
        counts.put("mid", 0L);
        counts.put("low", 0L);
        long total = 0;
        for (Object[] r : lblRepository.aggregateConfidenceBuckets(rawSn)) {
            String bucket = String.valueOf(r[0]);
            long count = ((Number) r[1]).longValue();
            counts.merge(bucket, count, Long::sum);
            total += count;
        }
        final long denom = total;
        List<ConfidenceBucket> buckets = new ArrayList<>();
        counts.forEach((bucket, count) -> {
            double ratio = denom == 0 ? 0.0
                    : BigDecimal.valueOf(count)
                        .divide(BigDecimal.valueOf(denom), 4, RoundingMode.HALF_UP)
                        .doubleValue();
            buckets.add(new ConfidenceBucket(bucket, count, ratio));
        });
        return buckets;
    }

    /**
     * 저신뢰 라벨을 프레임 단위로 중복 제거하여 상한 개수까지 수집.
     * confScore ASC 정렬된 쿼리 결과에서 프레임당 최저 신뢰도 라벨 1건만 채택한다.
     */
    private List<LowConfidenceFrame> collectLowConfidenceFrames(Long rawSn) {
        List<Object[]> rows = lblRepository.findLowConfidenceFrames(rawSn, LOW_CONFIDENCE_THRESHOLD);
        Set<Long> seen = new HashSet<>();
        List<LowConfidenceFrame> frames = new ArrayList<>();
        for (Object[] r : rows) {
            Long srcSn = ((Number) r[0]).longValue();
            if (!seen.add(srcSn)) {
                continue;
            }
            int frameNo = r[1] == null ? 0 : ((Number) r[1]).intValue();
            double confidence = ((BigDecimal) r[2]).doubleValue();
            frames.add(new LowConfidenceFrame(
                    srcSn, frameNo, confidence, "/v1/frames/" + srcSn + "/image"));
            if (frames.size() >= LOW_CONFIDENCE_LIMIT) {
                break;
            }
        }
        return frames;
    }

    /**
     * JPA 가 다중 select 표현식을 단일 row 로 반환할 때, 결과를 {@code Object[]{sum, cnt}} 로 정규화한다.
     * 일부 드라이버/버전은 {@code Object[]{Object[]{sum, cnt}}} 로 한 번 더 래핑하므로 평탄화한다.
     */
    private static Object[] normalizeRow(Object[] row) {
        if (row == null || row.length == 0) {
            return null;
        }
        if (row.length == 1 && row[0] instanceof Object[] inner) {
            return inner;
        }
        return row;
    }
}
