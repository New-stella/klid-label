package kr.co.cudo.authoring.quality.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.common.util.AnnotationConflict;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.common.util.QualityConflictDetector;
import kr.co.cudo.authoring.quality.dto.QualityCheckResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Phase 10 — export 직전 품질 자동 검사.
 *
 * <p>본 V1 의 비교 정책:
 * <ul>
 *   <li>입력 두 라벨 집합(GT, DS) 을 호출자가 결정.</li>
 *   <li>일반 사용처: 자동 라벨(AUTO_LBL_YN='Y') vs 사람 라벨(AUTO_LBL_YN='N') 을 비교해
 *       검수 통과 라벨이 자동 라벨과 크게 다르지 않은지 점검.</li>
 *   <li>충돌 발견 시 ERROR(MISSING/EXTRA/MISMATCHING_LABEL) 가 1건이라도 있으면 export 차단.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class QualityCheckService {

    private final LsDataLblRepository labelRepository;
    private final ObjectMapper objectMapper;

    /**
     * 단일 프레임의 자동 라벨 vs 사람 라벨 비교.
     */
    public QualityCheckResult check(Long srcSn) {
        List<LsDataLbl> all = labelRepository.findBySrcSn(srcSn);
        List<QualityConflictDetector.LabelShape> autoShapes = all.stream()
                .filter(l -> LsDataLbl.AUTO_YES.equals(l.getAutoLblYn()))
                .map(this::toShape)
                .toList();
        List<QualityConflictDetector.LabelShape> manualShapes = all.stream()
                .filter(l -> LsDataLbl.AUTO_NO.equals(l.getAutoLblYn()))
                .map(this::toShape)
                .toList();
        List<AnnotationConflict> conflicts = QualityConflictDetector.detect(autoShapes, manualShapes);
        QualityCheckResult result = QualityCheckResult.of(srcSn, conflicts);
        log.info("[Quality] check srcSn={} errors={} warnings={} blocked={}",
                srcSn, result.conflicts().size(), result.warnings().size(), result.blocked());
        return result;
    }

    private QualityConflictDetector.LabelShape toShape(LsDataLbl lbl) {
        List<Point> pts = LabelPointSerializer.fromJson(lbl.getPointCn(), objectMapper);
        return new QualityConflictDetector.LabelShape(lbl.getLblSn(), lbl.getLabelNm(), pts);
    }
}
