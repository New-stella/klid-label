package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelAttrValueResponse;
import kr.co.cudo.authoring.label.dto.LabelAttrValueUpsertRequest;
import kr.co.cudo.authoring.label.entity.LsDataLblAttrVal;
import kr.co.cudo.authoring.label.entity.LsLabelAttr;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsLabelAttrRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 객체별 속성값 응용 서비스 (CVAT-Like 라벨 풀 포팅 Phase 3).
 *
 * <p>비즈니스 규칙:
 * <ul>
 *   <li>{@code upsert}: (LBL_SN, ATTR_ID) UNIQUE — 존재하면 UPDATE value, 없으면 INSERT.</li>
 *   <li>LS_DATA_LBL 존재 검증 + LS_DATA_LBL.LABEL_ID 와 LS_LABEL_ATTR.LABEL_ID 일치 검증.</li>
 *   <li>LS_LABEL_ATTR.USE_YN='Y' 검증 — 비활성 속성에는 값 저장 불가.</li>
 *   <li>옵션형 value 의 옵션 일치 강제는 MVP 범위 외 — FE 에서 1차 방어.</li>
 *   <li>{@code findByLblSn}: ATTR_ID ASC + LS_LABEL_ATTR 조인 name/inputType 포함.</li>
 * </ul>
 *
 * <p><b>보안 — 영상 단위 인가 (CWE-639 IDOR)</b>: 두 진입점 모두 {@code lblSn} 만으로 접근하므로,
 * 라벨 객체가 속한 프레임({@code LS_DATA_LBL.SRC_SN})을 해석해 {@link LabelAccessGuard} 에 위임한다
 * (REVIEWER 전체 / WORKER 본인 LABELER 배정 영상만 / 그 외 차단). {@code SecurityConfig} 의
 * {@code /v1/**} 매처가 역할·채널만 검사하고 영상 단위 인가는 컨트롤러 진입 후의 이 가드에 위임한다는
 * 불변식(B-ISSUE-63)을 따른다 — 여기서 배정 조회를 재구현하지 않는다.
 *
 * <p><b>검사 순서</b>: 라벨 객체 존재(404) → 인가(403) → <b>비식별 누락 신고 게이트(412)</b> → 업무 규칙(400).
 * 존재하지 않는 {@code lblSn} 은 인가와 무관하게 404 를 유지해 응답 코드가 존재 오라클이 되지 않게 한다
 * (기존 계약 보존).
 *
 * <p><b>비식별 누락 신고 구간 차단(CWE-359)</b>: 신고({@code LS_DATA_RAW.DE_IDNTF_YN='F'}) 구간은
 * "비식별 결과가 잘못됐다고 알려진 구간"이라, 그 위에서 읽고 쓰는 속성값을 그대로 두면
 * ①조회는 라벨 객체의 부가 서술(occluded·방향 등 PII 맥락)을 노출하고 ②저장은 검수를 거치지 않은 값이
 * resolve 후 그대로 관제로 나간다. 라벨 본문 경로({@code LabelService.bulkUpsert} 조회·저장)가 이미 412 로
 * 막혀 있으므로 <b>같은 축의 속성값 경로만 열려 있으면 차단이 우회</b>된다. 판정은
 * {@link LabelAccessGuard#requireNotUnderDeidentReport}(→ {@code DeidentReportGate}) 단일 원천에 위임하며
 * 여기서 {@code "F"} 비교를 재구현하지 않는다. 역할 무관(REVIEWER 포함)이다.
 *
 * <p>영상 PK({@code rawSn})는 인가 검사가 이미 조회한 프레임({@link LabelAccessGuard#verifyAndGet})에서
 * 얻는다 — 게이트 때문에 프레임을 다시 조회하지 않는다(N+1 회피).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabelAttrValueService {

    private static final String USE_YN_ACTIVE = "Y";

    private final LsDataLblAttrValRepository valueRepository;
    private final LsLabelAttrRepository attrRepository;
    private final LsDataLblRepository labelRowRepository;
    /** 영상 단위 인가(CWE-639) 단일 원천 — 라벨 조회/저장 경로와 동일한 가드를 재사용한다. */
    private final LabelAccessGuard accessGuard;

    /** 객체별 속성값 일괄 upsert. */
    @Transactional("controlTransactionManager")
    public void upsert(Long lblSn, List<LabelAttrValueUpsertRequest.Entry> entries, TokenClaims actor) {
        LsDataLbl labelRow = labelRowRepository.findById(lblSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "라벨 객체를 찾을 수 없습니다."));
        // 인가는 업무 규칙 검증(LABEL_ID 미연결 400 등)보다 먼저 — 미배정 WORKER 에게 객체 상태를 알리지 않는다.
        //   verifyAndGet 으로 프레임 행을 함께 받아 rawSn 을 얻는다(신고 게이트용 재조회 없음).
        LsDataSrc frame = accessGuard.verifyAndGet(labelRow.getSrcSn(), actor);
        // 비식별 누락 신고 구간이면 저장 차단(412) — 인가 이후 평가되는 프리컨디션(클래스 javadoc 참조).
        accessGuard.requireNotUnderDeidentReport(frame.getRawSn());
        Long objectLabelId = labelRow.getLabelId();
        if (objectLabelId == null) {
            // LABEL_ID 미연결 객체에는 속성값 저장 불가 (legacy row 보호).
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "LABEL_ID 가 연결되지 않은 객체에는 속성값을 저장할 수 없습니다.");
        }
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (LabelAttrValueUpsertRequest.Entry entry : entries) {
            upsertOne(lblSn, objectLabelId, entry);
        }
        log.info("[LabelAttrValue] upserted lblSn={}, count={}", lblSn, entries.size());
    }

    private void upsertOne(Long lblSn, Long objectLabelId, LabelAttrValueUpsertRequest.Entry entry) {
        LsLabelAttr attr = attrRepository.findById(entry.attrId())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT,
                        "속성을 찾을 수 없습니다: attrId=" + entry.attrId()));
        if (!objectLabelId.equals(attr.getLabelId())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "속성과 객체의 라벨이 일치하지 않습니다: attrId=" + entry.attrId());
        }
        if (!USE_YN_ACTIVE.equals(attr.getUseYn())) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "비활성 속성에는 값을 저장할 수 없습니다: attrId=" + entry.attrId());
        }
        valueRepository.findByLblSnAndAttrId(lblSn, entry.attrId())
                .ifPresentOrElse(
                        existing -> existing.updateValue(entry.value()),
                        () -> valueRepository.save(LsDataLblAttrVal.create(lblSn, entry.attrId(), entry.value()))
                );
    }

    /** 객체별 속성값 조회 — attrId ASC, name/inputType 동봉. */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<LabelAttrValueResponse> findByLblSn(Long lblSn, TokenClaims actor) {
        LsDataLbl labelRow = labelRowRepository.findById(lblSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "라벨 객체를 찾을 수 없습니다."));
        LsDataSrc frame = accessGuard.verifyAndGet(labelRow.getSrcSn(), actor);
        // 신고 구간의 라벨 조회가 412 인 것과 같은 축 — 속성값만 열어두면 그 차단이 우회된다.
        accessGuard.requireNotUnderDeidentReport(frame.getRawSn());
        List<LsDataLblAttrVal> values = valueRepository.findByLblSnOrderByAttrIdAsc(lblSn);
        if (values.isEmpty()) {
            return List.of();
        }
        // N+1 회피 — attrId 일괄 조회.
        List<Long> attrIds = values.stream().map(LsDataLblAttrVal::getAttrId).distinct().toList();
        Map<Long, LsLabelAttr> attrById = attrRepository.findAllById(attrIds).stream()
                .collect(Collectors.toMap(LsLabelAttr::getAttrId, a -> a, (a, b) -> a, HashMap::new));

        List<LabelAttrValueResponse> result = new ArrayList<>(values.size());
        for (LsDataLblAttrVal v : values) {
            LsLabelAttr attr = attrById.get(v.getAttrId());
            String name = attr != null ? attr.getAttrNm() : null;
            String inputType = attr != null ? attr.getInputTypeCd() : null;
            result.add(new LabelAttrValueResponse(v.getAttrId(), name, inputType, v.getValue()));
        }
        return result;
    }
}
