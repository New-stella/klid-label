package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.transfer.dto.ImportMappingCreateRequest;
import kr.co.cudo.authoring.transfer.dto.ImportMappingListResponse;
import kr.co.cudo.authoring.transfer.dto.ImportMappingResponse;
import kr.co.cudo.authoring.transfer.dto.ImportMappingSaveResponse;
import kr.co.cudo.authoring.transfer.entity.LsOtsdCtgryMpng;
import kr.co.cudo.authoring.transfer.parser.ExternalNameSanitizer;
import kr.co.cudo.authoring.transfer.repository.LsOtsdCtgryMpngRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 외부 분류 대응을 <b>사람이 확정</b>하고 관리하는 통로.
 *
 * <h3>확정은 사람의 행위다</h3>
 * <p>이 서비스에는 이름 유사도로 대응을 만드는 통로가 없다. 추천은 검사 단계가 후보까지만 제시하고,
 * 여기서는 <b>요청이 명시한 연결 대상</b>만 저장한다. 짐작으로 연결하면 다른 분류로 저장되고,
 * 저장된 뒤에는 어느 것이 짐작이었는지 구분할 수 없다.
 *
 * <h3>연결 대상은 실재를 확인하고 저장한다</h3>
 * <p>라벨은 <b>쓰는 라벨</b>인지, 이벤트 유형은 <b>등록된 유형</b>인지 확인한다. 확인하지 않으면
 * 없는 라벨은 외래키에서 깨져 500 이 되고, 없는 이벤트 유형은 조용히 저장돼 그 대응으로 적재된
 * 영상이 어디에도 걸리지 않는다(오타 한 글자가 그대로 사실이 된다).
 *
 * <h3>해제는 삭제가 아니다</h3>
 * <p>행을 지우면 과거 이관이 어느 대응으로 적재됐는지 되짚을 수 없다. 그래서 사용여부만 바꾸고,
 * 이미 그 대응으로 적재된 라벨은 건드리지 않는다.
 *
 * @design DOMAIN-017
 * @design API-209
 * @design API-210
 * @design API-211
 * @design AC-043
 */
@Service
@RequiredArgsConstructor
public class ImportMappingService {

    private static final Logger log = LoggerFactory.getLogger(ImportMappingService.class);

    private final LsOtsdCtgryMpngRepository mappingRepository;
    private final LsLabelRepository labelRepository;
    private final EventTypeService eventTypeService;

    /**
     * 대응 목록.
     *
     * @param kind          {@code LABEL} / {@code EVNT_TYPE}. {@code null} 이면 둘 다
     * @param includeUnused 해제된 대응까지 포함할지 여부
     */
    @Transactional(readOnly = true)
    public ImportMappingListResponse list(String kind, boolean includeUnused, Pageable pageable) {
        String normalizedKind = normalizeKind(kind);
        String useYn = includeUnused ? null : LsOtsdCtgryMpng.USE_YES;
        Page<LsOtsdCtgryMpng> page = mappingRepository.search(normalizedKind, useYn, pageable);

        // 라벨 이름은 페이지 단위로 한 번에 모아 읽는다(N+1 방지). 마스터에 없거나 비활성이면 null 이며,
        // 그 행을 목록에서 빼지 않는다 — 미연결 상태 그대로 사람이 봐야 한다.
        Map<Long, String> labelNames = loadLabelNames(page.getContent());
        return ImportMappingListResponse.of(page.map(m ->
                ImportMappingResponse.from(m,
                        m.getLblId() == null ? null : labelNames.get(m.getLblId()))));
    }

    /**
     * 대응을 확정한다. 요청 전체가 한 트랜잭션이라 한 건이라도 거부되면 아무것도 저장되지 않는다.
     *
     * @throws CustomException 종류와 연결 대상 불일치(INVALID_INPUT) / 이미 있는 대응(CONFLICT)
     */
    @Transactional
    public ImportMappingSaveResponse save(ImportMappingCreateRequest request, String actorId) {
        boolean overwrite = request.overwriteOrDefault();
        List<ImportMappingCreateRequest.Item> items = request.items();

        validateNoDuplicateWithinRequest(items);
        Set<Long> activeLabelIds = loadActiveLabelIds(items);
        Set<String> registeredEventTypes = eventTypeService.registeredCodes();

        int created = 0;
        int updated = 0;
        for (ImportMappingCreateRequest.Item item : items) {
            validateTarget(item, activeLabelIds, registeredEventTypes);
            String externalName = ExternalNameSanitizer.text(item.externalName(),
                    LsOtsdCtgryMpng.OTSD_CTGRY_NM_MAX);

            Optional<LsOtsdCtgryMpng> existing = mappingRepository
                    .findByMpngKndCdAndOtsdCtgryCd(item.kind(), item.externalCode());
            if (existing.isPresent()) {
                if (!overwrite) {
                    // 이미 그 대응으로 적재된 결과가 있을 수 있다 — 의도를 밝히지 않은 변경은 막는다.
                    throw new CustomException(ErrorCode.CONFLICT,
                            "같은 종류·같은 이름의 대응이 이미 있습니다.");
                }
                LsOtsdCtgryMpng mapping = existing.get();
                if (item.isLabelKind()) {
                    mapping.remapToLabel(item.labelId(), actorId);
                } else {
                    mapping.remapToEventType(item.evntTypeCd(), actorId);
                }
                mapping.renameExternalCategory(externalName, actorId);
                // 해제됐던 대응을 다시 확정하는 것도 이 경로다 — 사용여부를 되살리지 않으면 저장은
                // 됐는데 검사에서는 여전히 미확정으로 보인다.
                mapping.enable(actorId);
                updated++;
            } else {
                mappingRepository.save(item.isLabelKind()
                        ? LsOtsdCtgryMpng.forLabel(item.externalCode(), externalName,
                                item.labelId(), actorId)
                        : LsOtsdCtgryMpng.forEventType(item.externalCode(), externalName,
                                item.evntTypeCd(), actorId));
                created++;
            }
        }
        log.info("[Import] category mappings confirmed created={}, updated={}, actor={}",
                created, updated, LogSanitizer.sanitize(actorId));
        return new ImportMappingSaveResponse(created, updated);
    }

    /**
     * 대응을 해제한다(멱등) — 행은 남기고 사용여부만 바꾼다.
     *
     * @throws CustomException 그 대응이 없을 때(NOT_FOUND)
     */
    @Transactional
    public void disable(long mpngSn, String actorId) {
        LsOtsdCtgryMpng mapping = mappingRepository.findById(mpngSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "해당 분류 대응을 찾을 수 없습니다."));
        mapping.disable(actorId);
        log.info("[Import] category mapping disabled mpngSn={}, actor={}",
                mpngSn, LogSanitizer.sanitize(actorId));
    }

    // ------------------------------------------------------------------ 검증

    /** 종류가 둘 중 하나가 아니면 조용히 무시하지 않고 거부한다(오타가 전체 조회로 둔갑하지 않게). */
    private static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return null;
        }
        if (!LsOtsdCtgryMpng.MPNG_KND_LABEL.equals(kind)
                && !LsOtsdCtgryMpng.MPNG_KND_EVNT_TYPE.equals(kind)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "대응 종류가 올바르지 않습니다.");
        }
        return kind;
    }

    /**
     * 한 요청 안에 같은 종류와 분류가 두 번 들어오는 것을 막는다.
     *
     * <p>막지 않으면 앞의 것이 저장된 뒤 뒤의 것이 유일 제약에 걸려 <b>요청 전체가 500</b> 이 된다.
     * 어느 쪽이 사람의 의도인지 서버가 고를 근거도 없다.
     */
    private static void validateNoDuplicateWithinRequest(List<ImportMappingCreateRequest.Item> items) {
        Set<String> seen = new HashSet<>();
        for (ImportMappingCreateRequest.Item item : items) {
            if (!seen.add(item.kind() + " " + item.externalCode())) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "한 요청에 같은 분류에 대한 대응이 두 번 들어 있습니다.");
            }
        }
    }

    /** 종류와 연결 대상이 맞는지, 그리고 그 대상이 실재하는지. */
    private static void validateTarget(ImportMappingCreateRequest.Item item,
                                       Set<Long> activeLabelIds, Set<String> registeredEventTypes) {
        boolean hasLabel = item.labelId() != null;
        boolean hasEventType = item.evntTypeCd() != null && !item.evntTypeCd().isBlank();
        if (hasLabel == hasEventType) {
            // 둘 다 있거나 둘 다 없으면 어느 축인지 정해지지 않는다.
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "라벨과 이벤트 유형 중 하나만 지정해야 합니다.");
        }
        if (item.isLabelKind()) {
            if (!hasLabel) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "라벨 대응에는 라벨을 지정해야 합니다.");
            }
            if (!activeLabelIds.contains(item.labelId())) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "지정한 라벨을 찾을 수 없습니다.");
            }
        } else {
            if (!hasEventType) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "이벤트 유형 대응에는 이벤트 유형을 지정해야 합니다.");
            }
            if (!registeredEventTypes.contains(item.evntTypeCd())) {
                throw new CustomException(ErrorCode.INVALID_INPUT, "지정한 이벤트 유형을 찾을 수 없습니다.");
            }
        }
    }

    // ------------------------------------------------------------------ 조회 보조

    private Set<Long> loadActiveLabelIds(List<ImportMappingCreateRequest.Item> items) {
        Set<Long> requested = items.stream()
                .map(ImportMappingCreateRequest.Item::labelId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (requested.isEmpty()) {
            return Set.of();
        }
        return labelRepository.findByLabelIdInAndUseYn(requested, LsOtsdCtgryMpng.USE_YES).stream()
                .map(LsLabel::getLabelId)
                .collect(Collectors.toSet());
    }

    private Map<Long, String> loadLabelNames(List<LsOtsdCtgryMpng> mappings) {
        Set<Long> ids = mappings.stream()
                .map(LsOtsdCtgryMpng::getLblId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return labelRepository.findByLabelIdInAndUseYn(ids, LsOtsdCtgryMpng.USE_YES).stream()
                .collect(Collectors.toMap(LsLabel::getLabelId, LsLabel::getLabelNm,
                        (first, second) -> first, LinkedHashMap::new));
    }
}
