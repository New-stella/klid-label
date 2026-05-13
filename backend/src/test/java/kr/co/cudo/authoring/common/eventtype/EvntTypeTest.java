package kr.co.cudo.authoring.common.eventtype;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EvntTypeTest {

    @Test
    @DisplayName("정규_6_종_enum_존재_EVT_TRASH_없음")
    void enum_6_kind() {
        assertThat(EvntType.values()).hasSize(6);
        assertThat(EvntType.ofCode("EVT_TRASH")).isEmpty();
    }

    @Test
    @DisplayName("SoT_라벨_정확_매핑")
    void labels() {
        assertThat(EvntType.EVT_FALL.getLabel()).isEqualTo("쓰러짐");
        assertThat(EvntType.EVT_VIOLENCE.getLabel()).isEqualTo("폭력");
        assertThat(EvntType.EVT_ACCIDENT.getLabel()).isEqualTo("교통사고");
        assertThat(EvntType.EVT_ABNORMAL.getLabel()).isEqualTo("이상행동(유괴)");
        assertThat(EvntType.EVT_FLOOD.getLabel()).isEqualTo("침수");
        assertThat(EvntType.EVT_FIRE.getLabel()).isEqualTo("산불");
    }

    @Test
    @DisplayName("getCode_는_name_과_동일")
    void getCode_equals_name() {
        for (EvntType e : EvntType.values()) {
            assertThat(e.getCode()).isEqualTo(e.name());
        }
    }

    @Test
    @DisplayName("ofCode_정상_코드는_Optional_present")
    void ofCode_valid() {
        assertThat(EvntType.ofCode("EVT_FALL")).contains(EvntType.EVT_FALL);
        assertThat(EvntType.ofCode("EVT_FIRE")).contains(EvntType.EVT_FIRE);
        assertThat(EvntType.ofCode("EVT_ABNORMAL")).contains(EvntType.EVT_ABNORMAL);
        assertThat(EvntType.ofCode("EVT_FLOOD")).contains(EvntType.EVT_FLOOD);
    }

    @Test
    @DisplayName("ofCode_null_또는_매칭없으면_empty")
    void ofCode_invalid() {
        assertThat(EvntType.ofCode(null)).isEmpty();
        assertThat(EvntType.ofCode("")).isEmpty();
        assertThat(EvntType.ofCode("UNKNOWN")).isEmpty();
        assertThat(EvntType.ofCode("EVT_TRASH")).isEmpty();
        // case-sensitive
        assertThat(EvntType.ofCode("evt_fall")).isEmpty();
    }
}
