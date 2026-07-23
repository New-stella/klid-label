package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.label.domain.CocoClasses;
import kr.co.cudo.authoring.label.dto.DetectCandidateResponse;
import kr.co.cudo.authoring.label.dto.LabelMasterRequest;
import kr.co.cudo.authoring.label.dto.LabelMasterResponse;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
     * AI 탐지 후보 — 활성 라벨 마스터 목록 + 각 라벨의 COCO 매핑 여부(sort_no ASC).
     *
     * <p>라벨링 화면 'AI 탐지' 팝업이 소비한다. 매핑된 라벨만 선택 가능(mapped=true)하며,
     * 실제 검출 대상 재구성·재검증은 BE(신뢰 경계, HIGH#1)가 담당한다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<DetectCandidateResponse> listDetectCandidates() {
        return labelRepository.findByUseYnOrderBySortSeqAsc(USE_YN_ACTIVE).stream()
                .map(DetectCandidateResponse::from)
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
        // 검출유형(COCO) 매핑 — allowlist 검증(400) + 활성 중복 매핑 선검증(409, DB 유니크 이중가드).
        String dtctTypeCd = validateAndNormalizeDtctType(req.dtctTypeCd());
        if (dtctTypeCd != null && labelRepository.existsActiveByDtctType(dtctTypeCd, USE_YN_ACTIVE)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 사용 중인 검출 클래스 매핑입니다.");
        }
        LsLabel saved = labelRepository.save(LsLabel.create(
                name,
                req.color(),
                req.type(),
                req.sortNo(),
                dtctTypeCd,
                regId
        ));
        log.info("[Label] created labelId={}, name={}, dtctTypeCd={}",
                saved.getLabelId(), saved.getLabelNm(), saved.getDtctTypeCd());
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
        // 검출유형(COCO) 매핑 — allowlist 검증(400) + 활성 중복 매핑 선검증(409, 자기 자신 제외).
        String dtctTypeCd = validateAndNormalizeDtctType(req.dtctTypeCd());
        if (dtctTypeCd != null
                && labelRepository.existsActiveByDtctTypeExcludingId(dtctTypeCd, USE_YN_ACTIVE, labelId)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 사용 중인 검출 클래스 매핑입니다.");
        }
        label.update(name, req.color(), req.type(), req.sortNo(), dtctTypeCd, mdfcnId);
        log.info("[Label] updated labelId={}, name={}, dtctTypeCd={}", labelId, name, dtctTypeCd);
        return LabelMasterResponse.from(label);
    }

    /**
     * 검출유형(COCO) 매핑값 검증·정규화.
     * <ul>
     *   <li>null/blank → null(미매핑) — 매핑 해제 허용.</li>
     *   <li>trim 후 COCO 80 allowlist({@link CocoClasses}) 미포함 → {@link ErrorCode#INVALID_INPUT}(400).
     *       자유텍스트·오타·미지원 값 유입 차단(CWE-20).</li>
     * </ul>
     */
    private String validateAndNormalizeDtctType(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        if (!CocoClasses.isValid(normalized)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "지원하지 않는 검출 클래스입니다.");
        }
        return normalized;
    }

    /**
     * AI 검출 결과(COCO 영문명) → LS_LABEL FK 매핑 — 온라인/배치 오토라벨 공용 단일 매핑 축(HIGH#7).
     *
     * <p>구 이름 사후매칭({@code findLabelIdByName}, COCO명↔labelNm)을 전면 대체한다. 라벨명은
     * 한글 등 자유 명칭이라 COCO 영문명과 1:1 이 보장되지 않으므로, 검출 귀속은 명시적 매핑
     * (DTCT_TYPE_CD)만을 축으로 삼는다.
     *
     * <ul>
     *   <li>null/blank 입력 → {@link Optional#empty()} (repository 호출 없음)</li>
     *   <li>trim 후 use_yn='Y' + DTCT_TYPE_CD 정확 매칭(V129 유니크로 최대 1건)</li>
     *   <li>미매핑 시 {@link Optional#empty()} (LABEL_ID 는 NULL 로 저장/반환됨)</li>
     * </ul>
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Optional<Long> findLabelIdByDtctType(String cocoLabel) {
        if (cocoLabel == null || cocoLabel.isBlank()) {
            return Optional.empty();
        }
        return labelRepository.findByDtctTypeCdAndUseYn(cocoLabel.trim(), USE_YN_ACTIVE)
                .map(LsLabel::getLabelId);
    }

    /**
     * 활성 라벨에 매핑된 COCO 클래스명 집합 — AI 검출 대상 재구성(HIGH#1)용.
     *
     * <p>온라인 오토라벨이 FE 요청을 신뢰하지 않고, 이 집합으로 검출 대상을 재구성한다
     * (매핑된 라벨만 실제 검출). 순서는 조회 순서를 보존(LinkedHashSet)한다.
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Set<String> mappedDetectClasses() {
        return new LinkedHashSet<>(labelRepository.findMappedDtctTypeCds(USE_YN_ACTIVE));
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
