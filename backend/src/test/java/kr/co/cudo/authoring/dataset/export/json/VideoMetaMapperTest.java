package kr.co.cudo.authoring.dataset.export.json;

import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import kr.co.cudo.authoring.dataset.export.ExportKind;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link VideoMetaMapper} 단위 테스트 — meta→raw 폴백·PSDO 분기·null 합성(location/coordinates/filename)·
 * kind 오버라이드 커버리지.
 *
 * <p>DEV_FIX(Critical): LsDataRaw 폴백 경로가 0% 커버였다. meta 필드가 null 일 때 raw 로 대체되는지,
 * 합성 헬퍼(composeLocation/composeCoordinates/basename)의 null 분기를 고정한다.
 */
class VideoMetaMapperTest {

    private final VideoMetaMapper mapper = new VideoMetaMapper();

    @Test
    @DisplayName("meta필드가_null이면_LsDataRaw로_폴백한다")
    void fallsBackToRawWhenMetaNull() {
        // given — 폴백 대상 필드(rawSn/rawFilePathNm/shtDt/vdoLenSec/prvcTypeCd/prvcYn)가 meta 에 모두 null
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .cctvNm("보조 CCTV")
                .build();
        LsDataRaw raw = LsDataRaw.builder()
                .vmsClipId("clip-99").vmsCctvId("cctv-9")
                .prvcTypeCd(LsDataRaw.PRVC_TYPE_PSDO)          // → prvcYn 파생 "Y"
                .rawFilePathNm("/nas/raw/99/original.mp4")
                .shtDt(LocalDateTime.of(2026, 5, 1, 9, 0))
                .durationSec(60)
                .build();
        ReflectionTestUtils.setField(raw, "rawSn", 99L);

        // when
        NiaVideo video = mapper.toVideo(meta, raw, ExportKind.ORIGINAL);

        // then — 전 필드가 raw 값으로 대체됨
        assertThat(video.id()).isEqualTo("99");
        assertThat(video.filename()).isEqualTo("original.mp4");
        assertThat(video.dateCreated()).isEqualTo("2026-05-01");
        assertThat(video.length()).isEqualTo("60");
        // 개인정보 3필드는 더 이상 prvcTypeCd/prvcYn 에서 파생되지 않는다(2026-08-03 정책 반전) —
        // ORIGINAL 은 판정하지 않으므로 null 이다.
        assertThat(video.pseudonymity()).isNull();
        assertThat(video.privacyIncluded()).isNull();
    }

    @Test
    @DisplayName("원천산출물은_개인정보3필드가_모두_null이다")
    void 원천산출물은_개인정보3필드가_모두_null이다() {
        // given — 영상이 PSDO(가명)·개인정보 포함(Y)이고 수동 판정까지 저장돼 있어도
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/x/a.mp4")
                .prvcTypeCd(LsDataRaw.PRVC_TYPE_PSDO).prvcYn("Y")
                .build();
        LsDataRaw raw = rawWithPrivacyMeta("N", "Y", "Y");

        // when
        NiaVideo original = mapper.toVideo(meta, raw, ExportKind.ORIGINAL);

        // then — 원천영상은 비식별 처리 전이라 판정이 성립하지 않는다(값을 지어내지 않음).
        assertThat(original.anonymity()).isNull();
        assertThat(original.pseudonymity()).isNull();
        assertThat(original.privacyIncluded()).isNull();
    }

    @Test
    @DisplayName("비식별산출물은_영상단위_수동값을_읽는다")
    void 비식별산출물은_영상단위_수동값을_읽는다() {
        // given — 사람이 영상 단위로 "익명 아님 / 가명 포함 / 개인정보 포함"으로 판정(LS_DATA_RAW, V163)
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/x/a.mp4")
                .prvcTypeCd(LsDataRaw.PRVC_TYPE_PRVC).prvcYn("N")
                .build();
        LsDataRaw raw = rawWithPrivacyMeta("N", "Y", "Y");

        // when
        NiaVideo deid = mapper.toVideo(meta, raw, ExportKind.DEIDENTIFIED, "/x/deid/a.mp4");

        // then — 수동 판정이 기본상수를 덮는다
        assertThat(deid.anonymity()).isEqualTo("N");
        assertThat(deid.pseudonymity()).isEqualTo("Y");
        assertThat(deid.privacyIncluded()).isEqualTo("Y");
    }

    @Test
    @DisplayName("비식별산출물은_수동값_미입력시_기본상수_YNN을_쓴다")
    void 비식별산출물은_수동값_미입력시_기본상수_YNN을_쓴다() {
        // given — 수동 판정 없음(raw 자체가 없는 경우 포함 — fail-safe)
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/x/a.mp4")
                .prvcTypeCd(LsDataRaw.PRVC_TYPE_PSDO).prvcYn("Y")
                .build();

        // when
        NiaVideo deid = mapper.toVideo(meta, null, ExportKind.DEIDENTIFIED, "/x/deid/a.mp4");

        // then — "전체가 비식별된 산출물" 기본 가정
        assertThat(deid.anonymity()).isEqualTo("Y");
        assertThat(deid.pseudonymity()).isEqualTo("N");
        assertThat(deid.privacyIncluded()).isEqualTo("N");
    }

    @Test
    @DisplayName("location이_모두없으면_null")
    void locationNullWhenBothBlank() {
        // given — sidoNm/sggNm 미지정
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/x/a.mp4").build();

        // when / then
        assertThat(mapper.toVideo(meta, null, ExportKind.ORIGINAL).location()).isNull();
    }

    @Test
    @DisplayName("location_정상값이_결합된다")
    void locationJoinedWhenBothPresent() {
        // given — sido·sgg 모두 존재
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/x/a.mp4")
                .sidoNm("서울특별시").sggNm("강남구").build();

        // when / then — 공백 결합 (구현 규칙: (nvl(sido)+" "+nvl(sgg)).trim())
        assertThat(mapper.toVideo(meta, null, ExportKind.ORIGINAL).location())
                .isEqualTo("서울특별시 강남구");
    }

    @Test
    @DisplayName("location_한쪽만있으면_그값만")
    void locationOnlyOneSide() {
        // given — sido 만 있는 meta, sgg 만 있는 meta
        LsDatasetVideoMeta sidoOnly = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/x/a.mp4").sidoNm("서울특별시").build();
        LsDatasetVideoMeta sggOnly = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/x/a.mp4").sggNm("강남구").build();

        // when / then — 빈쪽은 trim 되어 존재하는 값만 남음
        assertThat(mapper.toVideo(sidoOnly, null, ExportKind.ORIGINAL).location()).isEqualTo("서울특별시");
        assertThat(mapper.toVideo(sggOnly, null, ExportKind.ORIGINAL).location()).isEqualTo("강남구");
    }

    @Test
    @DisplayName("coordinates_정상값이_결합된다")
    void coordinatesJoinedWhenBothPresent() {
        // given — lat·lot 모두 존재
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/x/a.mp4")
                .wgs84Lat(new BigDecimal("37.5")).wgs84Lot(new BigDecimal("127.0")).build();

        // when / then — 구현 규칙: lat.toPlainString()+","+lot.toPlainString() (공백 없음)
        assertThat(mapper.toVideo(meta, null, ExportKind.ORIGINAL).coordinates())
                .isEqualTo("37.5,127.0");
    }

    @Test
    @DisplayName("coordinates가_모두없으면_null")
    void coordinatesNullWhenBothNull() {
        // given — wgs84Lat/Lot 미지정
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/x/a.mp4").build();

        // when / then
        assertThat(mapper.toVideo(meta, null, ExportKind.ORIGINAL).coordinates()).isNull();
    }

    @Test
    @DisplayName("경로가_없으면_filename도_null")
    void filenameNullWhenNoPath() {
        // given — meta·raw 모두 경로 없음
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder().rawSn(1L).build();

        // when
        NiaVideo video = mapper.toVideo(meta, null, ExportKind.ORIGINAL);

        // then
        assertThat(video.filename()).isNull();
    }

    @Test
    @DisplayName("DEIDENTIFIED면_filename이_비식별경로_basename_원본아님")
    void deidFilenameUsesDeidVideoPath() {
        // given — 원본 raw 경로 + 별도 비식별 영상 경로
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/nas/raw/1/original.mp4").build();

        // when — DEIDENTIFIED + deid 영상 경로 전달
        NiaVideo video = mapper.toVideo(meta, null, ExportKind.DEIDENTIFIED, "/nas/deid/1/deidentified.mp4");

        // then — 비식별 파일명, 원본 파일명 미노출
        assertThat(video.filename()).isEqualTo("deidentified.mp4");
    }

    @Test
    @DisplayName("DEIDENTIFIED인데_deid경로_null이면_filename도_null_원본미노출")
    void deidFilenameNullWhenNoDeidPath() {
        // given — deid 영상 경로 미상(fail-secure)
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/nas/raw/1/original.mp4").build();

        // when
        NiaVideo video = mapper.toVideo(meta, null, ExportKind.DEIDENTIFIED, null);

        // then — 원본 파일명 대신 null
        assertThat(video.filename()).isNull();
    }

    /** 영상 단위 개인정보 수동값(LS_DATA_RAW, V163)을 담은 원시 영상 픽스처. */
    private static LsDataRaw rawWithPrivacyMeta(String anonymity, String pseudonymity, String privacyIncluded) {
        LsDataRaw raw = LsDataRaw.builder()
                .vmsClipId("clip-prv").vmsCctvId("cctv-prv")
                .prvcTypeCd(LsDataRaw.PRVC_TYPE_PRVC)
                .rawFilePathNm("/nas/raw/1/original.mp4")
                .shtDt(LocalDateTime.of(2026, 1, 15, 22, 0))
                .durationSec(30)
                .build();
        ReflectionTestUtils.setField(raw, "rawSn", 1L);
        raw.changePrivacyMeta(anonymity, pseudonymity, privacyIncluded);
        return raw;
    }

    /** 촬영환경 수동값(LS_DATA_RAW)을 담은 원시 영상 픽스처. */
    private static LsDataRaw rawWithEnvironment(String weather, String timeOfDay, String season) {
        LsDataRaw raw = LsDataRaw.builder()
                .vmsClipId("clip-env").vmsCctvId("cctv-env")
                .prvcTypeCd(LsDataRaw.PRVC_TYPE_PRVC)
                .rawFilePathNm("/nas/raw/1/original.mp4")
                .shtDt(LocalDateTime.of(2026, 1, 15, 22, 0))
                .durationSec(30)
                .build();
        ReflectionTestUtils.setField(raw, "rawSn", 1L);
        raw.changeShootingEnvironment(weather, timeOfDay, season);
        return raw;
    }

    @Test
    @DisplayName("수동값_저장시_export_NiaVideo_weather_timeofday_season에_반영")
    void 수동값이_스냅샷_파생값보다_우선한다() {
        // given — 스냅샷은 파생값(야간·겨울), raw 에는 수동값(주간·여름·비)
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/nas/raw/1/original.mp4")
                .dayNgtCd("NGT").sesnCd("WINTER").wthrNm(null)
                .build();
        LsDataRaw raw = rawWithEnvironment("비", "DAY", "SUMMER");

        // when
        NiaVideo video = mapper.toVideo(meta, raw, ExportKind.ORIGINAL);

        // then — 수동값 우선
        assertThat(video.weather()).isEqualTo("비");
        assertThat(video.timeOfDay()).isEqualTo("DAY");
        assertThat(video.season()).isEqualTo("SUMMER");
    }

    @Test
    @DisplayName("수동값_미저장시_export_기존_파생값_유지")
    void 수동값이_없으면_스냅샷_파생값을_유지한다() {
        // given — raw 촬영환경 미입력, 스냅샷에는 파생/동결값 존재 (회귀 방어)
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/nas/raw/1/original.mp4")
                .dayNgtCd("NGT").sesnCd("WINTER").wthrNm("흐림")
                .build();
        LsDataRaw raw = rawWithEnvironment(null, null, null);

        // when
        NiaVideo video = mapper.toVideo(meta, raw, ExportKind.ORIGINAL);

        // then — 기존 동작 그대로(스냅샷 값)
        assertThat(video.weather()).isEqualTo("흐림");
        assertThat(video.timeOfDay()).isEqualTo("NGT");
        assertThat(video.season()).isEqualTo("WINTER");
    }

    @Test
    @DisplayName("ORIGINAL_DEIDENTIFIED_두_export의_촬영환경_동일")
    void 촬영환경은_kind에_따라_분기되지_않는다() {
        // given — 영상 단위 값이라 2벌 산출이 동일해야 한다
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/nas/raw/1/original.mp4")
                .dayNgtCd("NGT").sesnCd("WINTER")
                .build();
        LsDataRaw raw = rawWithEnvironment("눈", "DAY", "SPRING");

        // when
        NiaVideo original = mapper.toVideo(meta, raw, ExportKind.ORIGINAL, null);
        NiaVideo deid = mapper.toVideo(meta, raw, ExportKind.DEIDENTIFIED, "/nas/deid/1/deidentified.mp4");

        // then
        assertThat(deid.weather()).isEqualTo(original.weather()).isEqualTo("눈");
        assertThat(deid.timeOfDay()).isEqualTo(original.timeOfDay()).isEqualTo("DAY");
        assertThat(deid.season()).isEqualTo(original.season()).isEqualTo("SPRING");
    }

    @Test
    @DisplayName("촬영환경_미입력이면_export_JSON에_키는_남고_값만_null이다")
    void 촬영환경_미상이어도_export_키_계약은_유지된다() {
        // given — 수동 미입력 영상(E-ISSUE-42 이후 동결값도 null). 관제/데이터마트 파서가 키 부재로
        //         깨지지 않도록 NiaVideo 는 JsonInclude.ALWAYS 로 키를 유지해야 한다.
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/nas/raw/1/original.mp4")
                .build();
        LsDataRaw raw = rawWithEnvironment(null, null, null);

        // when
        NiaVideo video = mapper.toVideo(meta, raw, ExportKind.ORIGINAL);
        com.fasterxml.jackson.databind.JsonNode json =
                new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(video);

        // then — 값은 미상(null)이되 키(time_of_day/season/weather)는 존재한다.
        assertThat(video.timeOfDay()).isNull();
        assertThat(video.season()).isNull();
        assertThat(video.weather()).isNull();
        assertThat(json.has("time_of_day")).isTrue();
        assertThat(json.has("season")).isTrue();
        assertThat(json.has("weather")).isTrue();
        assertThat(json.get("time_of_day").isNull()).isTrue();
        assertThat(json.get("season").isNull()).isTrue();
    }
}
