package kr.co.cudo.authoring.batch.policy;

import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.eventtype.service.EventTypeService;
import kr.co.cudo.authoring.label.domain.LabelGeometry;
import kr.co.cudo.authoring.label.dto.LabelMasterResponse;
import kr.co.cudo.authoring.label.service.LabelMasterService;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.entity.LsLabelPresetCode;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 이벤트 타입 → 프리셋 라벨 코드 매핑 조회 서비스.
 *
 * <p>V1.8 변경: 기존 {@code EventPresetMapping} 코드 상수가 제거되고,
 * 매핑은 LS_LABEL_PRESET.EVNT_TYPE_CD (UNIQUE) 컬럼으로 DB 화. 운영자가 프리셋 UI 에서
 * 동적으로 관리한다.
 *
 * <p>Phase 1 — 라벨별 BBOX/POLYGON 토글 조회({@link #togglesFor(String)}) 추가.
 *
 * <p>Phase 4a — 프리셋은 관제 <b>카테고리 키</b>(categoryKey = EVNT_CLS_CD+EVNT_CTGRY_CD, 예 "010001")
 * 단위로 저장된다. 반면 영상의 이벤트 코드({@code LsDataRaw.getEvntTypeCd()})는 <b>상세 EV-코드</b>
 * (예 EV01000102)다. 따라서 매칭 전 {@link EventTypeService#filterKeyOf(String)} 로 영상 EV-코드를
 * categoryKey 로 변환한 뒤 {@code findByEventTypeCd(categoryKey)} 로 조회한다. 변환 없이 EV-코드로
 * 직접 조회하면 프리셋이 절대 매칭되지 않는다.
 *
 * <p>Phase 3 — 오토라벨 경로 <b>마스터 형태 정합</b>. 프리셋 코드는 labelId(LS_LABEL FK)로 마스터를
 * 참조한다. togglesFor 는 코드의 labelId 를 {@link LabelMasterService#findActiveByIds(java.util.Collection)}
 * 로 <b>배치 조회</b>(N+1 금지)하여 다음을 만든다:
 * <ul>
 *   <li>키 = 마스터 <b>검출유형(DTCT_TYPE_CD, COCO 영문명)</b> 정규화({@link #normalizeLabelKey(String)})
 *       — 검출 라벨({@code d.label()}, COCO 축)과 동일 축이라 오토라벨 토글 조회·labelId 매칭이
 *       일원화된다(Phase 4). 한글 등 자유 라벨명은 COCO 영문명과 1:1 이 아니라 키로 쓸 수 없다.</li>
 *   <li>값 = 마스터 {@code LBL_TYPE_CD} → {@link LabelGeometry} 로 <b>강제 파생</b>한 토글
 *       (BBOX→bbox only, POLYGON→polygon only, POINT/SKELETON→도형 미적용).</li>
 * </ul>
 * 미연결(labelId=null) · 마스터 미존재/비활성(soft-delete) · 미매핑(dtctTypeCd=null) 참조 코드는
 * <b>제외</b>한다(오류 없이 스킵).
 *
 * <p><b>CO-014 — fail-open 폐기.</b> 구 반환형({@code Optional<Map<...>>})은 "왜 비었는가" 를 담지
 * 못해 호출자가 빈 값을 {@code AnnotationToggle.BOTH}(전량 저장)로 폴백했다. 그 결과 프리셋이 없거나
 * 실효하지 않는 상태가 <b>아무 증상 없이</b> 성공으로 종결됐다. 이제 {@link #resolve(String)} 이
 * 사유({@link PresetResolutionStatus})를 실은 {@link PresetResolution} 을 돌려주고, 호출자가
 * <b>보류 / 오토라벨 제외 / 수행</b> 을 구분한다. [@design ADR-054]
 *
 * <ul>
 *   <li>eventTypeCd 가 null/blank 또는 미등록 EV-코드 → {@link PresetResolutionStatus#EVENT_TYPE_UNREGISTERED}</li>
 *   <li>해당 키에 매핑된 프리셋 없음 → {@link PresetResolutionStatus#PRESET_ABSENT}</li>
 *   <li>프리셋은 있으나 담긴 라벨 0건 → {@link PresetResolutionStatus#PRESET_EMPTY}(오토라벨 제외 선언)</li>
 *   <li>담긴 코드가 전부 미연결이거나 활성 마스터 부재 → {@link PresetResolutionStatus#PRESET_UNLINKED}</li>
 *   <li>연결·활성 라벨은 있으나 전부 검출 클래스 미매핑 → {@link PresetResolutionStatus#PRESET_UNMAPPED}</li>
 *   <li>매핑 존재 → {@link PresetResolutionStatus#RESOLVED} + 마스터 검출유형(정규화) → 토글 맵</li>
 * </ul>
 *
 * <p>★<b>이 판정이 배치·프리셋 화면·이벤트 유형 화면이 공유하는 단일 진실원이다.</b> 다른 곳에서
 * 프리셋을 다시 조회해 실효성을 재유도하지 말 것.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresetLabelLookupService {

    private final LsLabelPresetRepository presetRepository;
    private final EventTypeService eventTypeService;
    private final LabelMasterService labelMasterService;

    /**
     * 오토라벨 검출축(COCO) 정규화 규칙 — 토글 맵 키(마스터 DTCT_TYPE_CD)·검출 라벨({@code d.label()})
     * 매칭이 공유하는 단일 소스.
     *
     * <p>trim + 소문자. 검출 라벨 매핑을 담당하는 {@link LabelMasterService#findLabelIdByDtctType(String)}
     * 와 동일한 COCO 축이 되도록 유지한다. 불일치 시 오토라벨 토글이 검출 라벨과 어긋나 검출이 무음
     * 드롭된다.
     *
     * @return 정규화된 키, 입력이 null 이면 null
     */
    public static String normalizeLabelKey(String raw) {
        return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 주어진 이벤트 타입의 오토라벨 프리셋을 해석한다 — <b>사유를 함께 돌려주는 단일 진실원</b>.
     * [@design ADR-054] [@design ADR-019] [@design ADR-034]
     *
     * <p>{@code YoloAutolabelStep} / {@code Sam2SegmentStep} 이 라벨별로 BBOX/POLYGON 저장 여부를 분기할 때,
     * 그리고 프리셋·이벤트 유형 관리 화면이 연결 상태를 표시할 때 <b>같은 이 메서드</b>를 쓴다.
     * 키는 마스터 검출유형(DTCT_TYPE_CD, COCO 축 정규화), 값은 마스터 형태에서 파생한 토글이다.
     *
     * <p>⚠ <b>빈 토글 맵을 「전 라벨 허용」으로 읽지 말 것</b> — 그 폴백이 CO-014 가 없앤 fail-open 이다.
     * 실효하지 않은 결과는 {@link PresetResolution#isWithheld()}(보류) 또는
     * {@link PresetResolution#isAutolabelExcluded()}(오토라벨 제외 선언)로 갈린다.
     *
     * @param eventTypeCd 영상의 상세 이벤트 EV-코드 (예: EV01000102). null/blank/미등록이면
     *                    {@link PresetResolutionStatus#EVENT_TYPE_UNREGISTERED}
     * @return 해석 결과 — 사유 + (실효 시) 마스터 검출유형(정규화) → 토글 매핑
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public PresetResolution resolve(String eventTypeCd) {
        if (eventTypeCd == null || eventTypeCd.isBlank()) {
            return PresetResolution.of(PresetResolutionStatus.EVENT_TYPE_UNREGISTERED);
        }
        // 영상 EV-코드 → 프리셋 저장 단위인 필터 키로 변환. 축이 유형(V168)이라 키와 코드가 같은 값이지만,
        //   <등록되지 않은 코드는 매칭 0건> 이라는 계약은 그대로다 — 미등록 코드로 프리셋을 찾지 않는다.
        //   ★그룹 축 주의: 프리셋은 그룹 대표코드에 걸리므로 EV-코드로 직접 조회하면 같은 표시명 그룹의
        //   비대표 유형이 전부 미보유로 잘못 판정된다. 변환을 건너뛰지 말 것.
        Optional<String> presetKey = eventTypeService.filterKeyOf(eventTypeCd);
        if (presetKey.isEmpty()) {
            return PresetResolution.of(PresetResolutionStatus.EVENT_TYPE_UNREGISTERED);
        }
        Optional<LsLabelPreset> preset = presetRepository.findByEventTypeCd(presetKey.get());
        if (preset.isEmpty()) {
            return PresetResolution.of(PresetResolutionStatus.PRESET_ABSENT);
        }
        List<LsLabelPresetCode> codes = preset.get().getCodes();
        if (codes == null || codes.isEmpty()) {
            // ★라벨을 하나도 담지 않은 프리셋 — 「이 유형은 오토라벨에서 뺀다」는 사람의 선언이다.
            //   아래 미연결·미매핑(사고)과 반드시 갈라야 한다. [@design AC-119]
            return PresetResolution.of(PresetResolutionStatus.PRESET_EMPTY);
        }

        // Phase 3: 프리셋 코드의 labelId 를 모아 마스터를 1회 배치 조회한다(N+1 금지).
        // 미연결(labelId=null) 레거시 코드는 매칭 축(마스터 DTCT_TYPE_CD)이 없으므로 제외한다.
        List<Long> labelIds = codes.stream()
                .map(LsLabelPresetCode::getLabelId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (labelIds.isEmpty()) {
            return PresetResolution.of(PresetResolutionStatus.PRESET_UNLINKED);
        }
        // findActiveByIds 는 활성(USE_YN='Y') 마스터만 반환 — 미존재/soft-delete 참조는 자연히 제외된다.
        Map<Long, LabelMasterResponse> masters = labelMasterService.findActiveByIds(labelIds);
        if (masters.isEmpty()) {
            // 연결은 있으나 그 연결이 가리키는 활성 마스터가 하나도 없다 — 끊어진 연결이라 미연결로 본다.
            return PresetResolution.of(PresetResolutionStatus.PRESET_UNLINKED);
        }

        // 프리셋 코드 순서(sortOrder ASC)를 보존하며 마스터 검출유형(DTCT_TYPE_CD) 키 토글 맵을 구성한다.
        LinkedHashMap<String, AnnotationToggle> map = new LinkedHashMap<>();
        for (LsLabelPresetCode code : codes) {
            Long labelId = code.getLabelId();
            if (labelId == null) {
                continue; // 미연결 레거시 코드 — 제외
            }
            LabelMasterResponse master = masters.get(labelId);
            if (master == null) {
                continue; // 미존재/비활성(soft-delete) — 제외
            }
            // Phase 4: 검출 귀속축을 COCO 매핑(DTCT_TYPE_CD)으로 통일한다. 온라인 경로
            // ({@code LabelMasterService#findLabelIdByDtctType})와 동일 축이라 배치 오토라벨 토글
            // 조회가 검출 라벨({@code d.label()}, COCO 영문명)과 정확히 맞물린다. 한글 등 자유 라벨명
            // ({@code master.name()})은 COCO 영문명과 1:1 이 보장되지 않아 토글 키로 쓸 수 없다.
            // 미매핑(dtctTypeCd=null/blank) 라벨은 애초에 AI 검출되지 않으므로 토글에서 제외한다
            // (null 키 삽입 금지).
            String key = normalizeLabelKey(master.dtctTypeCd());
            if (key == null || key.isEmpty()) {
                continue; // 미매핑 라벨 — 검출 불가, 토글 제외
            }
            Optional<LabelGeometry> geometry = LabelGeometry.from(master.type());
            if (geometry.isEmpty()) {
                // 방어적 가시화: 마스터 데이터 오염(미지원 형태 문자열)으로 코드가 조용히 제외되는 것을
                // 조기 발견한다. 마스터 CRUD API 검증이 4값을 강제하므로 정상 유입은 불가하나,
                // 오염 데이터가 소리 없이 드롭되면 원인 추적이 어렵다. 라벨명만 남기고 민감정보는 없다.
                log.warn("[Preset] unsupported label geometry — code excluded labelId={} name={} type={}",
                        labelId, LogSanitizer.sanitize(master.name()), LogSanitizer.sanitize(master.type()));
                continue; // 미지원 형태(방어) — 제외
            }
            // 형태 강제 파생: 마스터 LBL_TYPE_CD → bbox/polygon (매직값 없음).
            AnnotationToggle toggle = new AnnotationToggle(
                    geometry.get().bboxEnabled(), geometry.get().polygonEnabled());
            // 정규화 후 키 충돌(같은 COCO 검출유형을 참조하는 근사중복 코드) → 먼저 삽입된 코드가 이기고
            // 나머지 형태는 조용히 버려진다. V129 부분 유니크가 활성 마스터의 dtctTypeCd 중복을 막으므로
            // 정상 유입은 불가하나, 동작(첫 코드 우선)은 유지하되 어떤 키가 충돌해 어떤 코드가 드롭됐는지
            // 가시화한다(라벨명만, 민감정보 없음).
            AnnotationToggle previous = map.putIfAbsent(key, toggle);
            if (previous != null) {
                log.warn("[Preset] duplicate normalized label key — code dropped (first wins) "
                                + "key={} droppedLabelId={} droppedName={}",
                        LogSanitizer.sanitize(key), labelId, LogSanitizer.sanitize(master.name()));
            }
        }
        if (map.isEmpty()) {
            // 라벨은 골랐는데 전부 검출 클래스 미매핑 — 운영자는 필터를 걸었다고 믿는데 실제로는 안 걸린다.
            //   위 PRESET_EMPTY(선언)와 달리 <b>사고</b>이므로 보류한다. [@design AC-114]
            return PresetResolution.of(PresetResolutionStatus.PRESET_UNMAPPED);
        }
        return PresetResolution.resolved(map);
    }

    /**
     * 라벨별 어노테이션 토글 VO — 마스터 형태({@code LBL_TYPE_CD})에서 파생한다.
     *
     * <p>⚠ 구 상수 {@code BOTH}(=fail-safe 기본값)는 <b>제거됐다</b>. 그것이 프리셋을 특정하지 못했을 때
     * 전 검출을 저장하게 만든 fail-open 의 실체였다(CO-014). 되살리지 말 것 — 토글 맵에 없는 검출은
     * 「기본 허용」이 아니라 <b>저장하지 않는다</b>가 계약이다. [@design ADR-054]
     */
    public record AnnotationToggle(boolean bbox, boolean polygon) {
    }
}
