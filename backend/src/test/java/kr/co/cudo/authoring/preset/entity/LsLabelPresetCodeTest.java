package kr.co.cudo.authoring.preset.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V117 — LsLabelPresetCode 의 코드/마스터 연결(LBL_ID) 동작 검증.
 *
 * <p>형태(BBOX/POLYGON) 스냅샷은 제거되었고 형태는 라벨 마스터 LBL_TYPE_CD 가 소유한다.
 * 패키지 가시성 팩토리({@code of(preset, code, sortOrder)})는 같은 패키지의 본 테스트에서 호출 가능하다.
 */
class LsLabelPresetCodeTest {

    @Test
    @DisplayName("프리셋_생성시_코드는_LBL_CD로_저장되고_LBL_ID는_미연결_null")
    void createdCodeStartsUnlinked() {
        LsLabelPreset preset = LsLabelPreset.create("기본", "", List.of("PERSON"), null);

        LsLabelPresetCode code = preset.getCodes().get(0);

        assertThat(code.getCode()).isEqualTo("PERSON");
        // V117: 마스터 연결(join)은 후속 Phase — 도메인 생성 시점엔 항상 미연결(null).
        assertThat(code.getLabelId()).isNull();
    }

    @Test
    @DisplayName("팩토리는_코드와_정렬순서를_그대로_저장하고_LBL_ID는_null")
    void factoryStoresCodeAndSortOrder() {
        LsLabelPreset preset = LsLabelPreset.create("프리셋", "", List.of(), null);

        LsLabelPresetCode person = LsLabelPresetCode.of(preset, "PERSON", 0);
        LsLabelPresetCode vehicle = LsLabelPresetCode.of(preset, "VEHICLE", 1);

        assertThat(person.getCode()).isEqualTo("PERSON");
        assertThat(person.getSortOrder()).isZero();
        assertThat(person.getLabelId()).isNull();
        assertThat(vehicle.getCode()).isEqualTo("VEHICLE");
        assertThat(vehicle.getSortOrder()).isEqualTo(1);
        assertThat(vehicle.getLabelId()).isNull();
    }

    @Test
    @DisplayName("updateSortOrder_는_정렬순서만_갱신한다")
    void updateSortOrderChangesOnlyOrder() {
        LsLabelPreset preset = LsLabelPreset.create("프리셋", "", List.of(), null);
        LsLabelPresetCode code = LsLabelPresetCode.of(preset, "PERSON", 0);

        code.updateSortOrder(5);

        assertThat(code.getSortOrder()).isEqualTo(5);
        assertThat(code.getCode()).isEqualTo("PERSON");
    }
}
