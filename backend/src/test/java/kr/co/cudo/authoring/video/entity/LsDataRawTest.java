package kr.co.cudo.authoring.video.entity;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.video.dto.ResolutionPreset;
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
    @DisplayName("신규_증강_파생이_생성시점에_AUG_TYPE_CD를_갖는다")
    void 신규_증강_파생이_생성시점에_AUG_TYPE_CD를_갖는다() {
        // given
        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-aug-src", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/storage/raw/1.mp4", null, 120);

        // when
        LsDataRaw augmented = LsDataRaw.createFromAugment(parent, "/storage/augment/winter.mp4",
                LsDataAug.AUG_WINTER);

        // then — 파생은 생성 시점에 출처유형·증강종류를 스스로 보유한다(V149 백필 이후 신규 행이
        //        영구 NULL 로 남지 않게 하는 쓰기측 배선. 파서 제거의 선행 조건)
        assertThat(augmented.getSrcType()).isEqualTo(LsDataRaw.SRC_TYPE_AUGMENTED);
        assertThat(augmented.getAugTypeCd()).isEqualTo(LsDataAug.AUG_WINTER);

        // then — VMS_CLIP_ID 에 심는 마커 문자열과 컬럼값이 같은 값이다(백필된 과거 행과 값 체계 동일).
        //        판별 원천은 컬럼이며 clipId 는 표시·추적용 원문일 뿐이다.
        assertThat(augmented.getVmsClipId())
                .startsWith(parent.getVmsClipId() + "_AUG_" + LsDataAug.AUG_WINTER + "_");
    }

    @Test
    @DisplayName("신규_해상도_파생이_생성시점에_RESL_코드를_갖는다")
    void 신규_해상도_파생이_생성시점에_RESL_코드를_갖는다() {
        // given
        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-resl-src", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/storage/raw/1.mp4", null, 120);

        // when — 호출부(ResolutionReservationPersister)가 넘기는 값 = ResolutionPreset.name()
        LsDataRaw derived = LsDataRaw.createFromResolution(parent, "/storage/resl/720p.mp4",
                ResolutionPreset.RESL_720P.name());

        // then
        assertThat(derived.getSrcType()).isEqualTo(LsDataRaw.SRC_TYPE_AUGMENTED);
        assertThat(derived.getAugTypeCd()).isEqualTo("RESL_720P");

        // then — clipId 는 이중 접두(_RESL_RESL_720P_) 형태로 생성되지만 판별에 쓰이지 않는다.
        //        컬럼값이 단일 원천이며 clipId 는 표시·추적용 원문일 뿐이다.
        assertThat(derived.getVmsClipId())
                .startsWith(parent.getVmsClipId() + "_RESL_" + ResolutionPreset.RESL_720P.name() + "_");
    }

    @Test
    @DisplayName("원본_영상은_AUG_TYPE_CD가_null이다")
    void 원본_영상은_AUG_TYPE_CD가_null이다() {
        // given / when — 관제 인입 적재(파생 아님)
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-original", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/storage/raw/1.mp4", null, 120, "RELAY");

        // then — 증강종류는 파생 전용이라 원본은 null, 출처유형은 인입값 그대로
        assertThat(raw.getAugTypeCd()).isNull();
        assertThat(raw.getSrcType()).isEqualTo("RELAY");
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
