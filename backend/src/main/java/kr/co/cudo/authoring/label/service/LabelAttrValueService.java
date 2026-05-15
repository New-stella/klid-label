package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabelAttrValueService {

    private static final String USE_YN_ACTIVE = "Y";

    private final LsDataLblAttrValRepository valueRepository;
    private final LsLabelAttrRepository attrRepository;
    private final LsDataLblRepository labelRowRepository;

    /** 객체별 속성값 일괄 upsert. */
    @Transactional("controlTransactionManager")
    public void upsert(Long lblSn, List<LabelAttrValueUpsertRequest.Entry> entries) {
        LsDataLbl labelRow = labelRowRepository.findById(lblSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "라벨 객체를 찾을 수 없습니다."));
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
    public List<LabelAttrValueResponse> findByLblSn(Long lblSn) {
        if (!labelRowRepository.existsById(lblSn)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "라벨 객체를 찾을 수 없습니다.");
        }
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
            String name = attr != null ? attr.getName() : null;
            String inputType = attr != null ? attr.getInputType() : null;
            result.add(new LabelAttrValueResponse(v.getAttrId(), name, inputType, v.getValue()));
        }
        return result;
    }
}
