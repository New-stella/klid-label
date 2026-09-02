package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJobFile;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobFileRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase A(검증·스냅샷) 단위 테스트 — 커넥션-점유 분리 리팩터 + Phase 7-D(외부 산출 반영).
 *
 * <p>멱등 가드, 부모 프레임 조회, 중복 videoFrameNo fail-fast, <b>외부 산출물 대응 복원(위탁 매핑)</b>과
 * 그 fail-closed 조건, 산출 경로(비식별 서브트리) 스냅샷을 검증한다. 부모 잠금·비식별 재검증을 하지
 * 않는(설계 유지) 무잠금 findById 만 사용함을 반영한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AugmentExtractSnapshotTest {

    @Mock VideoRepository videoRepository;
    @Mock LsDataAugRepository augRepository;
    @Mock LsDataSrcRepository srcRepository;
    @Mock LsDataAugJobFileRepository jobFileRepository;
    @Mock LsDeidentProcLogRepository deidentProcLogRepository;

    private AugmentExtractSnapshot snapshot;

    @BeforeEach
    void setup() {
        // 복사 원본 경로 조달은 단일 진실원(DerivativeSourceVideoResolver)이 한다 — 이 클래스는
        //   더 이상 처리 이력을 직접 읽지 않는다(V28/ADR-058). 포털 설정은 이 시나리오에서 타지 않는다.
        var sourceResolver = new kr.co.cudo.authoring.video.service.DerivativeSourceVideoResolver(
                deidentProcLogRepository, null, null);
        ReflectionTestUtils.setField(sourceResolver, "storageDeidentifiedPath", "/tmp/klid-deid");
        snapshot = new AugmentExtractSnapshot(videoRepository, augRepository, srcRepository, jobFileRepository,
                sourceResolver);
        ReflectionTestUtils.setField(snapshot, "storageDeidentifiedPath", "/tmp/klid-deid");
    }

    /** 부모 비식별 <영상> 경로(procLog 값) 스텁 — 파일명은 조합하지 않고 적재된 값을 쓴다. */
    private void givenParentDeidVideo(Long parentRawSn, String path) {
        LsDeidentProcLog procLog = LsDeidentProcLog.request(parentRawSn, null, "/storage/raw/x.mp4", "test");
        procLog.succeed(path);
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(parentRawSn))
                .thenReturn(Optional.of(procLog));
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
        // 복사 원본 경로 조달기가 부모 행을 읽는다(출처가 관제인지 포털인지를 그 행이 말한다).
        //   흡수 이전에는 처리 이력만 읽어 부모 행이 필요 없었다(V28/ADR-058).
        when(videoRepository.findById(parentRawSn)).thenReturn(Optional.of(parent));
        LsDataRaw aug = LsDataRaw.createFromAugment(parent, filePath, "WINTER", rawSn);
        setField(aug, "rawSn", rawSn);
        return aug;
    }

    /** 부모 프레임 — 위탁 입력이 된 <b>비식별</b> 프레임 경로를 보유한다. */
    private LsDataSrc parentFrame(Long srcSn, Long rawSn, int frameNo, Long videoFrameNo) {
        LsDataSrc src = LsDataSrc.create(rawSn, frameNo, videoFrameNo, null,
                "/tmp/klid-deid/frames/deid/" + rawSn + "/frame-" + frameNo + ".jpg", null);
        setField(src, "srcSn", srcSn);
        return src;
    }

    private LsDataAug aug(Long augSn) {
        LsDataAug a = LsDataAug.createPending(600L, "WINTER", BigDecimal.valueOf(0.9), "registrar");
        setField(a, "dataAugSn", augSn);
        return a;
    }

    /** 위탁 매핑 1건(결과 경로 적재 완료). */
    private LsDataAugJobFile mapping(Long augJobSn, int fileSeq, Long srcSn, String resultPath) {
        LsDataAugJobFile f = LsDataAugJobFile.issued(augJobSn, fileSeq, srcSn);
        if (resultPath != null) {
            f.applyResultPath(resultPath);
        }
        return f;
    }

    private void givenMappings(Long dataAugSn, List<LsDataAugJobFile> mappings) {
        when(jobFileRepository.findByDataAugSnOrderByJobAndFileSeq(dataAugSn)).thenReturn(mappings);
    }

    @Test
    @DisplayName("외부_산출_경로가_위탁매핑을_통해_프레임_생성계획까지_전달된다")
    void buildsPlanWithExternalOutputs() {
        // given
        LsDataRaw newRaw = newAugRaw(9001L, 100L, "/storage/augment/winter.mp4");
        assertThat(newRaw.getDeIdntfYn()).isEqualTo("N");
        LsDataSrc pf0 = parentFrame(600L, 100L, 0, 100L);
        LsDataSrc pf1 = parentFrame(601L, 100L, 1, 250L);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(20L)).thenReturn(Optional.of(aug(20L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(100L)).thenReturn(List.of(pf0, pf1));
        givenMappings(20L, List.of(
                mapping(1L, 1, 600L, "/nas/genai/job-1/a_gen.jpg"),
                mapping(1L, 2, 601L, "/nas/genai/job-1/b_gen.png")));
        givenParentDeidVideo(100L, "/tmp/klid-deid/videos/100/deidentified.mp4");

        // when
        Optional<AugmentExtractPlan> opt = snapshot.snapshot(9001L, 20L);

        // then — 각 프레임에 자기 산출물이 붙는다(부모 재추출 소스 없음).
        assertThat(opt).isPresent();
        AugmentExtractPlan plan = opt.get();
        assertThat(plan.newRawSn()).isEqualTo(9001L);
        assertThat(plan.parentRawSn()).isEqualTo(100L);
        assertThat(plan.frames()).hasSize(2);
        assertThat(plan.frames()).extracting(f -> f.externalSource().toString())
                .containsExactly("/nas/genai/job-1/a_gen.jpg", "/nas/genai/job-1/b_gen.png");
        assertThat(plan.frames()).extracting(AugmentExtractPlan.FrameSpec::frameNo).containsExactly(0L, 1L);
        assertThat(plan.frames()).extracting(AugmentExtractPlan.FrameSpec::videoFrameNo).containsExactly(100L, 250L);
        assertThat(plan.frames()).extracting(AugmentExtractPlan.FrameSpec::parentSrcSn).containsExactly(600L, 601L);
        // 산출 경로 = {deidBase}/frames/deid/{rawSn}/frame-{i}.{외부 확장자} (CWE-22 + PII 서브트리).
        assertThat(plan.frames().get(0).dst().toString().replace('\\', '/'))
                .endsWith("/frames/deid/9001/frame-0.jpg");
        assertThat(plan.frames().get(1).dst().toString().replace('\\', '/'))
                .endsWith("/frames/deid/9001/frame-1.png");
        assertThat(plan.framesDir().toString().replace('\\', '/')).endsWith("/frames/deid/9001");
        // 해상도 기준 = 위탁했던 부모 비식별 프레임.
        assertThat(plan.referenceFrame().toString().replace('\\', '/'))
                .isEqualTo("/tmp/klid-deid/frames/deid/100/frame-0.jpg");
    }

    @Test
    @DisplayName("복사_소스는_부모의_비식별_영상이며_원본이_아니다")
    void videoCopySourceIsParentDeidentifiedVideo() {
        // given — 부모 원본은 /storage/raw/110.mp4, 비식별본은 procLog 에 적재된 값(파일명 고정 아님).
        LsDataRaw newRaw = newAugRaw(9020L, 110L, "/storage/raw/110.mp4");
        when(videoRepository.findById(9020L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(30L)).thenReturn(Optional.of(aug(30L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(110L))
                .thenReturn(List.of(parentFrame(600L, 110L, 0, 100L)));
        givenMappings(30L, List.of(mapping(1L, 1, 600L, "/nas/genai/job-1/a.jpg")));
        givenParentDeidVideo(110L, "/tmp/klid-deid/videos/110/110-mask.mp4");

        // when
        AugmentExtractPlan plan = snapshot.snapshot(9020L, 30L).orElseThrow();

        // then — 소스는 procLog 값(비식별본) 그대로이며 원본 경로가 아니다.
        assertThat(plan.deidVideoSrc().toString().replace('\\', '/'))
                .isEqualTo("/tmp/klid-deid/videos/110/110-mask.mp4");
        assertThat(plan.deidVideoSrc().toString()).isNotEqualTo("/storage/raw/110.mp4");
        // 목적지 = 파생 전용 경로(부모 파일과 겹치지 않음) — 해상도 파생과 동일 규약.
        assertThat(plan.videoDst().toString().replace('\\', '/'))
                .isEqualTo("/tmp/klid-deid/videos/augment/110/9020/WINTER.mp4");
        assertThat(plan.videoDst()).isNotEqualTo(plan.deidVideoSrc());
    }

    @Test
    @DisplayName("부모_비식별_영상경로가_없으면_원본으로_폴백하지_않고_실패한다")
    void missingParentDeidVideo_failsClosed() {
        LsDataRaw newRaw = newAugRaw(9021L, 111L, "/storage/raw/111.mp4");
        when(videoRepository.findById(9021L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(31L)).thenReturn(Optional.of(aug(31L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(111L))
                .thenReturn(List.of(parentFrame(600L, 111L, 0, 100L)));
        givenMappings(31L, List.of(mapping(1L, 1, 600L, "/nas/genai/job-1/a.jpg")));
        // 부모 SUCCESS procLog 없음 — 폴백하면 원본(비-비식별)을 복제하게 된다.
        when(deidentProcLogRepository.findLatestSuccessByDataRawSn(111L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> snapshot.snapshot(9021L, 31L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("부모_비식별_영상경로가_허용_비식별_저장경로_밖이면_거부된다")
    void parentDeidVideoOutsideDeidBase_rejected() {
        LsDataRaw newRaw = newAugRaw(9022L, 112L, "/storage/raw/112.mp4");
        when(videoRepository.findById(9022L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(32L)).thenReturn(Optional.of(aug(32L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(112L))
                .thenReturn(List.of(parentFrame(600L, 112L, 0, 100L)));
        givenMappings(32L, List.of(mapping(1L, 1, 600L, "/nas/genai/job-1/a.jpg")));
        // 비식별 저장소 밖(원본 저장소) 경로 — 원본 픽셀 복제를 막기 위해 fail-secure.
        givenParentDeidVideo(112L, "/storage/raw/112.mp4");

        assertThatThrownBy(() -> snapshot.snapshot(9022L, 32L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("위탁_후_프레임이_추가돼도_매핑이_어긋나지_않는다")
    void mappingSurvivesFrameDrift() {
        // given — 위탁은 프레임 2건(600,601)에 대해 했는데, 그 뒤 프레임 1건(602)이 추가됐다.
        LsDataRaw newRaw = newAugRaw(9002L, 101L, "/storage/augment/drift.mp4");
        LsDataSrc pf0 = parentFrame(600L, 101L, 0, 100L);
        LsDataSrc pf1 = parentFrame(601L, 101L, 1, 250L);
        LsDataSrc added = parentFrame(602L, 101L, 2, 400L);
        when(videoRepository.findById(9002L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(21L)).thenReturn(Optional.of(aug(21L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(101L)).thenReturn(List.of(pf0, pf1, added));
        givenMappings(21L, List.of(
                mapping(1L, 1, 600L, "/nas/genai/job-1/a_gen.jpg"),
                mapping(1L, 2, 601L, "/nas/genai/job-1/b_gen.jpg")));

        // when / then — 순서 재계산이었다면 a_gen 이 600, b_gen 이 601 에 "그럴듯하게" 붙고 602 만 비어
        //               조용히 어긋났을 것이다. 매핑 기반이므로 건수 불일치로 즉시 실패한다.
        assertThatThrownBy(() -> snapshot.snapshot(9002L, 21L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("CONFLICT");
    }

    @Test
    @DisplayName("3개_job_의_결과가_JOB_SEQ_순서로_전체_프레임_순서를_복원한다")
    void restoresGlobalOrderAcrossJobs() {
        // given — 프레임 3건이 job 3개로 쪼개져 위탁됐고, 리포지토리가 (JOB_SEQ, FILE_SEQ) 순으로 준다.
        LsDataRaw newRaw = newAugRaw(9003L, 102L, "/storage/augment/multi.mp4");
        List<LsDataSrc> parents = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            parents.add(parentFrame(700L + i, 102L, i, 100L * (i + 1)));
        }
        when(videoRepository.findById(9003L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(22L)).thenReturn(Optional.of(aug(22L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(102L)).thenReturn(parents);
        givenMappings(22L, List.of(
                mapping(1L, 1, 700L, "/nas/genai/job-1/1.jpg"),
                mapping(2L, 2, 701L, "/nas/genai/job-2/2.jpg"),
                mapping(3L, 3, 702L, "/nas/genai/job-3/3.jpg")));
        givenParentDeidVideo(102L, "/tmp/klid-deid/videos/102/deidentified.mp4");

        // when
        AugmentExtractPlan plan = snapshot.snapshot(9003L, 22L).orElseThrow();

        // then — 프레임 순서(FRM_NO 0,1,2)와 job 순서 산출물이 1:1 로 맞물린다.
        assertThat(plan.frames()).extracting(f -> f.externalSource().toString())
                .containsExactly("/nas/genai/job-1/1.jpg", "/nas/genai/job-2/2.jpg", "/nas/genai/job-3/3.jpg");
    }

    @Test
    @DisplayName("위탁매핑이_없으면_부모_재추출로_폴백하지_않고_실패한다")
    void missingMapping_failsClosed() {
        LsDataRaw newRaw = newAugRaw(9004L, 103L, "/storage/augment/nomap.mp4");
        when(videoRepository.findById(9004L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(23L)).thenReturn(Optional.of(aug(23L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(103L))
                .thenReturn(List.of(parentFrame(600L, 103L, 0, 100L)));
        givenMappings(23L, List.of());

        assertThatThrownBy(() -> snapshot.snapshot(9004L, 23L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("CONFLICT");
    }

    @Test
    @DisplayName("산출_경로가_비어있는_매핑이_있으면_실패한다")
    void blankResultPath_failsClosed() {
        LsDataRaw newRaw = newAugRaw(9005L, 106L, "/storage/augment/blank.mp4");
        when(videoRepository.findById(9005L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(24L)).thenReturn(Optional.of(aug(24L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(106L))
                .thenReturn(List.of(parentFrame(600L, 106L, 0, 100L)));
        givenMappings(24L, List.of(mapping(1L, 1, 600L, null)));

        assertThatThrownBy(() -> snapshot.snapshot(9005L, 24L))
                .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("이미지가_아닌_확장자의_산출물은_거부된다")
    void nonImageOutput_rejected() {
        LsDataRaw newRaw = newAugRaw(9006L, 107L, "/storage/augment/exe.mp4");
        when(videoRepository.findById(9006L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(25L)).thenReturn(Optional.of(aug(25L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(107L))
                .thenReturn(List.of(parentFrame(600L, 107L, 0, 100L)));
        givenMappings(25L, List.of(mapping(1L, 1, 600L, "/nas/genai/job-1/payload.sh")));

        assertThatThrownBy(() -> snapshot.snapshot(9006L, 25L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("INVALID_INPUT");
    }

    @Test
    @DisplayName("부모_비식별_프레임_경로가_없으면_해상도_기준을_원본으로_대체하지_않고_실패한다")
    void missingParentDeidPath_failsClosed() {
        LsDataRaw newRaw = newAugRaw(9008L, 108L, "/storage/augment/nodeid.mp4");
        LsDataSrc pf0 = LsDataSrc.create(108L, 0, 100L, "/storage/raw/108/f0.jpg", null);
        setField(pf0, "srcSn", 600L);
        when(videoRepository.findById(9008L)).thenReturn(Optional.of(newRaw));
        when(augRepository.findById(26L)).thenReturn(Optional.of(aug(26L)));
        when(srcRepository.findByRawSnOrderByFrameNoAsc(108L)).thenReturn(List.of(pf0));
        givenMappings(26L, List.of(mapping(1L, 1, 600L, "/nas/genai/job-1/a.jpg")));

        assertThatThrownBy(() -> snapshot.snapshot(9008L, 26L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode().name())
                .isEqualTo("CONFLICT");
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
        verify(jobFileRepository, never())
                .findByDataAugSnOrderByJobAndFileSeq(org.mockito.ArgumentMatchers.anyLong());
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
        givenMappings(81L, List.of(
                mapping(1L, 1, 320L, "/nas/genai/job-1/a.jpg"),
                mapping(1L, 2, 321L, "/nas/genai/job-1/b.jpg")));

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
    @DisplayName("증강_영상경로가_비면_INVALID_INPUT")
    void blankVideoPath_rejected() {
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
