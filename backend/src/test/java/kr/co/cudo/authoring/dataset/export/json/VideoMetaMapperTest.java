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
        assertThat(video.orignFilename()).isEqualTo("original.mp4");
        assertThat(video.dateCreated()).isEqualTo("2026-05-01");
        assertThat(video.length()).isEqualTo("60");
        assertThat(video.pseudonymity()).isEqualTo("Y");        // PSDO
        assertThat(video.privacyIncluded()).isEqualTo("Y");     // raw.prvcYn 파생
    }

    @Test
    @DisplayName("PSDO_타입이면_pseudonymity_Y다")
    void psdoPseudonymityYes() {
        // given
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/x/a.mp4")
                .prvcTypeCd(LsDataRaw.PRVC_TYPE_PSDO)
                .build();

        // when
        NiaVideo video = mapper.toVideo(meta, null, ExportKind.ORIGINAL);

        // then
        assertThat(video.pseudonymity()).isEqualTo("Y");
    }

    @Test
    @DisplayName("PSDO가_아니면_pseudonymity_N이다")
    void nonPsdoPseudonymityNo() {
        // given — PRVC 타입
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/x/a.mp4")
                .prvcTypeCd(LsDataRaw.PRVC_TYPE_PRVC)
                .build();

        // when / then
        assertThat(mapper.toVideo(meta, null, ExportKind.ORIGINAL).pseudonymity()).isEqualTo("N");
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
        assertThat(video.orignFilename()).isNull();
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
        assertThat(video.orignFilename()).isEqualTo("deidentified.mp4");
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
        assertThat(video.orignFilename()).isNull();
    }

    @Test
    @DisplayName("ORIGINAL이면_anonymity_N_DEIDENTIFIED면_Y")
    void anonymityOverriddenByKind() {
        // given
        LsDatasetVideoMeta meta = LsDatasetVideoMeta.builder()
                .rawSn(1L).rawFilePathNm("/x/a.mp4").build();

        // when / then — kind 만으로 anonymity 결정
        assertThat(mapper.toVideo(meta, null, ExportKind.ORIGINAL).anonymity()).isEqualTo("N");
        assertThat(mapper.toVideo(meta, null, ExportKind.DEIDENTIFIED).anonymity()).isEqualTo("Y");
    }
}
