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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 라벨 마스터 응용 서비스.
 *
 * <p>CVAT-Like 라벨 풀 포팅 Phase 1.
 *
 * <p>비즈니스 규칙:
 * <ul>
 *   <li>create: 동일 name 중복 시 {@link ErrorCode#CONFLICT}.</li>
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
    public List<LabelMasterResponse> list() {
        return labelRepository.findByUseYnOrderBySortSeqAsc(USE_YN_ACTIVE).stream()
                .map(LabelMasterResponse::from)
                .toList();
    }

    /**
     * 라벨 신규 등록 — 이름 trim 후 저장. 활성 라벨 중 대소문자+공백 무시 근사중복 시 CONFLICT.
     *
     * <p>앱단 조기 판정(existsActiveByNormalizedName)에 더해, 동시 생성 경합은 DB 부분 유니크
     * 인덱스(V120 UK_LS_LABEL_NM_CI)가 원자적으로 차단하고 GlobalExceptionHandler 가 409 로 변환한다.
     */
    @Transactional("controlTransactionManager")
    public LabelMasterResponse create(LabelMasterRequest req, String regId) {
        String name = req.name().trim();
        if (labelRepository.existsActiveByNormalizedName(name, USE_YN_ACTIVE)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 사용 중인 라벨 이름입니다.");
        }
        LsLabel saved = labelRepository.save(LsLabel.create(
                name,
                req.color(),
                req.type(),
                req.sortNo(),
                regId
        ));
        log.info("[Label] created labelId={}, name={}", saved.getLabelId(), saved.getLabelNm());
        return LabelMasterResponse.from(saved);
    }

    /** 라벨 수정 — 존재 검증 + 이름 trim + 활성 근사중복 충돌 검증(자기 자신 제외). */
    @Transactional("controlTransactionManager")
    public LabelMasterResponse update(Long labelId, LabelMasterRequest req, String mdfcnId) {
        LsLabel label = labelRepository.findById(labelId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "라벨을 찾을 수 없습니다."));
        String name = req.name().trim();
        // name 변경 시 활성 근사중복 검증 (자기 자신 제외).
        if (labelRepository.existsActiveByNormalizedNameExcludingId(name, USE_YN_ACTIVE, labelId)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 사용 중인 라벨 이름입니다.");
        }
        label.update(name, req.color(), req.type(), req.sortNo(), mdfcnId);
        log.info("[Label] updated labelId={}, name={}", labelId, name);
        return LabelMasterResponse.from(label);
    }

    /**
     * Phase 6 (AutoLabel preset 매핑) — 자동 라벨링 단계가 ai-server 응답 라벨명을
     * LS_LABEL FK 로 변환할 때 사용.
     *
     * <ul>
     *   <li>null/blank 입력 → {@link Optional#empty()} (repository 호출 없음)</li>
     *   <li>name trim 후 use_yn='Y' 인 라벨만 대소문자 무시 매칭</li>
     *   <li>미매칭 시 {@link Optional#empty()} (LABEL_ID 는 NULL 로 저장됨)</li>
     * </ul>
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Optional<Long> findLabelIdByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return labelRepository.findByLabelNmIgnoreCaseAndUseYn(name.trim(), USE_YN_ACTIVE)
                .map(LsLabel::getLabelId);
    }

    /**
     * 프리셋 코드 join·검증용 — 활성(USE_YN='Y') 라벨을 labelId 집합으로 일괄 조회한다(N+1 방지).
     *
     * <p>반환 Map 의 key 는 labelId. 요청 id 중 반환에 없는 것은 미존재 또는 soft delete 를 의미한다
     * (호출자가 미연결/검증 실패로 처리).
     *
     * @param labelIds 조회할 labelId 집합 (null/빈값이면 빈 Map)
     * @return labelId → 응답 DTO (라벨명/형태 포함)
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Map<Long, LabelMasterResponse> findActiveByIds(Collection<Long> labelIds) {
        if (labelIds == null || labelIds.isEmpty()) {
            return Map.of();
        }
        return labelRepository.findByLabelIdInAndUseYn(labelIds, USE_YN_ACTIVE).stream()
                .collect(Collectors.toMap(LsLabel::getLabelId, LabelMasterResponse::from));
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
