package kr.co.cudo.authoring.batch.policy;

import kr.co.cudo.authoring.batch.policy.PresetLabelLookupService.AnnotationToggle;
import kr.co.cudo.authoring.eventtype.service.EventTypeCacheEvictor;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset.LabelCodeSpec;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 오토라벨 프리셋 매칭의 <b>표시명 그룹</b> 회귀 가드 (2026-08-05 · R5).
 *
 * <h3>왜 IT 인가 (Critical)</h3>
 * <p>{@code PresetLabelLookupServiceTest} 는 {@code eventTypeService} 를 mock 해 "EV-코드가 어떤 키로
 * 접히는지"를 스텁으로 정해 준다. 즉 <b>그룹 접기가 실제로 일어나는지</b>는 검증 밖이다. 이 IT 는
 * 실제 {@code EventTypeService} + 실제 프리셋 저장소 조합으로 사용자 확정 정책(R5)을 고정한다 —
 * <b>대표코드에 저장된 프리셋 1건이 그룹 내 모든 영상에 적용된다.</b>
 *
 * <p>이 안전망이 없으면 {@code filterKeyOf} 가 원문 코드를 그대로 돌려주도록 되돌아가도 아무 테스트가
 * 깨지지 않고, 비대표 코드 영상의 오토라벨만 조용히 프리셋 미적용(전체 라벨 허용)으로 떨어진다.
 *
 * <h3>격리</h3>
 * <p>이벤트유형·라벨·프리셋 모두 이 클래스 전용 값으로 심고 되돌린다. 프리셋
 * {@code EVNT_TYPE_CD} 에는 UNIQUE 제약이 있어 공유 시드 코드를 빌려 쓰면 다른 테스트와 충돌한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class PresetLabelLookupGroupMatchingIT {

    /** 표시명이 같은 2종 — 대표코드는 그룹 내 최소 코드인 {@link #GROUP_REPRESENTATIVE} 다. */
    private static final String GROUP_REPRESENTATIVE = "EV93000101";
    private static final String GROUP_MEMBER = "EV93000102";
    private static final String GROUP_LABEL = "침수-프리셋IT";

    /** 표시명이 다른 독립 유형 — 그룹 경계를 넘어 프리셋이 새는지 보는 음성 케이스. */
    private static final String OTHER_GROUP = "EV93000201";

    private static final List<String> SEEDED_TYPE_CODES =
            List.of(GROUP_REPRESENTATIVE, GROUP_MEMBER, OTHER_GROUP);

    /** 검출유형(COCO 축) — 활성 라벨 부분 유니크(V129)와 충돌하지 않도록 이 클래스 전용 값. */
    private static final String DTCT_TYPE_CD = "itpresetgrp";

    @Autowired private PresetLabelLookupService presetLabelLookupService;
    @Autowired private LsLabelRepository labelRepository;
    @Autowired private LsLabelPresetRepository presetRepository;
    @Autowired private EventTypeCacheEvictor eventTypeCacheEvictor;

    private final JdbcTemplate jdbc;

    private Long seededLabelId;
    private Long seededPresetId;

    PresetLabelLookupGroupMatchingIT(@Qualifier("controlDataSource") DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        seedEventType(GROUP_REPRESENTATIVE, GROUP_LABEL);
        seedEventType(GROUP_MEMBER, GROUP_LABEL);
        seedEventType(OTHER_GROUP, "화재-프리셋IT");
        // 장수명(6h) 캐시라 방금 심은 마스터가 반영되려면 비워야 한다.
        eventTypeCacheEvictor.evictNow();

        LsLabel label = labelRepository.saveAndFlush(LsLabel.create(
                "프리셋그룹IT-" + System.nanoTime(), "#112233", "BBOX", 0, DTCT_TYPE_CD, "tester"));
        seededLabelId = label.getLabelId();

        // 프리셋은 <대표코드>에만 저장한다 — 그룹 전체 적용은 filterKeyOf 의 접기가 만들어야 한다.
        LsLabelPreset preset = presetRepository.saveAndFlush(LsLabelPreset.createWithOptions(
                List.of(new LabelCodeSpec(seededLabelId, null)), GROUP_REPRESENTATIVE));
        seededPresetId = preset.getPresetId();
    }

    @AfterEach
    void tearDown() {
        if (seededPresetId != null) {
            jdbc.update("DELETE FROM LS_LABEL_PRESET_CODE WHERE PRESET_ID = ?", seededPresetId);
            jdbc.update("DELETE FROM LS_LABEL_PRESET WHERE PRESET_ID = ?", seededPresetId);
        }
        if (seededLabelId != null) {
            jdbc.update("DELETE FROM LS_LABEL WHERE LBL_ID = ?", seededLabelId);
        }
        for (String code : SEEDED_TYPE_CODES) {
            jdbc.update("DELETE FROM LS_EVNT_TYPE WHERE EVNT_TYPE_CD = ?", code);
        }
        eventTypeCacheEvictor.evictNow();
    }

    private void seedEventType(String code, String name) {
        jdbc.update("INSERT INTO LS_EVNT_TYPE "
                        + "(EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD, EVNT_CTGRY_CD, CLCT_YN) "
                        + "VALUES (?, ?, '93', '0001', 'Y') "
                        + "ON CONFLICT (EVNT_TYPE_CD) DO UPDATE "
                        + "SET EVNT_NM = EXCLUDED.EVNT_NM, EVNT_CLSF_CD = EXCLUDED.EVNT_CLSF_CD, "
                        + "    EVNT_CTGRY_CD = EXCLUDED.EVNT_CTGRY_CD, CLCT_YN = 'Y'",
                code, name);
    }

    // ------------------------------------------------------------------- tests

    @Test
    @DisplayName("그룹_비대표코드_영상도_대표코드에_저장된_프리셋에_매칭된다")
    void groupMemberMatchesPresetStoredOnRepresentative() {
        // when — 영상이 가진 값은 비대표 코드다(실제 인입 형태)
        PresetResolution resolution = presetLabelLookupService.resolve(GROUP_MEMBER);

        // then — filterKeyOf 가 대표코드로 접지 않으면 프리셋을 못 찾아 보류 사유가 된다
        assertThat(resolution.isResolved())
                .as("[req: R5] 대표코드 프리셋 1건이 그룹 전체에 적용돼야 한다")
                .isTrue();
        Map<String, AnnotationToggle> toggles = resolution.toggles();
        assertThat(toggles).containsOnlyKeys(DTCT_TYPE_CD);
        assertThat(toggles.get(DTCT_TYPE_CD).bbox()).isTrue();
        assertThat(toggles.get(DTCT_TYPE_CD).polygon()).isFalse();
    }

    @Test
    @DisplayName("대표코드_영상도_같은_프리셋에_매칭된다")
    void representativeItselfMatchesPreset() {
        PresetResolution resolution = presetLabelLookupService.resolve(GROUP_REPRESENTATIVE);

        assertThat(resolution.isResolved()).isTrue();
        assertThat(resolution.toggles()).containsOnlyKeys(DTCT_TYPE_CD);
    }

    @Test
    @DisplayName("다른_표시명_그룹의_코드는_프리셋에_매칭되지_않는다")
    void otherGroupDoesNotMatchPreset() {
        // 표시명이 다른데도 매칭되면 그룹 경계가 무너져 프리셋이 무관한 영상까지 좁히게 된다.
        PresetResolution other = presetLabelLookupService.resolve(OTHER_GROUP);
        assertThat(other.isResolved()).isFalse();
        assertThat(other.status()).isEqualTo(PresetResolutionStatus.PRESET_ABSENT);
    }
}
