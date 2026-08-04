package kr.co.cudo.authoring.preset.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.label.domain.LabelGeometry;
import kr.co.cudo.authoring.label.dto.LabelMasterResponse;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.preset.dto.PresetCodeView;
import kr.co.cudo.authoring.preset.dto.PresetView;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset.LabelCodeSpec;
import kr.co.cudo.authoring.preset.entity.LsLabelPresetCode;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 프리셋 도메인 응용 서비스.
 *
 * <p>입력 검증(중복 이름·이벤트 타입·labelId 유효성)은 본 서비스에서 수행하고, 도메인 상태 변경은
 * Aggregate Root({@link LsLabelPreset}) 의 정적 팩토리/도메인 메서드에 위임한다.
 *
 * <p>Phase 2 — 프리셋 코드는 <b>labelId 기반</b>으로 지정된다. 라벨명·형태는 스냅샷을 저장하지 않고
 * 조회 시 라벨 마스터(LS_LABEL)를 실시간 join 하여 파생한다(N+1 방지 위해 labelId 일괄 조회).
 * <ul>
 *   <li>create/update: 각 labelId 가 활성(USE_YN='Y') 마스터에 존재해야 한다 — 미존재/soft delete 는 400.</li>
 *   <li>조회 응답: labelId 로 마스터를 join 하여 라벨명·형태 토글({@link LabelGeometry})을 파생.</li>
 *   <li>미연결 코드(labelId null): 오류 없이 linked=false + legacy 코드 문자열로 노출(AC4).</li>
 * </ul>
 *
 * <p>UNIQUE 제약 충돌(중복 이벤트 매핑) 은 race-safe 하게 DB 단에서만 차단되며,
 * 본 서비스가 {@link DataIntegrityViolationException} 을 {@link ErrorCode#CONFLICT} 로 변환한다.
 */
@Service
@RequiredArgsConstructor
public class PresetService {

    /** 복제 시 충돌 회피 위한 최대 시도 횟수. */
    private static final int CLONE_SUFFIX_MAX = 50;

    private static final String MSG_EVENT_CONFLICT = "이미 다른 프리셋에 매핑된 이벤트입니다";

    private final LsLabelPresetRepository presetRepository;
    private final EventTypeService eventTypeService;
    private final LabelMasterService labelMasterService;

    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<PresetView> list() {
        return toViews(presetRepository.findAllWithCodes());
    }

    @Transactional("controlTransactionManager")
    public PresetView create(String name, String description,
                             List<Long> labelIds, String eventTypeCd) {
        validateEventType(eventTypeCd);
        if (presetRepository.existsByPresetNm(name)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 사용 중인 프리셋 이름입니다.");
        }
        Map<Long, LabelMasterResponse> masters = resolveLabels(labelIds);
        LsLabelPreset preset = LsLabelPreset.createWithOptions(
                name, description, toSpecs(labelIds), eventTypeCd);
        LsLabelPreset saved = saveWithEventUniqueGuard(preset);
        return toView(saved, masters);
    }

    /**
     * 프리셋 수정. 이벤트 매핑 검증은 <b>매핑값이 실제로 바뀔 때만</b> 수행한다(R1-1).
     *
     * <p>제외 대분류 코드가 시스템 설정으로 동적화되면서 어떤 카테고리가 나중에 제외 대상이 될 수
     * 있는데, 매번 재검증하면 그 카테고리에 이미 매핑된 <b>기존 프리셋</b>이 통째로 편집 불가가 된다
     * (이름·설명·라벨만 고치려 해도 400). 매핑을 유지한 편집은 허용하고, 제외된 카테고리로의
     * <b>재매핑</b>은 계속 차단한다. 신규 생성({@link #create})은 기존대로 항상 검증한다.
     */
    @Transactional("controlTransactionManager")
    public PresetView update(long id, String name, String description,
                             List<Long> labelIds, String eventTypeCd) {
        LsLabelPreset preset = presetRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프리셋을 찾을 수 없습니다."));
        if (!java.util.Objects.equals(normalizeEventTypeCd(eventTypeCd), preset.getEventTypeCd())) {
            validateEventType(eventTypeCd);
        }
        if (presetRepository.existsByPresetNmAndPresetIdNot(name, id)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 사용 중인 프리셋 이름입니다.");
        }
        Map<Long, LabelMasterResponse> masters = resolveLabels(labelIds);
        preset.updateBasics(name, description);
        preset.replaceCodes(toSpecs(labelIds));
        preset.assignToEvent(eventTypeCd);
        // dirty-checking 으로 flush 시 UNIQUE 위반 가능 → 명시적 flush 로 throw 위치를 본 메서드 안으로 끌어온다.
        try {
            presetRepository.saveAndFlush(preset);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CONFLICT, MSG_EVENT_CONFLICT, e);
        }
        return toView(preset, masters);
    }

    @Transactional("controlTransactionManager")
    public void delete(long id) {
        if (!presetRepository.existsById(id)) {
            return;
        }
        presetRepository.deleteById(id);
    }

    @Transactional("controlTransactionManager")
    public PresetView clone(long id) {
        LsLabelPreset src = presetRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프리셋을 찾을 수 없습니다."));
        String baseName = resolveCloneName(src.getPresetNm());
        // 복제본은 이벤트 매핑 미상속 — 이벤트 UNIQUE 충돌을 피하기 위해 null 로 생성.
        // 코드 목록(labelId/legacy 코드 포함)은 원본과 동일하게 복사한다.
        List<LabelCodeSpec> specs = src.getCodes().stream()
                .map(c -> new LabelCodeSpec(c.getLabelId(), c.getCode()))
                .toList();
        LsLabelPreset copy = LsLabelPreset.createWithOptions(baseName, src.getExpln(), specs, null);
        LsLabelPreset saved = presetRepository.save(copy);
        return toView(saved);
    }

    /**
     * 프리셋 이벤트 매핑값 검증 — 빈값(이벤트 무관 프리셋)은 허용, 비빈값은 유효 필터 키
     * ({@link EventTypeService#validFilterKeys()})여야 한다.
     *
     * <p>@Pattern(EVT_*) 정적 검증을 대체하는 동적 검증(CWE-20). 드롭다운에 노출되는 이벤트유형만
     * 프리셋 매핑을 허용하고, 그 밖의 값(구 EVT_* 코드·구 카테고리 키·임의 문자열)은 400 으로
     * 거부한다. ★축이 카테고리 → 유형으로 바뀌었으므로(V168) 기존에 저장된 카테고리 키는 V168 이
     * 대표 유형코드로 정정한다.
     */
    private void validateEventType(String eventTypeCd) {
        if (eventTypeCd == null || eventTypeCd.isBlank()) {
            return;
        }
        if (!eventTypeService.validFilterKeys().contains(eventTypeCd)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "지원하지 않는 이벤트 타입입니다");
        }
    }

    /**
     * 이벤트 매핑값 정규화 — 엔티티({@code LsLabelPreset.assignToEvent}) 와 동일하게 trim 후
     * 빈 문자열은 null 로 본다. "변경 여부" 비교를 저장 표현 기준으로 맞추기 위함.
     */
    private static String normalizeEventTypeCd(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * labelId 목록을 활성 마스터로 해석·검증한다(N+1 방지 위해 배치 1회 조회).
     *
     * <p>입력 순서를 보존하며 중복은 제거한다. 각 labelId 는 활성(USE_YN='Y') 마스터에 존재해야 하며,
     * 미존재/soft delete labelId 는 {@link ErrorCode#INVALID_INPUT}(400) 으로 거부한다.
     *
     * @return labelId → 마스터 응답 (join·응답 조립 재사용)
     */
    private Map<Long, LabelMasterResponse> resolveLabels(List<Long> labelIds) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        if (labelIds != null) {
            for (Long id : labelIds) {
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        Map<Long, LabelMasterResponse> masters = labelMasterService.findActiveByIds(ids);
        for (Long id : ids) {
            if (!masters.containsKey(id)) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "존재하지 않거나 비활성 라벨입니다: labelId=" + id);
            }
        }
        return masters;
    }

    /** labelId 목록 → 도메인 스펙(입력 순서 보존, null 제외). 코드 문자열(LBL_CD)은 저장하지 않는다. */
    private static List<LabelCodeSpec> toSpecs(List<Long> labelIds) {
        if (labelIds == null) {
            return List.of();
        }
        return labelIds.stream()
                .filter(java.util.Objects::nonNull)
                .map(id -> new LabelCodeSpec(id, null))
                .toList();
    }

    /** insert 시점 UNIQUE 위반(이벤트 중복) 을 CONFLICT 로 변환. */
    private LsLabelPreset saveWithEventUniqueGuard(LsLabelPreset preset) {
        try {
            return presetRepository.saveAndFlush(preset);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CONFLICT, MSG_EVENT_CONFLICT, e);
        }
    }

    /** 복제 이름 충돌 시 " (복사본 2)", " (복사본 3)" ... 식으로 시퀀스 부여. */
    private String resolveCloneName(String sourceName) {
        String candidate = sourceName + " (복사본)";
        if (!presetRepository.existsByPresetNm(candidate)) {
            return candidate;
        }
        for (int i = 2; i <= CLONE_SUFFIX_MAX; i++) {
            String alt = sourceName + " (복사본 " + i + ")";
            if (!presetRepository.existsByPresetNm(alt)) {
                return alt;
            }
        }
        throw new CustomException(ErrorCode.CONFLICT, "복제 이름 생성에 실패했습니다.");
    }

    /** 단건 프리셋 → 뷰. 코드가 참조하는 labelId 를 일괄 조회하여 마스터를 join 한다. */
    private PresetView toView(LsLabelPreset preset) {
        return toViews(List.of(preset)).get(0);
    }

    /** 마스터 맵이 이미 확보된 경우(create/update) 재사용해 뷰를 조립한다. */
    private static PresetView toView(LsLabelPreset preset, Map<Long, LabelMasterResponse> masters) {
        List<PresetCodeView> codeViews = new ArrayList<>(preset.getCodes().size());
        for (LsLabelPresetCode code : preset.getCodes()) {
            codeViews.add(toCodeView(code, masters));
        }
        return new PresetView(
                preset.getPresetId(),
                preset.getPresetNm(),
                preset.getExpln(),
                preset.getEventTypeCd(),
                preset.getRegDt(),
                preset.getMdfcnDt(),
                codeViews
        );
    }

    /** 프리셋 목록 → 뷰. 전체 코드의 labelId 를 한 번에 조회(배치)하여 코드-라벨 join N+1 을 방지한다. */
    private List<PresetView> toViews(List<LsLabelPreset> presets) {
        LinkedHashSet<Long> labelIds = new LinkedHashSet<>();
        for (LsLabelPreset preset : presets) {
            for (LsLabelPresetCode code : preset.getCodes()) {
                if (code.getLabelId() != null) {
                    labelIds.add(code.getLabelId());
                }
            }
        }
        Map<Long, LabelMasterResponse> masters = labelMasterService.findActiveByIds(labelIds);
        List<PresetView> views = new ArrayList<>(presets.size());
        for (LsLabelPreset preset : presets) {
            views.add(toView(preset, masters));
        }
        return views;
    }

    /** 코드 1건을 마스터 join 결과로 파생한다. 미연결이면 linked=false + legacy 코드 노출(AC4). */
    private static PresetCodeView toCodeView(LsLabelPresetCode code, Map<Long, LabelMasterResponse> masters) {
        LabelMasterResponse master = code.getLabelId() == null ? null : masters.get(code.getLabelId());
        boolean linked = master != null;
        if (!linked) {
            // 미연결(labelId null 또는 마스터 미존재/soft delete) — 오류 없이 legacy 코드로 노출.
            return new PresetCodeView(code.getLabelId(), code.getCode(), code.getCode(), null, false, false, false);
        }
        Optional<LabelGeometry> geometry = LabelGeometry.from(master.type());
        boolean bbox = geometry.map(LabelGeometry::bboxEnabled).orElse(false);
        boolean polygon = geometry.map(LabelGeometry::polygonEnabled).orElse(false);
        return new PresetCodeView(
                code.getLabelId(), code.getCode(), master.name(), master.type(), true, bbox, polygon);
    }
}
