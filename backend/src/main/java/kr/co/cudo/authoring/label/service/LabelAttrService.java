package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.dto.LabelAttrRequest;
import kr.co.cudo.authoring.label.dto.LabelAttrResponse;
import kr.co.cudo.authoring.label.entity.LsLabelAttr;
import kr.co.cudo.authoring.label.repository.LsLabelAttrRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 라벨 속성 정의 응용 서비스 (CVAT-Like 라벨 풀 포팅 Phase 3).
 *
 * <p>비즈니스 규칙:
 * <ul>
 *   <li>create/update: 동일 (labelId, name) 중복 시 {@link ErrorCode#CONFLICT}.</li>
 *   <li>create/update: INPUT_TYPE 이 SELECT/CHECKBOX/RADIO 면 VALUES_JSON 필수.</li>
 *   <li>list: 활성(USE_YN='Y') 속성만 SORT_NO ASC 로 반환.</li>
 *   <li>delete: soft delete (USE_YN='N'). hard delete 절대 안 함.</li>
 *   <li>labelId 존재 검증 — 없는 라벨에 속성을 만들 수 없다.</li>
 * </ul>
 *
 * <p>트랙 전파(MUTABLE='N' 일 때 트랙 단위 동일 값 강제)는 본 Phase 범위 외.
 * 컬럼만 보유하며 강제 로직은 향후 추가.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabelAttrService {

    private static final String USE_YN_ACTIVE = "Y";

    /** 옵션형 INPUT_TYPE — VALUES_JSON 필수. */
    private static final Set<String> OPTION_INPUT_TYPES = Set.of(
            LsLabelAttr.INPUT_SELECT, LsLabelAttr.INPUT_CHECKBOX, LsLabelAttr.INPUT_RADIO);

    private final LsLabelAttrRepository attrRepository;
    private final LsLabelRepository labelRepository;

    /** 활성 속성 목록 — sort_no ASC. */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<LabelAttrResponse> list(Long labelId) {
        ensureLabelExists(labelId);
        return attrRepository.findByLabelIdAndUseYnOrderBySortNoAsc(labelId, USE_YN_ACTIVE).stream()
                .map(LabelAttrResponse::from)
                .toList();
    }

    /** 속성 신규 등록 — 동일 (labelId, name) 중복 시 CONFLICT, 옵션형은 VALUES_JSON 필수. */
    @Transactional("controlTransactionManager")
    public LabelAttrResponse create(Long labelId, LabelAttrRequest req, String regId) {
        ensureLabelExists(labelId);
        validateValuesJson(req.inputType(), req.valuesJson());
        if (attrRepository.existsByLabelIdAndName(labelId, req.name())) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 사용 중인 속성 이름입니다.");
        }
        LsLabelAttr saved = attrRepository.save(LsLabelAttr.create(
                labelId,
                req.name(),
                req.inputType(),
                req.valuesJson(),
                req.defaultVal(),
                req.mutable(),
                req.sortNo(),
                regId
        ));
        log.info("[LabelAttr] created attrId={}, labelId={}, name={}", saved.getAttrId(), labelId, saved.getName());
        return LabelAttrResponse.from(saved);
    }

    /** 속성 수정 — 존재 검증 + 동일 이름 충돌 검증. */
    @Transactional("controlTransactionManager")
    public LabelAttrResponse update(Long labelId, Long attrId, LabelAttrRequest req, String mdfcnId) {
        ensureLabelExists(labelId);
        validateValuesJson(req.inputType(), req.valuesJson());
        LsLabelAttr attr = attrRepository.findById(attrId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "속성을 찾을 수 없습니다."));
        if (!attr.getLabelId().equals(labelId)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "속성을 찾을 수 없습니다.");
        }
        if (attrRepository.existsByLabelIdAndNameAndAttrIdNot(labelId, req.name(), attrId)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 사용 중인 속성 이름입니다.");
        }
        attr.update(req.name(), req.inputType(), req.valuesJson(), req.defaultVal(),
                req.mutable(), req.sortNo(), mdfcnId);
        log.info("[LabelAttr] updated attrId={}, name={}", attrId, req.name());
        return LabelAttrResponse.from(attr);
    }

    /** Soft delete — USE_YN='N'. */
    @Transactional("controlTransactionManager")
    public void delete(Long labelId, Long attrId, String mdfcnId) {
        ensureLabelExists(labelId);
        LsLabelAttr attr = attrRepository.findById(attrId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "속성을 찾을 수 없습니다."));
        if (!attr.getLabelId().equals(labelId)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "속성을 찾을 수 없습니다.");
        }
        attr.softDelete(mdfcnId);
        log.info("[LabelAttr] softDeleted attrId={}", attrId);
    }

    private void ensureLabelExists(Long labelId) {
        if (!labelRepository.existsById(labelId)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "라벨을 찾을 수 없습니다.");
        }
    }

    /** 옵션형(SELECT/CHECKBOX/RADIO) 은 valuesJson 필수, 그 외(NUMBER/TEXT) 는 null 허용. */
    private void validateValuesJson(String inputType, String valuesJson) {
        if (OPTION_INPUT_TYPES.contains(inputType)) {
            if (valuesJson == null || valuesJson.isBlank()) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "SELECT/CHECKBOX/RADIO 타입은 valuesJson 이 필수입니다.");
            }
        }
    }
}
