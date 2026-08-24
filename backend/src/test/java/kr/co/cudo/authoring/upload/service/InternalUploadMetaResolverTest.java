package kr.co.cudo.authoring.upload.service;

import kr.co.cudo.authoring.upload.service.UploadMediaProbe.MediaMeta;
import kr.co.cudo.authoring.video.dto.ResolvedIngestMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InternalUploadMetaResolver} 해석·파생·검증 단위 테스트.
 *
 * <p>ffprobe 출력은 신뢰 경계 밖(CWE-20)이므로 형식·범위·DDL 길이를 통과한 값만 채택되고,
 * 통과하지 못한 값은 <b>그 컬럼만</b> 미채택되어야 한다(전량 폐기 금지). 화소(PXL)는 어떤 입력에도
 * 채워지지 않는다(R3).
 *
 * <p>⚠ {@code BIT} 은 R3 대상이 <b>아니다</b> — 색심도가 아니라 비트레이트(bps 정수)로 재정의된
 * 컬럼이라(설계 ERD-012) ffprobe 측정값으로 채운다. 이 파일의 비트레이트 테스트를 "R3 위반"으로
 * 보고 지우지 말 것.
 */
class InternalUploadMetaResolverTest {

    /** 정상 측정값 — 1920x1080 h264 29.97fps 10초, nb_frames 300, DAR 16:9, 2,050,627bps. */
    private MediaMeta full() {
        return new MediaMeta(1920, 1080, "h264", 30000.0 / 1001.0, 10_000L, 300L, "16:9", 2_050_627L);
    }

    @Test
    @DisplayName("nb_frames가_있으면_그_값을_프레임수로_채택한다")
    void adoptsNbFramesWhenPresent() {
        // given: 컨테이너가 프레임 수 300 을 신고
        MediaMeta meta = full();

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.frmeCnt()).isEqualByComparingTo(BigDecimal.valueOf(300));
    }

    @Test
    @DisplayName("nb_frames가_없으면_길이와_fps로_프레임수를_파생한다")
    void derivesFrameCountFromDurationAndFps() {
        // given: nb_frames 미제공 컨테이너. 10초 × 29.97fps ≈ 299.7 → 300
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", 30000.0 / 1001.0, 10_000L, null, "16:9", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.frmeCnt()).isEqualByComparingTo(BigDecimal.valueOf(300));
    }

    @Test
    @DisplayName("nb_frames가_N_A로_해석돼_비면_파생으로_넘어간다")
    void fallsBackToDerivationWhenNbFramesNonPositive() {
        // given: nb_frames 가 0(비정상값)으로 신고됨 — 파생으로 넘어가야 한다
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", 25.0, 8_000L, 0L, "16:9", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then: 8초 × 25fps = 200
        assertThat(resolved.frmeCnt()).isEqualByComparingTo(BigDecimal.valueOf(200));
    }

    @Test
    @DisplayName("nb_frames도_없고_fps도_미상이면_프레임수를_채택하지_않는다")
    void skipsFrameCountWhenNeitherAvailable() {
        // given: nb_frames·fps 둘 다 미상
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", null, 10_000L, null, "16:9", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.frmeCnt()).isNull();
    }

    @Test
    @DisplayName("display_aspect_ratio가_없으면_너비높이_최대공약수로_종횡비를_만든다")
    void derivesAspectRatioByGcd() {
        // given: DAR 미제공
        MediaMeta hd = new MediaMeta(1920, 1080, "h264", 25.0, 1_000L, 25L, null, null);
        MediaMeta sd = new MediaMeta(640, 480, "h264", 25.0, 1_000L, 25L, null, null);

        // when / then
        assertThat(InternalUploadMetaResolver.resolve(hd).asprtRt()).isEqualTo("16:9");
        assertThat(InternalUploadMetaResolver.resolve(sd).asprtRt()).isEqualTo("4:3");
    }

    @Test
    @DisplayName("display_aspect_ratio가_N_A이면_최대공약수_폴백으로_넘어간다")
    void fallsBackToGcdWhenAspectRatioIsNotAvailable() {
        // given: ffprobe 가 미상 문자열을 그대로 준 경우(포트가 정규화하지 못한 형태)
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", 25.0, 1_000L, 25L, "N/A", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.asprtRt()).isEqualTo("16:9");
    }

    @Test
    @DisplayName("display_aspect_ratio가_0_1이면_최대공약수_폴백으로_넘어간다")
    void fallsBackToGcdWhenAspectRatioIsZero() {
        // given: 형식은 맞지만 값이 0 인 비정상 DAR
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", 25.0, 1_000L, 25L, "0:1", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.asprtRt()).isEqualTo("16:9");
    }

    @Test
    @DisplayName("종횡비도_해상도도_없으면_종횡비를_채택하지_않는다")
    void skipsAspectRatioWhenNoSource() {
        // given: 비디오 스트림 없음 + DAR 미상
        MediaMeta meta = new MediaMeta(0, 0, "h264", 25.0, 1_000L, 25L, null, null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.asprtRt()).isNull();
    }

    @Test
    @DisplayName("r_frame_rate가_30000_1001이면_fps는_29_97로_포맷된다")
    void formatsFractionalFps() {
        // given / when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(full());

        // then
        assertThat(resolved.fps()).isEqualTo("29.97");
    }

    @Test
    @DisplayName("fps가_정수면_소수점_없이_포맷된다")
    void formatsIntegerFps() {
        // given: 30/1
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", 30.0, 1_000L, 30L, "16:9", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.fps()).isEqualTo("30");
    }

    @Test
    @DisplayName("fps가_미상이면_fps를_채택하지_않는다")
    void skipsFpsWhenUnknown() {
        // given
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", null, 10_000L, 300L, "16:9", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.fps()).isNull();
    }

    @Test
    @DisplayName("코덱명이_20자를_넘으면_절단하지_않고_미채택한다")
    void rejectsOverlongCodecInsteadOfTruncating() {
        // given: DDL VDO_CDC VARCHAR(20) 초과 — 절단하면 "틀린 코덱명"이 확정된다
        String overlong = "a".repeat(21);
        MediaMeta meta = new MediaMeta(1920, 1080, overlong, 25.0, 1_000L, 25L, "16:9", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then: 코덱만 미채택되고 나머지는 살아 있다
        assertThat(resolved.vdoCdc()).isNull();
        assertThat(resolved.resl()).isEqualTo("1920x1080");
    }

    @Test
    @DisplayName("코덱명이_공백뿐이면_미채택한다")
    void rejectsBlankCodec() {
        // given
        MediaMeta meta = new MediaMeta(1920, 1080, "   ", 25.0, 1_000L, 25L, "16:9", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.vdoCdc()).isNull();
    }

    @Test
    @DisplayName("해상도는_소문자_x_형식으로_정규화된다")
    void normalizesResolution() {
        // given / when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(full());

        // then
        assertThat(resolved.resl()).isEqualTo("1920x1080");
        assertThat(resolved.wdth()).isEqualByComparingTo(BigDecimal.valueOf(1920));
        assertThat(resolved.vrtc()).isEqualByComparingTo(BigDecimal.valueOf(1080));
    }

    @Test
    @DisplayName("비디오_스트림이_없으면_해상도_너비_세로_종횡비를_모두_미채택한다")
    void skipsDimensionsWhenNoVideoStream() {
        // given: width=0, height=0 (비디오 스트림 없음)
        MediaMeta meta = new MediaMeta(0, 0, "h264", 25.0, 10_000L, 250L, null, null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.wdth()).isNull();
        assertThat(resolved.vrtc()).isNull();
        assertThat(resolved.resl()).isNull();
        assertThat(resolved.asprtRt()).isNull();
        // 스트림과 무관한 값은 그대로 채택된다
        assertThat(resolved.vdoCdc()).isEqualTo("h264");
    }

    @Test
    @DisplayName("너비만_있고_높이가_0이면_둘_다_미채택한다")
    void skipsDimensionsWhenOnlyOneSideKnown() {
        // given
        MediaMeta meta = new MediaMeta(1920, 0, "h264", 25.0, 10_000L, 250L, null, null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.wdth()).isNull();
        assertThat(resolved.vrtc()).isNull();
        assertThat(resolved.resl()).isNull();
    }

    @Test
    @DisplayName("영상길이가_0이하면_길이를_채택하지_않는다")
    void skipsNonPositiveDuration() {
        // given
        MediaMeta zero = new MediaMeta(1920, 1080, "h264", 25.0, 0L, null, "16:9", null);
        MediaMeta unknown = new MediaMeta(1920, 1080, "h264", 25.0, null, null, "16:9", null);

        // when / then
        assertThat(InternalUploadMetaResolver.resolve(zero).vdoLenSec()).isNull();
        assertThat(InternalUploadMetaResolver.resolve(unknown).vdoLenSec()).isNull();
        // 길이가 없으면 프레임수 파생도 성립하지 않는다
        assertThat(InternalUploadMetaResolver.resolve(zero).frmeCnt()).isNull();
    }

    @Test
    @DisplayName("영상길이가_500ms_미만이면_초_반올림이_0이라_길이와_프레임수_모두_미채택한다")
    void skipsDurationAndFrameCountWhenRoundsToZeroSeconds() {
        // given: 499ms — 클래스 Javadoc "예외 2"가 명시한 바로 그 입력이다.
        //        0L/null 과 달리 앞단 가드(durationMs <= 0)를 통과해 "초 반올림 0" 분기로 들어간다.
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", 30.0, 499L, null, "16:9", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then: 길이 미채택 + 파생 프레임수까지 연쇄 차단(실제 프레임이 약 15장이어도 비운다)
        assertThat(resolved.vdoLenSec()).isNull();
        assertThat(resolved.frmeCnt()).isNull();
        // 길이와 무관한 컬럼은 그대로 채택된다 — "그 컬럼만 미채택" 규약 확인
        assertThat(resolved.resl()).isEqualTo("1920x1080");
        assertThat(resolved.fps()).isEqualTo("30");
    }

    @Test
    @DisplayName("코덱명에_허용되지_않는_문자가_있으면_미채택한다")
    void rejectsCodecWithDisallowedCharacters() {
        // given: 길이·공백 검사는 통과하지만 허용 문자(영숫자/밑줄/점/하이픈) 밖의 '/' 가 섞인 값 —
        //        CODEC_PATTERN 검사를 단독으로 발화시키는 입력이다(공백 케이스는 isEmpty 쪽에서 걸린다)
        MediaMeta meta = new MediaMeta(1920, 1080, "h264/avc", 25.0, 1_000L, 25L, "16:9", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.vdoCdc()).isNull();
        assertThat(resolved.resl()).isEqualTo("1920x1080");
    }

    @Test
    @DisplayName("영상길이는_초단위_반올림으로_채택된다")
    void roundsDurationToSeconds() {
        // given: 12,500ms → 13초 (HALF_UP)
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", 25.0, 12_500L, 312L, "16:9", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.vdoLenSec()).isEqualByComparingTo(BigDecimal.valueOf(13));
    }

    // ==================== DDL 상한 경계 회귀 (가드 mutation 검출용) ====================
    //
    // 아래 6건은 "가드를 약화시켜도 스위트가 초록"인 상태를 막기 위한 것이다. 이 테스트들이 없을 때
    // MAX_TEXT_LENGTH 20→200 · MAX_FPS_LENGTH 10→100 · TEN.pow(10)→TEN.pow(30) · bounded() 제거를
    // 해도 20건 중 19건이 그대로 통과했다(적대적 검증 실측). 기존 케이스의 최대 입력이 1920/1080/
    // 12_500L 수준이라 reject 분기에 한 번도 진입하지 않았기 때문이다.
    //
    // ⚠ 각 입력값은 "어느 DDL 상한을 어떻게 넘는가"로 고른 것이다. 값을 임의로 줄이면 그 순간
    //    가드 검출력이 사라진다(경계 밖으로 나가면 reject 분기에 도달하지 못한다).

    @Test
    @DisplayName("해상도_문자열이_20자를_넘으면_해상도를_미채택한다")
    void rejectsOverlongResolutionText() {
        // given: 2147483647x2147483647 = 21자 → RESL VARCHAR(20) 초과
        //        (int 최대값이라 이보다 긴 해상도 문자열은 만들 수 없다 = 최악 경계)
        MediaMeta meta = new MediaMeta(Integer.MAX_VALUE, Integer.MAX_VALUE, "h264",
                25.0, 1_000L, 25L, null, null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then: 길이 가드가 약화되면 21자 문자열이 그대로 채택돼 Phase 2 에서 DB 예외가 된다
        assertThat(resolved.resl()).isNull();
        // 너비·세로는 NUMERIC(10) 이내(10자리)라 살아 있다 — 컬럼 단위 미채택 규약 확인
        assertThat(resolved.wdth()).isEqualByComparingTo(BigDecimal.valueOf(Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("최대공약수_축약_종횡비가_20자를_넘으면_종횡비를_미채택한다")
    void rejectsOverlongGcdAspectRatio() {
        // given: 2147483647 과 2147483646 은 서로소(연속 정수)라 GCD 축약이 되지 않아
        //        "2147483647:2147483646" = 21자 → ASPRT_RT VARCHAR(20) 초과
        MediaMeta meta = new MediaMeta(Integer.MAX_VALUE, Integer.MAX_VALUE - 1, "h264",
                25.0, 1_000L, 25L, null, null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.asprtRt()).isNull();
    }

    @Test
    @DisplayName("nb_frames가_NUMERIC_10_자릿수를_넘으면_프레임수를_미채택한다")
    void rejectsNbFramesExceedingNumeric10() {
        // given: Long.MAX_VALUE(19자리) → FRME_CNT NUMERIC(10) 초과
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", 25.0, 1_000L, Long.MAX_VALUE, null, null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then: 상한이 약화되면 19자리 값이 NUMERIC(10) 컬럼으로 흘러간다
        assertThat(resolved.frmeCnt()).isNull();
    }

    @Test
    @DisplayName("길이와_fps가_각각_상한_이내여도_곱이_NUMERIC_10을_넘으면_프레임수를_미채택한다")
    void rejectsDerivedFrameCountExceedingNumeric10() {
        // given: 각 값은 상한 이내인데(9,999,999초=7자리 · fps "9999.99"=7자) 곱이 11자리가 되는 조합
        //        9_999_999 × 9999.99 = 99,999,980,000 (11자리) → FRME_CNT NUMERIC(10) 초과
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", 9999.99, 9_999_999_000L, null, "16:9", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then: 파생 결과에도 상한 검증이 걸려 있어야 한다 (입력만 검증하면 새는 경로)
        assertThat(resolved.frmeCnt()).isNull();
        // 각 입력 자체는 상한 이내이므로 채택된다 — "곱만" 걸러졌음을 확인
        assertThat(resolved.vdoLenSec()).isEqualByComparingTo(BigDecimal.valueOf(9_999_999));
        assertThat(resolved.fps()).isEqualTo("9999.99");
    }

    @Test
    @DisplayName("fps_표기가_10자를_넘으면_fps를_미채택한다")
    void rejectsOverlongFpsText() {
        // given: 1e10 → toPlainString "10000000000" = 11자 → FPS VARCHAR(10) 초과
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", 1e10, 1_000L, 25L, "16:9", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.fps()).isNull();
    }

    @Test
    @DisplayName("영상길이_초가_NUMERIC_10_자릿수를_넘으면_길이를_미채택한다")
    void rejectsDurationExceedingNumeric10() {
        // given: Long.MAX_VALUE ms → 9,223,372,036,854,776초(16자리) → VDO_LEN_SEC NUMERIC(10) 초과
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", 25.0, Long.MAX_VALUE, null, "16:9", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.vdoLenSec()).isNull();
        // 길이 미채택은 프레임수 파생까지 연쇄 차단한다(클래스 Javadoc "예외 2")
        assertThat(resolved.frmeCnt()).isNull();
    }

    @Test
    @DisplayName("화소는_어떤_입력에도_채워지지_않는다 — 비트레이트는_R3_대상이_아니다")
    void neverExposesPixel() {
        // given / when: ResolvedIngestMeta 는 PXL 을 담을 필드 자체를 갖지 않는다 (R3).
        //   ★bit 는 <있다> — 색심도가 아니라 비트레이트(bps 정수)로 재정의된 컬럼이다(ERD-012).
        var names = Arrays.stream(ResolvedIngestMeta.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        // then
        assertThat(names).containsExactlyInAnyOrder(
                "vdoLenSec", "fps", "vdoCdc", "wdth", "vrtc", "resl", "frmeCnt", "asprtRt", "bit");
        assertThat(names).doesNotContain("pxl");
    }

    // ======================== 비트레이트(BIT — bps 정수) ========================

    @Test
    @DisplayName("측정된_비트레이트를_bps_정수_문자열로_채택한다 — 단위접미사도_kbps환산도_없다")
    void adoptsBitRateAsPlainBpsInteger() {
        // given: ffprobe 가 2,050,627 bps 를 신고
        MediaMeta meta = full();

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then — 관제가 보내는 표기와 동일해야 한 컬럼에 두 표기가 섞이지 않는다
        assertThat(resolved.bit()).isEqualTo("2050627");
    }

    @Test
    @DisplayName("비트레이트가_미상이면_그_컬럼만_미채택되고_나머지는_그대로다")
    void rejectsNullBitRateWithoutAffectingOthers() {
        // given: 컨테이너가 bit_rate 를 신고하지 않음
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", 25.0, 10_000L, 250L, "16:9", null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then — 전량 폐기 금지(그 컬럼만 미채택)
        assertThat(resolved.bit()).isNull();
        assertThat(resolved.vdoCdc()).isEqualTo("h264");
        assertThat(resolved.resl()).isEqualTo("1920x1080");
    }

    @Test
    @DisplayName("비트레이트가_0이거나_음수면_미채택한다 — 0bps는_형식상_정상인_틀린_값이다")
    void rejectsNonPositiveBitRate() {
        // given: ffprobe 는 값을 모를 때 0 을 신고하는 컨테이너가 있다
        MediaMeta zero = new MediaMeta(1920, 1080, "h264", 25.0, 10_000L, 250L, "16:9", 0L);
        MediaMeta negative = new MediaMeta(1920, 1080, "h264", 25.0, 10_000L, 250L, "16:9", -1L);

        // when / then
        assertThat(InternalUploadMetaResolver.resolve(zero).bit()).isNull();
        assertThat(InternalUploadMetaResolver.resolve(negative).bit()).isNull();
    }

    @Test
    @DisplayName("Long_최대치_19자도_BIT_VARCHAR_20_안이라_채택된다 — 길이초과는_이_타입에서_도달불가다")
    void adoptsLongMaxBitRateBecauseTwentyCharsIsUnreachable() {
        // ★사실 고지: Long 양수의 최대 표기는 19자(9223372036854775807)라 VARCHAR(20) 을 넘길 수
        //   없다. 즉 "20자 초과 미채택" 은 현재 입력 도메인에서 <도달 불가>이며, 그것을 검증하는
        //   테스트는 만들 수 없다(억지로 만들려면 리플렉션으로 레코드를 위조해야 한다). 대신
        //   <경계가 한계 안에 있다>는 사실을 고정한다 — 포트 타입이 넓어지면 이 테스트가 함께
        //   재검토 대상이 된다. bounded() 가드 자체는 그 변경에 대비해 남겨 둔다.
        MediaMeta meta = new MediaMeta(1920, 1080, "h264", 25.0, 10_000L, 250L, "16:9", Long.MAX_VALUE);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.bit()).isEqualTo("9223372036854775807");
        assertThat(resolved.bit()).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    @DisplayName("비트레이트만_유효해도_isEmpty가_거짓이다")
    void isNotEmptyWhenOnlyBitRateAdopted() {
        // given: 비트레이트 외 전량 미상
        MediaMeta meta = new MediaMeta(0, 0, null, null, null, null, null, 1_500_000L);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then — isEmpty 가 bit 를 세지 않으면 back-fill UPDATE 자체가 skip 된다
        assertThat(resolved.isEmpty()).isFalse();
        assertThat(resolved.bit()).isEqualTo("1500000");
    }

    @Test
    @DisplayName("측정값이_하나도_유효하지_않으면_isEmpty가_참이다")
    void isEmptyWhenNothingAdopted() {
        // given: 전량 미상
        MediaMeta meta = new MediaMeta(0, 0, null, null, null, null, null, null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("하나라도_채택되면_isEmpty가_거짓이다")
    void isNotEmptyWhenAnythingAdopted() {
        // given: 코덱만 유효
        MediaMeta meta = new MediaMeta(0, 0, "h264", null, null, null, null, null);

        // when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(meta);

        // then
        assertThat(resolved.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("측정값이_null이면_전량_미채택으로_돌아온다")
    void handlesNullMeta() {
        // given / when
        ResolvedIngestMeta resolved = InternalUploadMetaResolver.resolve(null);

        // then
        assertThat(resolved.isEmpty()).isTrue();
    }
}
