package kr.co.cudo.authoring.video.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class LsDataRawTest {

    @Test
    @DisplayName("createFromAugment_메타_계승_PENDING_상태_parentRawSn_참조")
    void createFromAugmentInheritsMetaAndSetsParent() throws Exception {
        // given
        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-1", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/storage/raw/1.mp4", null, 120);
        setField(parent, "rawSn", 100L);

        // when
        LsDataRaw augmented = LsDataRaw.createFromAugment(parent, "/storage/augment/winter.mp4", "WINTER");

        // then — 원본 메타 계승
        assertThat(augmented.getVmsCctvId()).isEqualTo("cctv-1");
        assertThat(augmented.getEvntTypeCd()).isEqualTo("EVT");
        assertThat(augmented.getLclgvCd()).isEqualTo("GOV");
        assertThat(augmented.getPrvcTypeCd()).isEqualTo(LsDataRaw.PRVC_TYPE_PRVC);
        assertThat(augmented.getPrvcYn()).isEqualTo("Y");
        assertThat(augmented.getDurationSec()).isEqualTo(120);

        // then — 새 영상 고유 속성
        assertThat(augmented.getParentRawSn()).isEqualTo(100L);
        assertThat(augmented.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);
        assertThat(augmented.getRawFilePathNm()).isEqualTo("/storage/augment/winter.mp4");
        assertThat(augmented.getDeIdntfYn()).isEqualTo("N");
        assertThat(augmented.getVmsClipId()).contains("clip-1");
        assertThat(augmented.getVmsClipId()).contains("AUG_WINTER");
        assertThat(augmented.getRegDt()).isNotNull();

        // then — rawSn 미할당 (DB 가 AUTO_INCREMENT)
        assertThat(augmented.getRawSn()).isNull();
    }

    @Test
    @DisplayName("createFromResolution_PENDING_parentRawSn_결정론적_VMS_CLIP_ID")
    void createFromResolutionDeterministicClipId() throws Exception {
        // given
        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-1", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/1.mp4", null, 60);
        setField(parent, "rawSn", 100L);

        // when
        LsDataRaw resized = LsDataRaw.createFromResolution(parent, "/storage/raw/resolution/100/P50/1.mp4", "P50");

        // then — 결정론적 clip id (timestamp 미포함)
        assertThat(resized.getVmsClipId()).isEqualTo("clip-1_RES_P50");
        assertThat(resized.getParentRawSn()).isEqualTo(100L);
        assertThat(resized.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);
        assertThat(resized.getRawFilePathNm()).isEqualTo("/storage/raw/resolution/100/P50/1.mp4");
        assertThat(resized.getVmsCctvId()).isEqualTo("cctv-1");
        assertThat(resized.getDurationSec()).isEqualTo(60);
        assertThat(resized.getRawSn()).isNull();

        // then — 동일 parent+preset 재호출 시 동일 clip id (중복 식별 가능)
        LsDataRaw again = LsDataRaw.createFromResolution(parent, "/x.mp4", "P50");
        assertThat(again.getVmsClipId()).isEqualTo(resized.getVmsClipId());
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
