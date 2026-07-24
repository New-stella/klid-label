package kr.co.cudo.authoring.video.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LsDataRawTest {

    @Test
    @DisplayName("createFromAugment_메타_계승_PENDING_상태_orgnlRawSn_참조")
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
        assertThat(augmented.getOrgnlRawSn()).isEqualTo(100L);
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
    @DisplayName("증강_파생본이_부모의_촬영환경_수동값을_복사한다")
    void 증강_파생본이_부모의_촬영환경_수동값을_복사한다() {
        // given — 부모가 파생값(13시=DAY)과 다른 수동값(NGT·맑음·WINTER)을 보유
        LsDataRaw parent = parentWithManualEnvironment();

        // when
        LsDataRaw augmented = LsDataRaw.createFromAugment(parent, "/storage/augment/winter.mp4", "WINTER");

        // then — 파생본이 같은 영상 소스이므로 촬영환경 수동값을 그대로 계승
        assertThat(augmented.getWthrNm()).isEqualTo("맑음");
        assertThat(augmented.getDayNgtCd()).isEqualTo("NGT");
        assertThat(augmented.getSesnCd()).isEqualTo("WINTER");
    }

    @Test
    @DisplayName("해상도_파생본이_부모의_촬영환경_수동값을_복사한다")
    void 해상도_파생본이_부모의_촬영환경_수동값을_복사한다() {
        // given
        LsDataRaw parent = parentWithManualEnvironment();

        // when
        LsDataRaw derived = LsDataRaw.createFromResolution(parent, "/storage/resl/720p.mp4", "RESL_720P");

        // then
        assertThat(derived.getWthrNm()).isEqualTo("맑음");
        assertThat(derived.getDayNgtCd()).isEqualTo("NGT");
        assertThat(derived.getSesnCd()).isEqualTo("WINTER");
    }

    @Test
    @DisplayName("부모가_수동값_없으면_파생본도_null로_복사(파생 폴백 유지)")
    void 부모가_수동값_없으면_파생본도_null로_복사() {
        // given — 촬영환경 수동값 미입력 부모
        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-env-null", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/storage/raw/1.mp4",
                LocalDateTime.of(2026, 7, 1, 13, 0), 120);

        // when
        LsDataRaw augmented = LsDataRaw.createFromAugment(parent, "/storage/augment/rain.mp4", "RAIN");
        LsDataRaw derived = LsDataRaw.createFromResolution(parent, "/storage/resl/480p.mp4", "RESL_480P");

        // then — 수동값 없음(null) 그대로 → 조회·동결 시 촬영일시 파생 폴백이 유지된다
        assertThat(augmented.getWthrNm()).isNull();
        assertThat(augmented.getDayNgtCd()).isNull();
        assertThat(augmented.getSesnCd()).isNull();
        assertThat(derived.getWthrNm()).isNull();
        assertThat(derived.getDayNgtCd()).isNull();
        assertThat(derived.getSesnCd()).isNull();
    }

    /** 촬영일시 파생(13시=DAY)과 어긋나는 수동 촬영환경(실내/터널 등)을 저장한 부모 영상. */
    private static LsDataRaw parentWithManualEnvironment() {
        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-env", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/storage/raw/1.mp4",
                LocalDateTime.of(2026, 7, 1, 13, 0), 120);
        parent.changeShootingEnvironment("맑음", "NGT", "WINTER");
        return parent;
    }

    @Test
    @DisplayName("markMarkingReady_가_DATA_STTS_CD를_MARKING_READY로_전이_원본경로_미변경")
    void markMarkingReady_transitionsAndPreservesRawPath() {
        // given — 적재 직후 PENDING
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-mr", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/storage/raw/mr.mp4", null, 60);
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.STATUS_PENDING);

        // when
        raw.markMarkingReady();

        // then — 상태 전이 + 원본 파일 경로는 절대 미변경(원본 보존)
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
        assertThat(raw.getRawFilePathNm()).isEqualTo("/storage/raw/mr.mp4");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
