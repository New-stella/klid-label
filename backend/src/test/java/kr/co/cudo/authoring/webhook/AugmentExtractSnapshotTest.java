package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.service.AugmentExtractPlan;
import kr.co.cudo.authoring.webhook.service.AugmentExtractSnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase A(검증·스냅샷) 단위 테스트 — 커넥션-점유 분리 리팩터.
 *
 * <p>멱등 가드, 부모 프레임 조회, 중복 videoFrameNo fail-fast, 프레임별 산출 경로·번호 스냅샷을 검증한다.
 * 부모 잠금·비식별 재검증을 하지 않는(설계 유지) 무잠금 findById 만 사용함을 반영한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AugmentExtractSnapshotTest {

    @Mock VideoRepository videoRepository;
    @Mock LsDataAugRepository augRepository;
    @Mock LsDataSrcRepository srcRepository;

    private AugmentExtractSnapshot snapshot;

    @BeforeEach
    void setup() {
        snapshot = new AugmentExtractSnapshot(videoRepository, augRepository, srcRepository);
        ReflectionTestUtils.setField(snapshot, "storageRawPath", "/tmp/klid-store");
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private LsDataRaw newAugRaw(Long rawSn, Long parentRawSn, String filePath) {
        LsDataRaw parent = LsDataRaw.createFromIngest(
                "clip-" + parentRawSn, "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/" + parentRawSn + ".mp4", null, 60);
        setField(parent, "rawSn", parentRawSn);
        LsDataRaw aug = LsDataRaw.createFromAugment(parent, filePath, "WINTER");
        setField(aug, "rawSn", rawSn);
        return aug;
    }

    private LsDataSrc parentFrame(Long srcSn, Long rawSn, int frameNo, Long videoFrameNo) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, videoFrameNo, rawSn + "/f" + frameNo + ".jpg", null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    private LsDataAug aug(Long augSn) {
        LsDataAug a = LsDataAug.createPending(600L, "WINTER", BigDecimal.valueOf(0.9), "registrar");
        setField(a, "dataAugSn", augSn);
        return a;
    }

    @Test
    @DisplayName("deIdntfYn_N신규RAW의_프레임스펙과_산출경로가_정확히_스냅샷된다")
    void buildsPlanWithFrameSpecs() {
        LsDataRaw newRaw = newAugRaw(9001L, 100L, "/storage/augment/winter.mp4");
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("N");
        LsDataSrc pf0 = parentFrame(600L, 100L, 0, 100L);
        LsDataSrc pf1 = parentFrame(601L, 100L, 1, 250L);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(20L)).thenReturn(Optional.of(aug(20L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(pf0, pf1));

        Optional<AugmentExtractPlan> opt = snapshot.snapshot(9001L, 20L);

        assertThat(opt).isPresent();
        AugmentExtractPlan plan = opt.get();
        assertThat(plan.newRawSn()).isEqualTo(9001L);
        assertThat(plan.parentRawSn()).isEqualTo(100L);
        assertThat(plan.dataAugSn()).isEqualTo(20L);
        assertThat(plan.sourceVideo().toString()).isEqualTo("/storage/augment/winter.mp4");
        assertThat(plan.frames()).hasSize(2);
        // FRM_NO = 추출 순번(0,1), VDO_FRM_NO = 부모 프레임 번호(100,250), parentSrcSn 보존.
        assertThat(plan.frames()).extracting(AugmentExtractPlan.FrameSpec::frameNo).containsExactly(0L, 1L);
        assertThat(plan.frames()).extracting(AugmentExtractPlan.FrameSpec::videoFrameNo).containsExactly(100L, 250L);
        assertThat(plan.frames()).extracting(AugmentExtractPlan.FrameSpec::parentSrcSn).containsExactly(600L, 601L);
        // 산출 경로 = {base}/frames/raw/{rawSn}/frame-{i}.jpg (CWE-22 검증).
        assertThat(plan.frames().get(0).dst().toString().replace('\\', '/'))
                .endsWith("/frames/raw/9001/frame-0.jpg");
        assertThat(plan.framesDir().toString().replace('\\', '/')).endsWith("/frames/raw/9001");
    }

    @Test
    @DisplayName("이미_비식별완료된_신규RAW면_멱등_empty반환_부모조회_안함")
    void alreadyFinalized_returnsEmpty() {
        LsDataRaw newRaw = newAugRaw(9007L, 160L, "/storage/augment/done.mp4");
        newRaw.markDeidentified("Y");
        when(videoRepository.findById(9007L)).thenReturn(Optional.of(newRaw));

        Optional<AugmentExtractPlan> opt = snapshot.snapshot(9007L, 72L);

        assertThat(opt).isEmpty();
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(org.mockito.ArgumentMatchers.anyLong());
        verify(augRepository, never()).findById(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("부모_중복_videoFrameNo면_고아없이_실패처리")
    void duplicateParentVideoFrameNo_failsFast() {
        LsDataRaw newRaw = newAugRaw(9009L, 103L, "/storage/augment/dup.mp4");
        LsDataSrc pf0 = parentFrame(320L, 103L, 0, 100L);
        LsDataSrc pf1 = parentFrame(321L, 103L, 1, 100L); // 같은 videoFrameNo — 오손
        when(videoRepository.findById(9009L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(81L)).thenReturn(Optional.of(aug(81L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(103L)).thenReturn(List.of(pf0, pf1));

        assertThatThrownBy(() -> snapshot.snapshot(9009L, 81L))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("부모_프레임_0건이면_INTERNAL_ERROR")
    void emptyParentFrames_failsFast() {
        LsDataRaw newRaw = newAugRaw(9010L, 104L, "/storage/augment/empty.mp4");
        when(videoRepository.findById(9010L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(82L)).thenReturn(Optional.of(aug(82L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(104L)).thenReturn(List.of());

        assertThatThrownBy(() -> snapshot.snapshot(9010L, 82L))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("증강_소스경로가_비면_INVALID_INPUT")
    void blankSourcePath_rejected() {
        LsDataRaw newRaw = newAugRaw(9011L, 105L, "   ");
        when(videoRepository.findById(9011L)).thenReturn(Optional.of(newRaw));

        assertThatThrownBy(() -> snapshot.snapshot(9011L, 83L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("신규RAW가_없으면_NOT_FOUND")
    void newRawNotFound() {
        when(videoRepository.findById(9012L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> snapshot.snapshot(9012L, 84L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("NOT_FOUND");
    }
}
