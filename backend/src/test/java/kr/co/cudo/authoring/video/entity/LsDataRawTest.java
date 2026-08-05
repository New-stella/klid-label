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
        LsDataRaw augmented = LsDataRaw.createFromAugment(parent, "/storage/augment/winter.mp4", "WINTER", 7001L);

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
        LsDataRaw augmented = LsDataRaw.createFromAugment(parent, "/storage/augment/winter.mp4", "WINTER", 7001L);

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
        LsDataRaw augmented = LsDataRaw.createFromAugment(parent, "/storage/augment/rain.mp4", "RAIN", 7002L);
        LsDataRaw derived = LsDataRaw.createFromResolution(parent, "/storage/resl/480p.mp4", "RESL_480P");

        // then — 수동값 없음(null) 그대로 → 조회·동결 시 촬영일시 파생 폴백이 유지된다
        assertThat(augmented.getWthrNm()).isNull();
        assertThat(augmented.getDayNgtCd()).isNull();
        assertThat(augmented.getSesnCd()).isNull();
        assertThat(derived.getWthrNm()).isNull();
        assertThat(derived.getDayNgtCd()).isNull();
        assertThat(derived.getSesnCd()).isNull();
    }

    // ─── 영상 단위 개인정보 수동값 계승 (V163, DEV_FIX 2026-08-03) ────────────────────

    /**
     * ★ 파생본이 부모의 <b>영상 축</b> 개인정보 판정을 계승해야 한다.
     *
     * <p>파생 프레임은 부모 프레임의 개인정보 수동값을 이미 복사받는데(프레임 축), 영상 축만 빠지면
     * 같은 {@code deid} 문서에서 {@code image="Y"} / {@code video="N"} 이 난다. 이는 정책이 정당화한
     * 방향("영상엔 있지만 이 프레임엔 없다")의 <b>역방향</b>이라 성립할 수 없는 조합이며, 개인정보가
     * 남은 영상이 영상 단위로 "없음"으로 <b>과소 신고</b>된다.
     */
    @Test
    @DisplayName("증강_파생본이_부모의_영상단위_개인정보_수동값을_계승한다")
    void 증강_파생본이_부모의_개인정보_수동값을_계승한다() {
        // given — 부모가 "익명 아님 / 가명 아님 / 개인정보 잔존"으로 판정된 영상
        LsDataRaw parent = parentWithManualPrivacy();

        // when
        LsDataRaw augmented = LsDataRaw.createFromAugment(parent, "/storage/augment/winter.mp4", "WINTER", 7101L);

        // then
        assertThat(augmented.getAnonyInclYn()).isEqualTo("N");
        assertThat(augmented.getPsdoInclYn()).isEqualTo("N");
        assertThat(augmented.getPrvcInclYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("해상도_파생본이_부모의_영상단위_개인정보_수동값을_계승한다")
    void 해상도_파생본이_부모의_개인정보_수동값을_계승한다() {
        // given
        LsDataRaw parent = parentWithManualPrivacy();

        // when — 리스케일은 픽셀만 바꾸므로 개인정보 잔존 여부라는 사실은 부모와 같다
        LsDataRaw derived = LsDataRaw.createFromResolution(parent, "/storage/resl/720p.mp4", "RESL_720P");

        // then
        assertThat(derived.getAnonyInclYn()).isEqualTo("N");
        assertThat(derived.getPsdoInclYn()).isEqualTo("N");
        assertThat(derived.getPrvcInclYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("부모가_개인정보_미입력이면_파생본은_적재_기본값을_쓴다 (구 기대 '파생본도 null' 폐기 — 2026-08-04)")
    void 부모가_개인정보_미입력이면_파생본도_null이다() {
        // given — 영상 축 수동 판정 없음
        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-prv-null", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/storage/raw/1.mp4",
                LocalDateTime.of(2026, 7, 1, 13, 0), 120);

        // when
        LsDataRaw augmented = LsDataRaw.createFromAugment(parent, "/a.mp4", "RAIN", 7102L);
        LsDataRaw derived = LsDataRaw.createFromResolution(parent, "/r.mp4", "RESL_480P");

        // then — ★ 구 기대("null 그대로") 폐기(2026-08-04): 비식별 3필드를 <쓰는 시점>에 실제 값으로
        //   적재하게 되어 부모가 미입력이면 파생은 적재 기본값(Y/N/N)을 계승한다. 부모 자체도 이제
        //   createFromIngest 가 Y/N/N 을 넣으므로 이 시나리오는 <레거시 부모>를 가정한 것이다.
        //   export 산출값은 프리필 상수와 같아 파일 내용은 달라지지 않는다.
        assertThat(augmented.getAnonyInclYn()).isEqualTo("Y");
        assertThat(augmented.getPsdoInclYn()).isEqualTo("N");
        assertThat(augmented.getPrvcInclYn()).isEqualTo("N");
        assertThat(derived.getAnonyInclYn()).isEqualTo("Y");
        assertThat(derived.getPsdoInclYn()).isEqualTo("N");
        assertThat(derived.getPrvcInclYn()).isEqualTo("N");
    }

    /** "개인정보 잔존(privacy_included=Y)" 으로 수동 판정된 부모 영상. */
    private static LsDataRaw parentWithManualPrivacy() {
        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-prv", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/storage/raw/1.mp4",
                LocalDateTime.of(2026, 7, 1, 13, 0), 120);
        parent.changePrivacyMeta("N", "N", "Y");
        return parent;
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

    // ─── 파생 식별자 유일성 (MED-2, 2026-07-31) ─────────────────────────────

    /**
     * 같은 (부모 × 종류) 재요청이 허용된 뒤로는 두 파생이 <b>같은 밀리초</b>에 만들어질 수 있다.
     * 구 구현({@code System.currentTimeMillis()} 접미)은 그때 {@code VMS_CLIP_ID} 가 같아져
     * {@code UK_LS_DATA_RAW_VMS_CLIP} 위반 → 콜백 롤백 → 외부 결과물 유실로 이어졌다.
     * 유일성의 근거를 <b>시각이 아니라 증강 행 PK</b> 로 옮겼으므로 타이밍과 무관하게 달라야 한다.
     */
    @Test
    @DisplayName("같은_부모와_종류로_연속_생성해도_VMS_CLIP_ID가_충돌하지_않는다")
    void 같은_부모와_종류로_연속_생성해도_식별자가_다르다() {
        // given — 같은 부모, 같은 증강 종류
        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-dup", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/storage/raw/1.mp4", null, 120);

        // when — 증강 행 PK 만 다른 두 파생 (같은 밀리초에 만들어져도 성립해야 한다)
        LsDataRaw first = LsDataRaw.createFromAugment(parent, "/a.mp4", "WINTER", 5001L);
        LsDataRaw second = LsDataRaw.createFromAugment(parent, "/b.mp4", "WINTER", 5002L);

        // then
        assertThat(first.getVmsClipId()).isNotEqualTo(second.getVmsClipId());
        assertThat(first.getVmsClipId()).isEqualTo("clip-dup_AUG_WINTER_5001");
        assertThat(second.getVmsClipId()).isEqualTo("clip-dup_AUG_WINTER_5002");
    }

    /**
     * 파생본도 다시 증강 대상이 될 수 있어 마커가 누적된다 — 컬럼 상한(VARCHAR(128))을 넘기면
     * 적재가 거부돼 콜백이 500 으로 끝난다. 넘칠 때는 앞쪽을 자르되 <b>유일 접미는 보존</b>한다.
     */
    @Test
    @DisplayName("부모_식별자가_길어도_VMS_CLIP_ID는_128자를_넘지_않고_유일접미를_보존한다")
    void 파생_식별자는_컬럼_상한을_넘지_않는다() {
        // given — 이미 상한에 가까운 부모 식별자
        String longClip = "C".repeat(128);
        LsDataRaw parent = LsDataRaw.createFromIngest(
                longClip, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/storage/raw/1.mp4", null, 120);

        // when
        LsDataRaw augmented = LsDataRaw.createFromAugment(parent, "/a.mp4", "WINTER", 987654L);

        // then
        assertThat(augmented.getVmsClipId()).hasSizeLessThanOrEqualTo(128);
        assertThat(augmented.getVmsClipId()).endsWith("_AUG_WINTER_987654");
    }

    @Test
    @DisplayName("신규_증강_파생이_생성시점에_AUG_TYPE_CD를_갖는다")
    void 신규_증강_파생이_생성시점에_AUG_TYPE_CD를_갖는다() {
        // given
        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-aug-src", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/storage/raw/1.mp4", null, 120);

        // when
        // (식별자 접미는 증강 행 PK — 같은 (영상 × 종류) 재요청이 허용되면서 시각 기반 접미가
        //  동시 콜백에서 충돌하던 것을 대체한 값이다. 여기서는 임의의 유효 PK 를 넘긴다.)
        LsDataRaw augmented = LsDataRaw.createFromAugment(parent, "/storage/augment/winter.mp4",
                LsDataAug.AUG_WINTER, 4242L);

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

    // ------------------------------------------------------------ 비식별 축 적재 기본값 (2026-08-04)

    @Test
    @DisplayName("비식별_3필드는_적재_시점에_YNN_으로_채워진다")
    void 비식별_3필드는_적재_시점에_YNN_으로_채워진다() {
        // given / when — 관제 인입 적재(영상 생성의 단일 팩토리)
        LsDataRaw raw = LsDataRaw.createFromIngest("CLIP-PRV-1", "CCTV-1", "EVT", "11110",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/raw/a.mp4", LocalDateTime.now(), 30);

        // then — 값이 <실제로 INSERT> 된다(읽는 시점 프리필이 아니다).
        //   ★ DB 컬럼 DEFAULT 로 하지 않은 이유: @DynamicInsert 가 없어 Hibernate 가 모든 컬럼을
        //     명시 INSERT 하므로 DEFAULT 가 적용될 수 없다(관제가 직접 INSERT 하는 LS_DATA_INGEST 와
        //     기법이 다른 이유 = "누가 INSERT 하는가"가 다르다).
        assertThat(raw.getAnonyInclYn()).isEqualTo("Y");
        assertThat(raw.getPsdoInclYn()).isEqualTo("N");
        assertThat(raw.getPrvcInclYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("라벨링화면_수정이_적재값을_덮어쓰고_null_인자는_null_로_설정된다")
    void 라벨링화면_수정이_적재값을_덮어쓰고_null_인자는_null_로_설정된다() {
        // given
        LsDataRaw raw = LsDataRaw.createFromIngest("CLIP-PRV-2", "CCTV-1", "EVT", "11110",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/raw/b.mp4", LocalDateTime.now(), 30);

        // when — 화면 수정(PUT /v1/videos/{rawSn}/privacy-meta 가 쓰는 기존 경로)
        raw.changePrivacyMeta("N", "Y", "Y");

        // then
        assertThat(raw.getAnonyInclYn()).isEqualTo("N");
        assertThat(raw.getPrvcInclYn()).isEqualTo("Y");

        // when — 엔티티 메서드에 null 3개를 직접 전달(도메인 메서드 자체의 계약 검증).
        //   ★ 주의: 이 호출은 <프로덕션 호출자가 없다>. 구 주석은 이 지점을 "비식별 누락 신고 리셋
        //   (DeidentReportService)" 이라 설명했으나, 신고가 개인정보 3필드를 리셋하던 동작은
        //   2026-08-04 폐기됐다(사용자 확정 — 라벨 보존 정책과 같은 취지). 서비스 참조를 걷어내고
        //   메서드 계약("null 을 주면 null 로 설정된다")만 남긴다.
        raw.changePrivacyMeta(null, null, null);

        // then — null 인자는 <적재 기본값으로 대체되지 않고> 그대로 null 이 된다. 그런 행(레거시 행)의
        //   export 는 ExportPrivacyPolicy 의 프리필 상수(Y/N/N)를 타므로 산출값은 종전과 같다.
        //   (이래서 프리필 상수를 제거하지 않고 유지한다.)
        assertThat(raw.getAnonyInclYn()).isNull();
        assertThat(raw.getPsdoInclYn()).isNull();
        assertThat(raw.getPrvcInclYn()).isNull();
    }
}
