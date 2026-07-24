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

    @Test
    @DisplayName("라벨_프레임_메타가_모두_동일하면_해시가_같다")
    void identicalInputsSameHash() {
        List<LsDataLbl> labels = List.of(label(1L, 10L, 100L, "BBOX", "car", "[[1,2]]", null));
        List<LsDataSrc> frames = List.of(frame(10L, 0L, "설명"));
        String h1 = hasher.hash(labels, frames, meta("PRVC", 1920, 1080), null);
        String h2 = hasher.hash(labels, frames, meta("PRVC", 1920, 1080), null);

        assertThat(h1).isEqualTo(h2);
    }
}
