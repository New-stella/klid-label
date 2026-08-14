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
 *
 * <h3>⚠⚠ 이 클래스를 <b>배선하기 전에</b> 반드시 읽을 것 — V6 흡수가 의미를 뒤집었다</h3>
 * 현재 이 서비스는 <b>진입점이 0건</b>이다(주입처·컨트롤러 없음, 참조는 테스트 하나). 그래서 아래는
 * 지금 발현되는 결함이 아니라 <b>배선하는 순간 즉시 터지는 잠복 성질</b>이며, 그 이유로 코드를 고치지
 * 않고 경고만 남긴다(도달하지 않는 코드를 추측으로 고치면 그 추측이 사양이 된다).
 *
 * <ul>
 *   <li><b>V6 이전</b>: {@code LsDataLbl.getAutoLblYn()} 이 {@code @Transient} 라 DB 조회 시 항상
 *       {@code null} 이었다 → 자동/수동 두 집합이 <b>모두 빈 리스트</b> → 충돌 0건 → 사실상 무동작.
 *       즉 이 검사는 <b>한 번도 실제로 판정한 적이 없다</b>.</li>
 *   <li><b>V6 이후</b>: 자동 집합은 채워지지만 수동 집합은 여전히 비어 있다 — 수동 라벨의
 *       {@code AUTO_LBL_YN} 은 {@code 'N'} 이 아니라 <b>{@code NULL}</b> 이기 때문이다
 *       (부재와 "AI 가 만들었으나 자동이 아님"을 구분하려는 의도적 설계 — {@code LsDataLbl} 주석 참조).
 *       그 상태로 배선하면 {@code detect(auto, [])} 가 되어 <b>자동 라벨 전건이 MISSING 으로 분류</b>되고
 *       ERROR 1건 이상이므로 <b>export 가 전량 차단</b>된다.</li>
 * </ul>
 *
 * <p><b>따라서 배선 전에 "사람 라벨을 무엇으로 조달할지"를 먼저 정해야 한다</b> — 후보는
 * {@code AUTO_LBL_YN <> 'Y' OR IS NULL}(= 자동이 아닌 전부)이지만, 그러면 비교 대상이 "검수를 거친
 * 사람 라벨"이 아니라 "자동이 아닌 모든 라벨"이 되어 본래 의도(GT 대비 검수본 점검)와 어긋날 수 있다.
 * 이 판단은 요구 확인이 선행돼야 하므로 여기서 임의로 정하지 않는다.
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
        // SKELETON(키포인트 포즈)은 삼중값 좌표라 2-튜플 toShape 파싱이 붕괴하고, IoU 박스/폴리곤
        // 충돌 검출 대상도 아니므로 전 스트림에서 skip 한다(파싱 500 방지 + 오탐 방지).
        List<LsDataLbl> all = labelRepository.findBySrcSn(srcSn).stream()
                .filter(l -> !LsDataLbl.TYPE_SKELETON.equals(l.getLblTypeCd()))
                .toList();
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
