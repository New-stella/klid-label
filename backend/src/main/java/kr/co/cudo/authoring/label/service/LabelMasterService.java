package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.dto.LabelMasterRequest;
import kr.co.cudo.authoring.label.dto.LabelMasterResponse;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 라벨 마스터 응용 서비스.
 *
 * <p>CVAT-Like 라벨 풀 포팅 Phase 1.
 *
 * <p>비즈니스 규칙:
 * <ul>
 *   <li>create: 동일 (pjtId, name) 중복 시 {@link ErrorCode#CONFLICT}.</li>
 *   <li>update: name 변경 시 동일 검증, 자기 자신 제외.</li>
 *   <li>delete: soft delete (USE_YN='N'). hard delete 절대 안 함.</li>
 *   <li>list: 활성(USE_YN='Y') 라벨만 SORT_NO ASC 로 반환.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LabelMasterService {

    private static final String USE_YN_ACTIVE = "Y";

    private final LsLabelRepository labelRepository;

    /** 활성 라벨 목록 — sort_no ASC. */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<LabelMasterResponse> list(Long pjtId) {
        return labelRepository.findByPjtIdAndUseYnOrderBySortNoAsc(pjtId, USE_YN_ACTIVE).stream()
                .map(LabelMasterResponse::from)
                .toList();
    }

    /** 라벨 신규 등록 — 동일 (pjtId, name) 중복 시 CONFLICT. */
    @Transactional("controlTransactionManager")
    public LabelMasterResponse create(LabelMasterRequest req, String regId) {
        if (labelRepository.existsByPjtIdAndName(req.pjtId(), req.name())) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 사용 중인 라벨 이름입니다.");
        }
        LsLabel saved = labelRepository.save(LsLabel.create(
                req.pjtId(),
                req.name(),
                req.color(),
                req.type(),
                req.sortNo(),
                regId
        ));
        log.info("[Label] created labelId={}, pjtId={}, name={}", saved.getLabelId(), saved.getPjtId(), saved.getName());
        return LabelMasterResponse.from(saved);
    }

    /** 라벨 수정 — 존재 검증 + 동일 이름 충돌 검증. */
    @Transactional("controlTransactionManager")
    public LabelMasterResponse update(Long labelId, LabelMasterRequest req, String mdfcnId) {
        LsLabel label = labelRepository.findById(labelId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "라벨을 찾을 수 없습니다."));
        // name 변경 시 동일 검증 (자기 자신 제외).
        if (labelRepository.existsByPjtIdAndNameAndLabelIdNot(req.pjtId(), req.name(), labelId)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 사용 중인 라벨 이름입니다.");
        }
        label.update(req.name(), req.color(), req.type(), req.sortNo(), mdfcnId);
        log.info("[Label] updated labelId={}, name={}", labelId, req.name());
        return LabelMasterResponse.from(label);
    }

    /** Soft delete — USE_YN='N'. */
    @Transactional("controlTransactionManager")
    public void delete(Long labelId, String mdfcnId) {
        LsLabel label = labelRepository.findById(labelId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "라벨을 찾을 수 없습니다."));
        label.softDelete(mdfcnId);
        log.info("[Label] softDeleted labelId={}", labelId);
    }
}
