package kr.co.cudo.authoring.preset.service;

import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService;
import kr.co.cudo.authoring.batch.policy.PresetResolution;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.eventtype.policy.EventTypeDisplayNamePolicy;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.label.domain.LabelGeometry;
import kr.co.cudo.authoring.label.dto.LabelMasterResponse;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.preset.dto.PresetCodeView;
import kr.co.cudo.authoring.preset.dto.PresetView;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset.LabelCodeSpec;
import kr.co.cudo.authoring.preset.entity.LsLabelPresetCode;
import kr.co.cudo.authoring.preset.event.PresetLabelsChangedEvent;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 프리셋 도메인 응용 서비스 — 프리셋은 <b>이벤트유형 1건 + 라벨 목록</b> 둘로 이루어진다.
 *
 * <p>입력 검증(이벤트유형 필수·등록 여부·이벤트 중복·labelId 유효성)은 본 서비스에서 수행하고,
 * 도메인 상태 변경은 Aggregate Root({@link LsLabelPreset}) 의 정적 팩토리/도메인 메서드에 위임한다.
 *
 * <p>프리셋 코드는 <b>labelId 기반</b>으로 지정된다. 라벨명·형태는 스냅샷을 저장하지 않고
 * 조회 시 라벨 마스터(LS_LABEL)를 실시간 join 하여 파생한다(N+1 방지 위해 labelId 일괄 조회).
 * <ul>
 *   <li>create/update: 각 labelId 가 활성(USE_YN='Y') 마스터에 존재해야 한다 — 미존재/soft delete 는 400.</li>
 *   <li>조회 응답: labelId 로 마스터를 join 하여 라벨명·형태 토글({@link LabelGeometry})을 파생.</li>
 *   <li>미연결 코드(labelId null): 오류 없이 linked=false + legacy 코드 문자열로 노출.</li>
 * </ul>
 *
 * <p>이벤트 중복(한 이벤트에 프리셋 둘)은 <b>두 겹</b>으로 막는다 — ① 입구의 사전 조회(어느
 * 이벤트인지 메시지에 담아 409) ② 저장 시점의 UNIQUE 제약 위반을
 * {@link DataIntegrityViolationException} → {@link ErrorCode#CONFLICT} 로 변환(동시 요청 경합).
 * 프리셋에 이름이 없어져 사용자가 중복을 눈으로 알아채기 어려우므로 입구 검사가 필요하고,
 * 사전 조회만으로는 경합을 못 막으므로 둘 다 있어야 한다.
 *
 * <p><b>CO-014 — 실효성 노출과 보류 재개.</b> 조회 응답은 프리셋마다 실효 여부({@code effective})를,
 * 라벨마다 검출 클래스 매핑 코드({@code dtctTypeCd})를 함께 싣는다. 담긴 라벨이 전부 미매핑이면 프리셋이
 * 존재해도 그 이벤트 유형의 오토라벨링은 보류되는데, 그 사실이 조회에 드러나지 않으면 운영자는 프리셋을
 * 등록해 놓고도 왜 적용되지 않는지 알 수 없다. 등록·수정은 저장을 거부하지 않고 그 사실만 알린다.
 * [@design ADR-054] [@design AC-118]
 *
 * <p>★실효 판정은 {@link PresetLabelLookupService#resolve(String)} <b>한 곳</b>에서만 한다 — 오토라벨
 * 보류 판정과 같은 판정이며 프리셋 도메인이 다시 유도하지 않는다. 재유도하면 화면과 배치가 조용히 갈라져
 * "화면은 실효한다는데 오토라벨은 안 돈다"가 된다(이 저장소의 반복 결함).
 *
 * @design API-037
 * @design API-038
 * @design API-039
 * @design SEQ-022
 * @design ADR-054
 * @design AC-115
 * @design AC-118
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresetService {

    private static final String MSG_EVENT_REQUIRED = "이벤트유형은 필수입니다";
    private static final String MSG_EVENT_UNSUPPORTED = "지원하지 않는 이벤트 타입입니다";
    private static final String MSG_EVENT_CONFLICT_PREFIX = "이미 프리셋이 등록된 이벤트입니다: ";
    private static final String MSG_EVENT_CONFLICT = "이미 다른 프리셋에 매핑된 이벤트입니다";

    private final LsLabelPresetRepository presetRepository;
    private final EventTypeService eventTypeService;
    private final LabelMasterService labelMasterService;
    /**
     * 실효 판정의 단일 진실원 — 오토라벨 보류 판정과 <b>같은 판정</b>을 재사용한다. [@design ADR-054]
     *
     * <p>필드를 <b>맨 뒤</b>에 둔다: {@code @RequiredArgsConstructor} 가 선언 순서로 생성자를 만들므로
     * 중간에 넣으면 위치 인자를 쓰는 기존 테스트가 조용히 어긋난다.
     */
    private final PresetLabelLookupService presetLabelLookupService;
    /** 보류된 오토라벨의 재개 트리거 발행처. 필드 위치 이유는 위와 같다. */
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<PresetView> list() {
        return toViews(presetRepository.findAllWithCodes());
    }

    /**
     * 프리셋 생성. 이벤트유형은 필수이며 <b>등록된 전체 이벤트유형</b>이어야 한다.
     *
     * @param labelIds    담을 라벨 마스터 PK 목록 (1~20, 활성 마스터여야 한다)
     * @param eventTypeCd 대상 이벤트유형 코드 (필수)
     */
    @Transactional("controlTransactionManager")
    public PresetView create(List<Long> labelIds, String eventTypeCd) {
        String normalized = requireRegisteredEventType(eventTypeCd);
        if (presetRepository.existsByEventTypeCd(normalized)) {
            throw new CustomException(ErrorCode.CONFLICT, eventConflictMessage(normalized));
        }
        Map<Long, LabelMasterResponse> masters = resolveLabels(labelIds);
        LsLabelPreset preset = LsLabelPreset.createWithOptions(toSpecs(labelIds), normalized);
        LsLabelPreset saved = saveWithEventUniqueGuard(preset);
        // 저장 성공 = 그 이벤트 유형에 보류돼 있던 오토라벨의 재개 트리거. 소비자가 AFTER_COMMIT 이라
        //   커밋 뒤에 실행되고, 응답을 지연시키지도 저장을 되돌리지도 않는다.
        publishLabelsChanged(saved.getEventTypeCd());
        return toView(saved, masters, eventNameOf(saved.getEventTypeCd()), isEffective(saved));
    }

    /**
     * 프리셋 수정. 이벤트 검증은 <b>매핑값이 실제로 바뀔 때만</b> 수행한다.
     *
     * <p>제외 대분류 코드가 시스템 설정으로 동적화되면서 어떤 이벤트유형이 나중에 제외 대상이 될 수
     * 있는데, 매번 재검증하면 그 유형에 이미 매핑된 <b>기존 프리셋</b>이 라벨만 고치려 해도 통째로
     * 편집 불가가 된다(400). 매핑을 유지한 편집은 허용하고, <b>재매핑</b>은 계속 검증한다.
     * 신규 생성({@link #create})은 항상 검증한다.
     */
    @Transactional("controlTransactionManager")
    public PresetView update(long id, List<Long> labelIds, String eventTypeCd) {
        LsLabelPreset preset = presetRepository.findById(id)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "프리셋을 찾을 수 없습니다."));
        String normalized = normalizeEventTypeCd(eventTypeCd);
        if (!Objects.equals(normalized, preset.getEventTypeCd())) {
            normalized = requireRegisteredEventType(eventTypeCd);
            // 자기 자신을 제외하고 그 이벤트를 이미 쓰는 프리셋이 있는지 본다.
            if (presetRepository.existsByEventTypeCdAndPresetIdNot(normalized, id)) {
                throw new CustomException(ErrorCode.CONFLICT, eventConflictMessage(normalized));
            }
        } else if (normalized == null) {
            // 저장값도 요청값도 비어 있는 경우 — 컬럼이 NOT NULL 이라 통과시키면 DB 오류로 샌다.
            throw new CustomException(ErrorCode.INVALID_INPUT, MSG_EVENT_REQUIRED);
        }
        Map<Long, LabelMasterResponse> masters = resolveLabels(labelIds);
        preset.replaceCodes(toSpecs(labelIds));
        preset.assignToEvent(normalized);
        // dirty-checking 으로 flush 시 UNIQUE 위반 가능 → 명시적 flush 로 throw 위치를 본 메서드 안으로 끌어온다.
        try {
            presetRepository.saveAndFlush(preset);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CONFLICT, MSG_EVENT_CONFLICT, e);
        }
        // 수정은 「매핑된 라벨이 없던 무효 프리셋에 매핑된 라벨을 넣는」 동선이라 재개가 실제로 일어나는
        //   주요 경로다. 재매핑이면 <b>새</b> 이벤트 유형으로 발행한다 — 떠난 유형은 프리셋이 사라진 셈이라
        //   오히려 보류 조건이 성립해 깨울 것이 없다(삭제를 발행하지 않는 것과 같은 이유).
        publishLabelsChanged(preset.getEventTypeCd());
        return toView(preset, masters, eventNameOf(preset.getEventTypeCd()), isEffective(preset));
    }

    /**
     * 프리셋 삭제(멱등). <b>재개 트리거를 발행하지 않는다</b> — 프리셋이 사라지면 오히려 보류 조건이
     * 성립하므로, 발행하면 재개 러너가 돌았다가 스스로 다시 보류하는 헛일이 된다. [@design ADR-054]
     */
    @Transactional("controlTransactionManager")
    public void delete(long id) {
        if (!presetRepository.existsById(id)) {
            return;
        }
        presetRepository.deleteById(id);
    }

    /**
     * 이벤트유형 검증 — <b>비어 있으면 400</b>, 그리고 <b>등록된 전체 유형</b>이 아니면 400.
     *
     * <p>★판정 축은 필터 옵션({@code validFilterKeys})이 아니라 <b>등록 여부</b>
     * ({@link EventTypeService#registeredCodes()})다. 제외 대분류 설정은 <b>필터 드롭다운에서 감추는
     * 것</b>일 뿐 그 유형의 영상이 안 들어온다는 뜻이 아니다 — 영상이 들어오면 오토라벨이 돌아야
     * 하고 그러려면 그 유형의 프리셋을 만들 수 있어야 한다. 필터 축으로 검증하면 화면에서 고를 수
     * 있는 값을 서버가 400 으로 거부한다.
     *
     * <p>목록 필터·화면 옵션 쪽 검증은 여전히 필터 옵션 축이 맞다 — 두 축을 통일하지 않는다.
     *
     * @return 정규화된 이벤트유형 코드 (trim)
     */
    private String requireRegisteredEventType(String eventTypeCd) {
        String normalized = normalizeEventTypeCd(eventTypeCd);
        if (normalized == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT, MSG_EVENT_REQUIRED);
        }
        if (!eventTypeService.registeredCodes().contains(normalized)) {
            throw new CustomException(ErrorCode.INVALID_INPUT, MSG_EVENT_UNSUPPORTED);
        }
        return normalized;
    }

    /**
     * 이벤트 중복 409 메시지 — <b>어느 이벤트인지</b> 표시명과 코드로 밝힌다.
     *
     * <p>여기 실리는 코드는 {@link #requireRegisteredEventType} 를 통과한 <b>등록된 코드</b>이므로
     * 임의 사용자 입력이 그대로 반향되지 않는다.
     */
    private String eventConflictMessage(String eventTypeCd) {
        String name = eventNameOf(eventTypeCd);
        return MSG_EVENT_CONFLICT_PREFIX + name + "(" + eventTypeCd + ")";
    }

    /**
     * 이벤트유형 매핑값 정규화 — 엔티티({@code LsLabelPreset.assignToEvent}) 와 동일하게 trim 후
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
                .filter(Objects::nonNull)
                .map(id -> new LabelCodeSpec(id, null))
                .toList();
    }

    /**
     * 보류된 오토라벨의 재개 트리거 발행 — <b>등록·수정에서만</b> 부른다. [@design ADR-054] [@design AC-115]
     *
     * <p>소비자는 {@code AFTER_COMMIT} 리스너라 프리셋이 커밋된 뒤에 실행된다(커밋 전에 돌면 아직 보이지
     * 않는 프리셋을 조회해 스스로 다시 보류한다). 재개 자체는 별도 스레드가 수행하므로 이 호출은 응답을
     * 지연시키지 않는다.
     *
     * <p>발행 실패가 <b>프리셋 저장을 되돌려서는 안 된다</b>(이벤트 계약). 발행은 트랜잭션 동기화 등록이라
     * 정상 경로에서 실패하지 않지만, 동기 리스너가 늘어나면 그쪽 예외가 여기로 전파되어 저장이 롤백될 수
     * 있으므로 경계를 여기서 막는다 — 재개는 못 해도 등록은 남아야 하고, 남은 등록은 다음 수정에서 다시
     * 촉발된다.
     */
    private void publishLabelsChanged(String eventTypeCd) {
        try {
            eventPublisher.publishEvent(new PresetLabelsChangedEvent(eventTypeCd));
        } catch (RuntimeException e) {
            log.warn("[Preset] autolabel resume trigger publish failed — preset save is kept. evntTypeCd={}",
                    eventTypeCd, e);
        }
    }

    /**
     * 이 프리셋이 실효하는가 — 담긴 라벨 중 AI 검출 클래스에 매핑된 것이 하나라도 있는가.
     * [@design ADR-054] [@design AC-118]
     *
     * <p>★판정은 {@link PresetLabelLookupService#resolve(String)} 를 <b>재사용</b>한다. 프리셋 도메인이
     * 라벨 마스터를 다시 훑어 재유도하면 배치의 보류 판정과 갈라져 화면이 거짓말을 한다.
     *
     * <p>판정 축은 프리셋의 이벤트 유형 코드이며, 그 안에서 <b>그룹 대표코드</b>로 변환된 뒤 조회된다
     * (오토라벨이 영상 EV-코드를 다루는 방식과 같은 축). 따라서 비대표 코드에 저장된 레거시 프리셋은
     * 실효하지 않는 것으로 나오는데, 그것이 사실이다 — 배치도 그 프리셋에 닿지 않는다.
     */
    private boolean isEffective(LsLabelPreset preset) {
        PresetResolution resolution = presetLabelLookupService.resolve(preset.getEventTypeCd());
        return resolution != null && resolution.isResolved();
    }

    /** insert 시점 UNIQUE 위반(이벤트 중복) 을 CONFLICT 로 변환. */
    private LsLabelPreset saveWithEventUniqueGuard(LsLabelPreset preset) {
        try {
            return presetRepository.saveAndFlush(preset);
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.CONFLICT, MSG_EVENT_CONFLICT, e);
        }
    }

    /**
     * 이벤트유형 표시명 해석 — 단건 경로(create/update/충돌 메시지)용.
     *
     * <p>★해석은 {@link EventTypeService#resolveLabel(String)} 에 위임한다. 4단 폴백(운영자 표시명 →
     * 관제 수신 유형명 → 카테고리명 → 유형코드)의 판정은 {@link EventTypeDisplayNamePolicy} 한 곳에만
     * 있어야 하며, 여기서 재유도하면 화면·산출물이 조용히 갈라진다.
     *
     * <p>그 맵은 <b>등록된 전체 유형</b>(수집/비수집·제외 대분류 무관)을 담으므로 필터 옵션에서
     * 감춰진 유형도 이름이 채워진다 — 노출 옵션 목록으로 역해석하면 그 코드가 코드 그대로 노출된다.
     */
    private String eventNameOf(String eventTypeCd) {
        return eventTypeService.resolveLabel(eventTypeCd);
    }

    /** 마스터 맵·이벤트 표시명·실효 판정이 이미 확보된 경우 재사용해 뷰를 조립한다. */
    private static PresetView toView(LsLabelPreset preset,
                                     Map<Long, LabelMasterResponse> masters,
                                     String eventTypeNm,
                                     boolean effective) {
        List<PresetCodeView> codeViews = new ArrayList<>(preset.getCodes().size());
        for (LsLabelPresetCode code : preset.getCodes()) {
            codeViews.add(toCodeView(code, masters));
        }
        return new PresetView(
                preset.getPresetId(),
                preset.getEventTypeCd(),
                eventTypeNm,
                preset.getRegDt(),
                preset.getMdfcnDt(),
                codeViews,
                effective
        );
    }

    /**
     * 프리셋 목록 → 뷰. 두 축을 각각 <b>1회씩 배치 조회</b>해 N+1 을 막는다 —
     * ① 전체 코드의 labelId → 라벨 마스터 ② 등록 유형 전체의 코드 → 이벤트 표시명.
     */
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
        // 등록된 전체 유형의 표시명 맵을 1회만 가져온다(프리셋마다 개별 조회 금지).
        //   이름이 없는 유형은 맵에 담기지 않으며, 그때의 원문(코드) 폴백은 codeLabelMap 이 명시한
        //   소비 계약이다 — 4단 폴백 자체를 여기서 다시 구현하지 않는다.
        Map<String, String> eventNames = eventTypeService.codeLabelMap();
        List<PresetView> views = new ArrayList<>(presets.size());
        for (LsLabelPreset preset : presets) {
            String code = preset.getEventTypeCd();
            views.add(toView(preset, masters,
                    code == null ? null : eventNames.getOrDefault(code, code),
                    isEffective(preset)));
        }
        return views;
    }

    /** 코드 1건을 마스터 join 결과로 파생한다. 미연결이면 linked=false + legacy 코드 노출. */
    private static PresetCodeView toCodeView(LsLabelPresetCode code, Map<Long, LabelMasterResponse> masters) {
        LabelMasterResponse master = code.getLabelId() == null ? null : masters.get(code.getLabelId());
        boolean linked = master != null;
        if (!linked) {
            // 미연결(labelId null 또는 마스터 미존재/soft delete) — 오류 없이 legacy 코드로 노출.
            // 미연결이면 마스터가 없어 검출 클래스 매핑도 알 수 없다 — dtctTypeCd 는 null.
            return new PresetCodeView(
                    code.getLabelId(), code.getCode(), code.getCode(), null, false, false, false, null);
        }
        Optional<LabelGeometry> geometry = LabelGeometry.from(master.type());
        boolean bbox = geometry.map(LabelGeometry::bboxEnabled).orElse(false);
        boolean polygon = geometry.map(LabelGeometry::polygonEnabled).orElse(false);
        // 검출 클래스 매핑은 마스터가 소유한다 — 프리셋은 스냅샷하지 않고 실시간 join 값을 그대로 싣는다.
        return new PresetCodeView(
                code.getLabelId(), code.getCode(), master.name(), master.type(), true, bbox, polygon,
                master.dtctTypeCd());
    }
}
