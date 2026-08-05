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

import java.util.Collections;
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
 * <ul>
 *   <li>eventTypeCd 가 null/blank → {@link Optional#empty()} (호출자 fail-safe: 필터 미적용)</li>
 *   <li>관제 미등록 EV-코드(categoryKey 변환 실패) → {@link Optional#empty()} (fail-safe)</li>
 *   <li>해당 카테고리에 매핑된 프리셋이 없음 → {@link Optional#empty()} (호출자 fail-safe)</li>
 *   <li>연결·활성 라벨이 하나도 없음 → {@link Optional#empty()} (호출자 fail-safe)</li>
 *   <li>매핑 존재 → 마스터 검출유형(DTCT_TYPE_CD, 정규화) → 토글 맵</li>
 * </ul>
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
     * 주어진 이벤트 타입에 매핑된 라벨 → 어노테이션 토글 맵을 반환한다 (Phase 1 / Phase 3 재구성).
     *
     * <p>YoloAutolabelStep / Sam2SegmentStep 이 라벨별로 BBOX/POLYGON 저장 여부를 분기할 때 사용한다.
     * 키는 마스터 검출유형(DTCT_TYPE_CD, COCO 축 정규화), 값은 마스터 형태에서 파생한 토글이다.
     *
     * @param eventTypeCd 영상의 상세 이벤트 EV-코드 (예: EV01000102). null/blank/미등록/미매핑 시 빈 Optional.
     * @return 마스터 검출유형(DTCT_TYPE_CD, 정규화) → 토글 매핑. 빈 Optional 이면 필터 미적용 (호출자 default BOTH).
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public Optional<Map<String, AnnotationToggle>> togglesFor(String eventTypeCd) {
        if (eventTypeCd == null || eventTypeCd.isBlank()) {
            return Optional.empty();
        }
        // 영상 EV-코드 → 프리셋 저장 단위인 필터 키로 변환 (미등록 코드는 fail-safe 빈 Optional).
        //   축이 유형(V168)이라 키와 코드가 같은 값이지만, <등록되지 않은 코드는 매칭 0건> 이라는
        //   fail-safe 계약은 그대로다 — 미등록 코드로 프리셋을 찾지 않는다.
        Optional<String> presetKey = eventTypeService.filterKeyOf(eventTypeCd);
        if (presetKey.isEmpty()) {
            return Optional.empty();
        }
        Optional<LsLabelPreset> preset = presetRepository.findByEventTypeCd(presetKey.get());
        if (preset.isEmpty()) {
            return Optional.empty();
        }
        List<LsLabelPresetCode> codes = preset.get().getCodes();

        // Phase 3: 프리셋 코드의 labelId 를 모아 마스터를 1회 배치 조회한다(N+1 금지).
        // 미연결(labelId=null) 레거시 코드는 매칭 축(마스터 DTCT_TYPE_CD)이 없으므로 제외한다.
        List<Long> labelIds = codes.stream()
                .map(LsLabelPresetCode::getLabelId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (labelIds.isEmpty()) {
            return Optional.empty();
        }
        // findActiveByIds 는 활성(USE_YN='Y') 마스터만 반환 — 미존재/soft-delete 참조는 자연히 제외된다.
        Map<Long, LabelMasterResponse> masters = labelMasterService.findActiveByIds(labelIds);
        if (masters.isEmpty()) {
            return Optional.empty();
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
            return Optional.empty();
        }
        return Optional.of(Collections.unmodifiableMap(map));
    }

    /**
     * 라벨별 어노테이션 토글 VO.
     *
     * <p>{@link #BOTH} 는 fail-safe 기본값 — 호출자가 옵션 미설정 라벨을 만나면 사용한다.
     */
    public record AnnotationToggle(boolean bbox, boolean polygon) {

        /** 기본값(BBOX + POLYGON 모두 활성) — fail-safe. */
        public static final AnnotationToggle BOTH = new AnnotationToggle(true, true);
    }
}
