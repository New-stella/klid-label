package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.dataset.entity.LsDatasetVideoMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * {@link LabelContentHasher} 순수 함수 단위 테스트 — 무수정 재승인 멱등 판정 키의 결정성/민감도 검증.
 *
 * <p>해시 입력은 라벨뿐 아니라 산출 JSON 에 직렬화되는 프레임(frmExpln 등)·영상 메타(prvcTypeCd 등)를
 * 포함하므로, "산출 JSON 이 달라지면 해시도 달라진다" 불변식을 프레임/메타 변경 재현 테스트로 고정한다.
 */
class LabelContentHasherTest {

    private final LabelContentHasher hasher = new LabelContentHasher();

    private LsDataLbl label(Long lblSn, Long srcSn, Long labelId, String type,
                            String labelNm, String points, String trackId) {
        LsDataLbl l = mock(LsDataLbl.class);
        lenient().when(l.getLblSn()).thenReturn(lblSn);
        lenient().when(l.getSrcSn()).thenReturn(srcSn);
        lenient().when(l.getLabelId()).thenReturn(labelId);
        lenient().when(l.getLblTypeCd()).thenReturn(type);
        lenient().when(l.getLabelNm()).thenReturn(labelNm);
        lenient().when(l.getPointCn()).thenReturn(points);
        lenient().when(l.getTrackId()).thenReturn(trackId);
        return l;
    }

    private LsDataSrc frame(Long srcSn, Long frameNo, String frmExpln) {
        LsDataSrc s = mock(LsDataSrc.class);
        lenient().when(s.getSrcSn()).thenReturn(srcSn);
        lenient().when(s.getFrameNo()).thenReturn(frameNo);
        lenient().when(s.getFrmExpln()).thenReturn(frmExpln);
        lenient().when(s.getSrcFilePathNm()).thenReturn("raw/f" + frameNo + ".jpg");
        lenient().when(s.getDeidFilePath()).thenReturn("deid/f" + frameNo + ".jpg");
        return s;
    }

    private LsDatasetVideoMeta meta(String prvcTypeCd, Integer width, Integer height) {
        LsDatasetVideoMeta m = mock(LsDatasetVideoMeta.class);
        lenient().when(m.getPrvcTypeCd()).thenReturn(prvcTypeCd);
        lenient().when(m.getPrvcYn()).thenReturn("Y");
        lenient().when(m.getVdoWdth()).thenReturn(width);
        lenient().when(m.getVdoHgt()).thenReturn(height);
        return m;
    }

    private String hash(List<LsDataLbl> labels) {
        return hasher.hash(labels, List.of(), null, null);
    }

    @Test
    @DisplayName("동일한_라벨집합은_동일한_해시를_반환한다")
    void sameLabelsSameHash() {
        List<LsDataLbl> a = List.of(
                label(1L, 10L, 100L, "BBOX", "car", "[[1,2],[3,4]]", "t1"),
                label(2L, 10L, 101L, "POLYGON", "person", "[[5,6]]", null));
        List<LsDataLbl> b = List.of(
                label(1L, 10L, 100L, "BBOX", "car", "[[1,2],[3,4]]", "t1"),
                label(2L, 10L, 101L, "POLYGON", "person", "[[5,6]]", null));

        assertThat(hash(a)).isEqualTo(hash(b));
    }

    @Test
    @DisplayName("입력_순서가_달라도_같은_집합이면_해시가_같다 (순서 독립)")
    void orderIndependent() {
        LsDataLbl l1 = label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null);
        LsDataLbl l2 = label(2L, 10L, 101L, "BBOX", "bus", "[[3,4]]", null);

        assertThat(hash(List.of(l1, l2))).isEqualTo(hash(List.of(l2, l1)));
    }

    @Test
    @DisplayName("좌표가_바뀌면_해시가_달라진다")
    void pointsChangeChangesHash() {
        List<LsDataLbl> before = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));
        List<LsDataLbl> after = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[9,9]]", null));

        assertThat(hash(before)).isNotEqualTo(hash(after));
    }

    @Test
    @DisplayName("라벨이_추가되면_해시가_달라진다")
    void labelAddedChangesHash() {
        List<LsDataLbl> before = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));
        List<LsDataLbl> after = List.of(
                label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null),
                label(2L, 10L, 101L, "BBOX", "bus", "[[3,4]]", null));

        assertThat(hash(before)).isNotEqualTo(hash(after));
    }

    @Test
    @DisplayName("빈_리스트와_null은_동일한_고정_해시를_반환한다")
    void emptyAndNullStable() {
        String empty = hash(List.of());
        String nullHash = hash(null);

        assertThat(empty).isEqualTo(nullHash);
        assertThat(empty).hasSize(64);
    }

    @Test
    @DisplayName("프레임설명(frmExpln)만_바뀌어도_해시가_달라진다 (stale 산출물 방지)")
    void frameDescriptionChangeChangesHash() {
        List<LsDataLbl> labels = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));
        String before = hasher.hash(labels, List.of(frame(10L, 0L, "사람 2명")), null, null);
        String after = hasher.hash(labels, List.of(frame(10L, 0L, "사람 3명")), null, null);

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("개인정보플래그(prvcTypeCd)만_바뀌어도_해시가_달라진다 (stale 산출물 방지)")
    void privacyTypeChangeChangesHash() {
        List<LsDataLbl> labels = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));
        List<LsDataSrc> frames = List.of(frame(10L, 0L, "설명"));
        String before = hasher.hash(labels, frames, meta("PRVC", 1920, 1080), null);
        String after = hasher.hash(labels, frames, meta("PSDO", 1920, 1080), null);

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("해상도(width_height)만_바뀌어도_해시가_달라진다")
    void resolutionChangeChangesHash() {
        List<LsDataLbl> labels = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));
        List<LsDataSrc> frames = List.of(frame(10L, 0L, "설명"));
        String before = hasher.hash(labels, frames, meta("PRVC", 1920, 1080), null);
        String after = hasher.hash(labels, frames, meta("PRVC", 1280, 720), null);

        assertThat(before).isNotEqualTo(after);
    }

    /** {@link #frame} + VDO_FRM_NO(영상 내 실제 프레임 위치) 지정 — 산출 JSON {@code frame_num} 원천. */
    private LsDataSrc frameWithVideoFrameNo(Long srcSn, Long frameNo, Long videoFrameNo) {
        LsDataSrc s = frame(srcSn, frameNo, "설명");
        lenient().when(s.getVideoFrameNo()).thenReturn(videoFrameNo);
        return s;
    }

    @Test
    @DisplayName("S11_라벨과_프레임이_같고_vdoFrmNo만_달라도_콘텐츠해시가_달라진다")
    void videoFrameNoChangeChangesHash() {
        // given — 라벨·프레임(추출순번/설명/경로) 동일, VDO_FRM_NO 만 10 → 20
        List<LsDataLbl> labels = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));

        // when
        String before = hasher.hash(labels, List.of(frameWithVideoFrameNo(10L, 0L, 10L)), null, null);
        String after = hasher.hash(labels, List.of(frameWithVideoFrameNo(10L, 0L, 20L)), null, null);

        // then — 산출 JSON 의 frame_num 이 달라지므로 멱등 skip 되면 안 된다(stale 고착 방지).
        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("S11_vdoFrmNo가_null에서_값으로_백필되면_해시가_달라진다")
    void videoFrameNoBackfillChangesHash() {
        // given — 백필 전(null) vs 백필 후(30) — 나머지는 완전히 동일
        List<LsDataLbl> labels = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));

        // when
        String beforeBackfill = hasher.hash(labels, List.of(frameWithVideoFrameNo(10L, 0L, null)), null, null);
        String afterBackfill = hasher.hash(labels, List.of(frameWithVideoFrameNo(10L, 0L, 30L)), null, null);

        // then — 백필분이 재산출되어야 하므로 해시가 갈라진다.
        assertThat(beforeBackfill).isNotEqualTo(afterBackfill);
    }

    private LsDataSrc frameWithPrivacy(Long srcSn, Long frameNo, String anony, String psdo, String prvc) {
        LsDataSrc s = frame(srcSn, frameNo, "설명");
        lenient().when(s.getAnonyInclYn()).thenReturn(anony);
        lenient().when(s.getPsdoInclYn()).thenReturn(psdo);
        lenient().when(s.getPrvcInclYn()).thenReturn(prvc);
        return s;
    }

    @Test
    @DisplayName("개인정보_3필드만_수정후_재승인시_콘텐츠해시_변경되어_재산출")
    void privacyMetaChangeChangesHash() {
        // given — 라벨/설명/메타 동일, 프레임 개인정보 3필드만 변경(가명여부 N→Y)
        List<LsDataLbl> labels = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));
        LsDatasetVideoMeta m = meta("PRVC", 1920, 1080);
        String before = hasher.hash(labels, List.of(frameWithPrivacy(10L, 0L, "N", "N", "Y")), m, null);
        String after = hasher.hash(labels, List.of(frameWithPrivacy(10L, 0L, "N", "Y", "Y")), m, null);

        // then — 해시가 달라져 stale 멱등 skip 이 발생하지 않는다(#2)
        assertThat(before).isNotEqualTo(after);
    }

    /**
     * DEV_FIX 2차 [5] — <b>프레임 축도 blank 정규화 기준이 영상 축과 같다</b>.
     *
     * <p>산출 JSON 은 blank 를 "미입력"으로 보고 기본상수를 싣는데(ExportPrivacyPolicy), 해시만
     * {@code null} 과 {@code " "} 를 다르게 보면 같은 파일이 다른 해시로 갈려 무의미한 전량 재산출이
     * 일어난다. 반대로 <b>코드가 만들 수 있는 값(null/'Y'/'N')에는 이 정규화가 항등</b>이므로 기존
     * 승인분 해시는 그대로다 — 아래 두 단언이 그 둘을 함께 고정한다.
     */
    @Test
    @DisplayName("프레임_개인정보_공백값은_미입력과_같은_해시다 — 축_간_기준_통일")
    void framePrivacyBlankNormalizesLikeNull() {
        // given
        List<LsDataLbl> labels = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));
        LsDatasetVideoMeta m = meta("PRVC", 1920, 1080);

        // when — CHAR(1) 공백만 있는 레거시 행 vs 미입력(null)
        String blank = hasher.hash(labels, List.of(frameWithPrivacy(10L, 0L, " ", " ", " ")), m, null);
        String absent = hasher.hash(labels, List.of(frameWithPrivacy(10L, 0L, null, null, null)), m, null);

        // then — 산출 JSON 이 같으므로 해시도 같아야 한다
        assertThat(blank).isEqualTo(absent);
    }

    @Test
    @DisplayName("프레임_개인정보_YN값의_해시는_정규화_도입_전후로_불변이다")
    void framePrivacyYnValuesStillDistinct() {
        // given — 기존 승인분에 실제로 존재하는 값은 null / 'Y' / 'N' 뿐이다(엔티티 normalizeYn).
        //   그 셋에서는 blankToNull 이 항등이므로 값별 구분이 그대로 유지되어야 한다.
        List<LsDataLbl> labels = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));
        LsDatasetVideoMeta m = meta("PRVC", 1920, 1080);

        String yes = hasher.hash(labels, List.of(frameWithPrivacy(10L, 0L, "Y", "N", "N")), m, null);
        String no = hasher.hash(labels, List.of(frameWithPrivacy(10L, 0L, "N", "N", "N")), m, null);
        String none = hasher.hash(labels, List.of(frameWithPrivacy(10L, 0L, null, null, null)), m, null);

        // then — 셋 다 서로 다른 해시(값이 산출 JSON 에 실제로 반영되므로)
        assertThat(yes).isNotEqualTo(no);
        assertThat(yes).isNotEqualTo(none);
        assertThat(no).isNotEqualTo(none);
    }

    /** 영상 단위 개인정보 수동값(LS_DATA_RAW, V163)을 가진 원시 영상 mock. */
    private kr.co.cudo.authoring.video.entity.LsDataRaw rawWithPrivacy(String anony, String psdo, String prvc) {
        kr.co.cudo.authoring.video.entity.LsDataRaw r =
                mock(kr.co.cudo.authoring.video.entity.LsDataRaw.class);
        lenient().when(r.getAnonyInclYn()).thenReturn(anony);
        lenient().when(r.getPsdoInclYn()).thenReturn(psdo);
        lenient().when(r.getPrvcInclYn()).thenReturn(prvc);
        return r;
    }

    @Test
    @DisplayName("영상단위_개인정보_수동값만_수정해도_콘텐츠해시가_변경되어_재산출된다")
    void videoPrivacyMetaChangeChangesHash() {
        // given — 라벨/프레임/메타 동일, 영상 단위 수동값만 변경(개인정보포함 N→Y).
        //   빠지면 저장은 됐는데 export 가 멱등 skip 되어 파일이 옛 값으로 고착된다.
        List<LsDataLbl> labels = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));
        List<LsDataSrc> frames = List.of(frame(10L, 0L, "설명"));
        LsDatasetVideoMeta m = meta("PRVC", 1920, 1080);

        String before = hasher.hash(labels, frames, m, rawWithPrivacy("Y", "N", "N"));
        String after = hasher.hash(labels, frames, m, rawWithPrivacy("Y", "N", "Y"));

        // then
        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("영상단위_수동값이_전부_미입력이면_기존_해시가_그대로_유지된다")
    void videoPrivacyMetaAbsentKeepsLegacyHash() {
        // given — V163 이전에 승인된(수동값 없는) 영상. 무조건 3필드를 해시에 붙이면 전 영상이
        //   재산출되므로, 값이 하나도 없으면 아무것도 append 하지 않아야 한다(하위호환).
        List<LsDataLbl> labels = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));
        List<LsDataSrc> frames = List.of(frame(10L, 0L, "설명"));
        LsDatasetVideoMeta m = meta("PRVC", 1920, 1080);

        // when — raw 자체가 없는 경우 vs raw 는 있으나 수동값이 전부 null 인 경우
        String withoutRaw = hasher.hash(labels, frames, m, null);
        String withEmptyRaw = hasher.hash(labels, frames, m, rawWithPrivacy(null, null, null));

        // then — 동일 해시(재산출 폭증 방지)
        assertThat(withoutRaw).isEqualTo(withEmptyRaw);
    }

    @Test
    @DisplayName("영상단위_수동값과_프레임_수동값은_서로_다른_축이라_해시가_구분된다")
    void videoAndFramePrivacyAxesAreDistinctInHash() {
        // given — 같은 값(개인정보포함 Y)을 영상 축에만 / 프레임 축에만 저장
        List<LsDataLbl> labels = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));
        LsDatasetVideoMeta m = meta("PRVC", 1920, 1080);

        String videoAxis = hasher.hash(labels, List.of(frame(10L, 0L, "설명")), m,
                rawWithPrivacy(null, null, "Y"));
        String frameAxis = hasher.hash(labels, List.of(frameWithPrivacy(10L, 0L, null, null, "Y")), m, null);

        // then — 산출 JSON 이 다르므로(video vs image) 해시도 달라야 한다
        assertThat(videoAxis).isNotEqualTo(frameAxis);
    }

    @Test
    @DisplayName("라벨_프레임_메타가_모두_동일하면_해시가_같다")
    void identicalInputsSameHash() {
        List<LsDataLbl> labels = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));
        List<LsDataSrc> frames = List.of(frame(10L, 0L, "설명"));
        String h1 = hasher.hash(labels, frames, meta("PRVC", 1920, 1080), null);
        String h2 = hasher.hash(labels, frames, meta("PRVC", 1920, 1080), null);

        assertThat(h1).isEqualTo(h2);
    }

    // ------------------------------------------------------------ 원천 축 (2026-08-04 전환)

    @Test
    @DisplayName("원천축_개인정보가_바뀌면_해시가_달라진다")
    void 원천축_개인정보가_바뀌면_해시가_달라진다() {
        // given — 관제가 원천 판정을 정정해 재송신한 경우(승인 후 재산출 트리거 시 반영돼야 한다)
        List<LsDataLbl> labels = List.of();
        List<LsDataSrc> frames = List.of();

        // when
        String before = hasher.hash(labels, frames, meta("PRVC", 1920, 1080), null,
                SourcePrivacyMeta.ofIngest("N", "N", "Y"));
        String after = hasher.hash(labels, frames, meta("PRVC", 1920, 1080), null,
                SourcePrivacyMeta.ofIngest("Y", "N", "N"));

        // then — 빠지면 재승인이 멱등 skip 되어 저장은 바뀌었는데 export 는 옛 값으로 고착된다.
        assertThat(before).isNotEqualTo(after);
    }

    @Test
    @DisplayName("원천축_값이_없으면_기존_해시가_유지된다")
    void 원천축_값이_없으면_기존_해시가_유지된다() {
        // given / when — 4인자(구) 호출 == NONE == 값 전무한 인입
        List<LsDataLbl> labels = List.of();
        List<LsDataSrc> frames = List.of();
        String legacy = hasher.hash(labels, frames, meta("PRVC", 1920, 1080), null);
        String none = hasher.hash(labels, frames, meta("PRVC", 1920, 1080), null,
                SourcePrivacyMeta.NONE);
        String silent = hasher.hash(labels, frames, meta("PRVC", 1920, 1080), null,
                SourcePrivacyMeta.ofIngest(null, null, " "));

        // then — 하위호환: 이 변경 이전 승인분(관제 미송신)이 전량 재산출되지 않는다.
        assertThat(none).isEqualTo(legacy);
        assertThat(silent).isEqualTo(legacy);
    }
}
