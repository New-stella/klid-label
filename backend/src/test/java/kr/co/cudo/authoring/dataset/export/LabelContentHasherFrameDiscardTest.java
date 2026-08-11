package kr.co.cudo.authoring.dataset.export;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R4 — 프레임 폐기가 산출 콘텐츠 해시(멱등 판정 키)에 반영되는지 검증.
 *
 * <p>해시에 폐기 축이 없으면 <b>폐기했는데 export 가 멱등 skip 되어</b> 산출물에 그 프레임이 그대로
 * 남는다(저장은 됐는데 파일이 안 바뀌는 결함). 반대로 무조건 편입하면 이 변경 이전에 승인된 전
 * 영상의 해시가 달라져 전량 재산출된다 — 그래서 이 파일의 다른 조건부 블록
 * ({@code VPRV}/{@code SPRV}/{@code VDSC})과 같은 규약으로 <b>폐기 프레임이 있을 때만</b> 붙인다.
 *
 * @design D1
 * @req R4
 */
class LabelContentHasherFrameDiscardTest {

    private final LabelContentHasher hasher = new LabelContentHasher();

    private LsDataSrc frame(long rawSn, int frameNo) {
        return LsDataSrc.create(rawSn, frameNo, "/raw/f" + frameNo + ".jpg", null);
    }

    @Test
    @DisplayName("폐기_프레임이_없으면_폐기축_도입_전과_해시가_동일하다_기존_승인분_전량재산출_방지")
    void 폐기_프레임이_없으면_폐기축_도입_전과_해시가_동일하다_기존_승인분_전량재산출_방지() {
        List<LsDataSrc> frames = List.of(frame(1L, 0), frame(1L, 1));

        String legacy = hasher.hash(List.of(), frames, null, null);
        String withEmptyDiscard = hasher.hash(List.of(), frames, null, null,
                SourcePrivacyMeta.NONE, null, List.of());

        assertThat(withEmptyDiscard).isEqualTo(legacy);
    }

    @Test
    @DisplayName("프레임을_폐기하면_해시가_달라져_산출이_멱등skip_되지_않는다")
    void 프레임을_폐기하면_해시가_달라져_산출이_멱등skip_되지_않는다() {
        List<LsDataSrc> frames = List.of(frame(1L, 0), frame(1L, 1));

        String before = hasher.hash(List.of(), frames, null, null,
                SourcePrivacyMeta.NONE, null, List.of());
        String after = hasher.hash(List.of(), frames, null, null,
                SourcePrivacyMeta.NONE, null, List.of(77L));

        assertThat(after).isNotEqualTo(before);
    }

    @Test
    @DisplayName("어느_프레임을_폐기했는지에_따라_해시가_달라진다")
    void 어느_프레임을_폐기했는지에_따라_해시가_달라진다() {
        List<LsDataSrc> frames = List.of(frame(1L, 0), frame(1L, 1));

        String a = hasher.hash(List.of(), frames, null, null, SourcePrivacyMeta.NONE, null, List.of(77L));
        String b = hasher.hash(List.of(), frames, null, null, SourcePrivacyMeta.NONE, null, List.of(78L));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("폐기_프레임_목록의_순서가_달라도_해시는_같다_결정성")
    void 폐기_프레임_목록의_순서가_달라도_해시는_같다_결정성() {
        List<LsDataSrc> frames = List.of(frame(1L, 0), frame(1L, 1));

        String asc = hasher.hash(List.of(), frames, null, null,
                SourcePrivacyMeta.NONE, null, List.of(77L, 78L));
        String desc = hasher.hash(List.of(), frames, null, null,
                SourcePrivacyMeta.NONE, null, List.of(78L, 77L));

        assertThat(asc).isEqualTo(desc);
    }

    @Test
    @DisplayName("복원하면_폐기_이전_해시로_되돌아간다")
    void 복원하면_폐기_이전_해시로_되돌아간다() {
        List<LsDataSrc> frames = List.of(frame(1L, 0), frame(1L, 1));

        String before = hasher.hash(List.of(), frames, null, null,
                SourcePrivacyMeta.NONE, null, List.of());
        String discarded = hasher.hash(List.of(), frames, null, null,
                SourcePrivacyMeta.NONE, null, List.of(77L));
        String restored = hasher.hash(List.of(), frames, null, null,
                SourcePrivacyMeta.NONE, null, List.of());

        // 폐기 상태에서 마지막 산출이 이뤄졌으므로, 복원 시 해시가 그 값과 달라야 재산출된다.
        assertThat(restored).isEqualTo(before).isNotEqualTo(discarded);
    }
}
