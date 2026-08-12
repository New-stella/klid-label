package kr.co.cudo.authoring.batch.runner;

import kr.co.cudo.authoring.batch.listener.VlmResumeBridge;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.step.VlmTimeseriesStep;
import kr.co.cudo.authoring.batch.vlm.VlmTimeseriesMetaPresence;
import kr.co.cudo.authoring.label.event.DeidentGateReopenedEvent;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.service.VideoMetaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 신고 해소 후 <b>보류됐던 VLM 시계열 위탁이 실제로 재개되는가</b> — 결손 방지 회귀 가드
 * (2026-07-29, MEDIUM "VLM SKIPPED 에 재개 트리거가 없다").
 *
 * <p>보류(SKIPPED)는 실패 행을 남기지 않아 재시도 큐·실패 회수기가 집지 않는다. 그래서 해제 시점의
 * 재트리거가 <b>유일한 복구 경로</b>이며, 그 배선(이벤트 → 브릿지 → 러너 → 스텝)이 하나라도 끊기면
 * 시계열 메타가 영구 결손된다. 아래 테스트는 배선 각 마디를 지우면 RED 가 되도록 구성했다.
 */
class VlmWithheldResumeRunnerTest {

    private static final long RAW_SN = 7700L;

    private VlmTimeseriesStep vlmTimeseriesStep;
    private BatchStatusService batchStatusService;
    private LsDataMetaRepository metaRepository;
    private LsMarkingRepository markingRepository;
    private VlmWithheldResumeRunner runner;

    @BeforeEach
    void setUp() {
        vlmTimeseriesStep = mock(VlmTimeseriesStep.class);
        batchStatusService = mock(BatchStatusService.class);
        metaRepository = mock(LsDataMetaRepository.class);
        markingRepository = mock(LsMarkingRepository.class);
        // ★ 판정 컴포넌트는 <b>실제 객체</b>로 조립한다 — 시계열 카운트 판정이
        //   VlmTimeseriesMetaPresence 로 공용화(스텝의 재실행 멱등 가드와 공유)되었으나, 이 가드가 고정하는
        //   것은 "러너가 결국 어떤 쿼리를 어떤 상수로 부르는가" 이므로 모킹하면 그 축이 사라진다.
        runner = new VlmWithheldResumeRunner(vlmTimeseriesStep, batchStatusService,
                new VlmTimeseriesMetaPresence(metaRepository), markingRepository);
    }

    /**
     * 미수행 기록 존재 여부 stub — 사유 목록 상수가 바뀌면 배선이 끊기므로 상수 자체를 매칭한다.
     *
     * <p>Phase C-1 에서 재개 사유가 셋(신고 보류 · 비동기 제출 실패 · ACK 미수신 회수)으로 늘어
     * 판정이 {@code isStageSkippedWithAnyReason} 으로 바뀌었다.
     */
    private void stubWithheld(boolean withheld) {
        when(batchStatusService.isStageSkippedWithAnyReason(
                RAW_SN, BatchStage.VLM, VlmTimeseriesStep.RESUMABLE_SKIP_REASONS))
                .thenReturn(withheld);
    }

    /**
     * 메타 적재 상태 stub — <b>전체 건수</b>와 <b>시계열 건수</b>를 따로 심는다.
     *
     * <p>{@code LS_DATA_META} 에는 VLM 시계열 메타와 {@code video.*} 기술메타가 섞여 있어, 전체 카운트로
     * 멱등을 판정하면 "기술메타만 있고 시계열은 0건"인 영상(= 재개가 필요한 바로 그 상태)이 영구히
     * skip 된다. 두 값을 갈라 심어야 그 오산입을 테스트가 잡는다.
     */
    private void stubMetaCounts(long totalCount, long timeseriesCount) {
        when(metaRepository.countByRawSn(RAW_SN)).thenReturn(totalCount);
        when(metaRepository.countTimeseriesByRawSn(RAW_SN, VideoMetaService.KEY_PREFIX))
                .thenReturn(timeseriesCount);
    }

    @Test
    @DisplayName("신고_보류됐던_VLM_위탁은_해제_후_재위탁된다")
    void resumesWithheldSubmit() {
        stubWithheld(true);
        stubMetaCounts(0L, 0L);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(RAW_SN)).thenReturn(List.of());

        runner.resumeAsync(RAW_SN);

        verify(vlmTimeseriesStep).run(RAW_SN);
    }

    @Test
    @DisplayName("마킹이_있으면_마킹_전이를_포함한_경로로_재위탁된다")
    void resumesWithMarkingWhenPresent() {
        stubWithheld(true);
        stubMetaCounts(0L, 0L);
        LsMarking marking = mock(LsMarking.class);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(RAW_SN))
                .thenReturn(List.of(marking));

        runner.resumeAsync(RAW_SN);

        // 마킹 상태 전이(PENDING → VLM_REQUESTED)까지 원래 파이프라인과 동일하게 이어져야 한다.
        verify(vlmTimeseriesStep).runWithMarking(RAW_SN, marking);
        verify(vlmTimeseriesStep, never()).run(anyLong());
    }

    @Test
    @DisplayName("보류_기록이_없으면_재위탁하지_않는다")
    void skipsWhenNothingWithheld() {
        stubWithheld(false);

        runner.resumeAsync(RAW_SN);

        verifyNoInteractions(vlmTimeseriesStep);
    }

    @Test
    @DisplayName("이미_시계열_메타가_적재됐으면_중복_재위탁하지_않는다")
    void skipsWhenMetaAlreadyPresent() {
        // 보류 기록은 append-only 라 지워지지 않는다 — 멱등성은 "시계열 메타 0건" 조건이 담당한다.
        stubWithheld(true);
        stubMetaCounts(9L, 3L);

        runner.resumeAsync(RAW_SN);

        verifyNoInteractions(vlmTimeseriesStep);
    }

    /**
     * ★ 핵심 회귀 — {@code LS_DATA_META} 에는 {@code video.*} 기술메타({@code VideoMetaService} 소유)가
     * 시계열 메타와 <b>같은 테이블</b>에 들어 있다. 전체 카운트로 판정하면 <b>기술메타만 있는 영상</b>
     * (= 시계열 위탁이 보류돼 재개가 필요한 바로 그 상태)이 "메타 이미 있음"으로 오산입돼 영구히 skip 되고,
     * 시계열 메타가 무증상으로 영구 결손된다.
     */
    @Test
    @DisplayName("기술메타만_있고_시계열_메타가_0건이면_재위탁된다")
    void resumesWhenOnlyTechnicalMetaPresent() {
        stubWithheld(true);
        stubMetaCounts(6L, 0L); // video.* 6키만 적재된 상태(배치 직후 대다수)
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(RAW_SN)).thenReturn(List.of());

        runner.resumeAsync(RAW_SN);

        verify(vlmTimeseriesStep).run(RAW_SN);
    }

    /**
     * 멱등 방향의 대칭 가드 — 시계열이 1건이라도 있으면 기술메타 유무와 무관하게 재위탁하지 않는다
     * (중복 메타·중복 검수행 + 외부 VLM 벤더로의 불필요한 요청 방지).
     */
    @Test
    @DisplayName("시계열_메타가_1건이라도_있으면_기술메타_유무와_무관하게_재위탁하지_않는다")
    void skipsWhenTimeseriesMetaPresentRegardlessOfTechnicalMeta() {
        stubWithheld(true);
        stubMetaCounts(7L, 1L); // 기술메타 6 + 시계열 1

        runner.resumeAsync(RAW_SN);

        verifyNoInteractions(vlmTimeseriesStep);
    }

    /**
     * 접두 상수 드리프트 가드 — 재개 판정은 {@link VideoMetaService#KEY_PREFIX} 를 <b>그대로</b> 넘겨야 한다.
     * 접두 문자열을 러너/JPQL 에 복제해 박으면 상수가 바뀌는 날 조용히 어긋난다.
     */
    @Test
    @DisplayName("시계열_카운트는_VideoMetaService_접두상수를_그대로_넘긴다")
    void passesTechnicalKeyPrefixConstant() {
        stubWithheld(true);
        stubMetaCounts(0L, 0L);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(RAW_SN)).thenReturn(List.of());

        runner.resumeAsync(RAW_SN);

        verify(metaRepository).countTimeseriesByRawSn(RAW_SN, VideoMetaService.KEY_PREFIX);
        // 전체 카운트(기술메타 포함)로 판정하던 옛 경로는 더 이상 쓰이지 않는다.
        verify(metaRepository, never()).countByRawSn(anyLong());
    }

    /**
     * 접두 상수와 JPQL {@code LIKE} 의미가 어긋나지 않게 고정한다 — {@code not like concat(:prefix, '%')} 가
     * {@code startsWith} 와 동치이려면 접두에 LIKE 와일드카드({@code %}, {@code _})가 없어야 한다.
     * (예: 접두가 {@code "video_"} 가 되면 {@code videoX...} 시계열 키까지 기술메타로 오분류된다.)
     */
    @Test
    @DisplayName("기술메타_접두상수에_LIKE_와일드카드가_없다")
    void technicalKeyPrefixHasNoLikeWildcard() {
        org.assertj.core.api.Assertions.assertThat(VideoMetaService.KEY_PREFIX)
                .isNotBlank()
                .doesNotContain("%")
                .doesNotContain("_");
        // 술어 단일 원천과의 정합 — 접두로 시작하는 키만 기술메타다.
        org.assertj.core.api.Assertions
                .assertThat(VideoMetaService.isTechnicalKey(VideoMetaService.KEY_PREFIX + "fps")).isTrue();
        org.assertj.core.api.Assertions
                .assertThat(VideoMetaService.isTechnicalKey("videoclip-0-10")).isFalse();
    }

    @Test
    @DisplayName("재위탁_실패는_삼켜서_해소_트랜잭션에_영향을_주지_않는다")
    void swallowsResumeFailure() {
        stubWithheld(true);
        stubMetaCounts(0L, 0L);
        when(markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(RAW_SN)).thenReturn(List.of());
        when(vlmTimeseriesStep.run(RAW_SN)).thenThrow(new IllegalStateException("VLM down"));

        runner.resumeAsync(RAW_SN); // 예외가 밖으로 나오면 실패

        verify(vlmTimeseriesStep).run(RAW_SN);
    }

    /**
     * Phase C-1 — 미결 스위퍼가 남긴 {@code ACK 미수신} 감사 행도 같은 재개 경로를 타야 한다.
     * 사유 목록에서 이 값이 빠지면 스위퍼가 클레임만 하고 아무도 재위탁하지 않는 무증상 결손이 된다.
     */
    @Test
    @DisplayName("재개_사유_목록에_비동기_제출실패와_ACK_미수신이_포함된다")
    void resumableReasonsCoverAsyncSubmitOutcomes() {
        org.assertj.core.api.Assertions.assertThat(VlmTimeseriesStep.RESUMABLE_SKIP_REASONS)
                .contains(VlmTimeseriesStep.SKIP_REASON_DEIDENT_REPORT,
                        VlmTimeseriesStep.SKIP_REASON_SUBMIT_FAILED,
                        VlmTimeseriesStep.SKIP_REASON_ACK_MISSING);
    }

    @Test
    @DisplayName("게이트_재개방_이벤트가_재개_러너로_배선되어_있다")
    void bridgeDelegatesToRunner() {
        // 배선이 끊기면(리스너 제거·다른 이벤트 구독) 이 단언이 깨진다 — 결손은 무증상이라 배선 자체를 고정한다.
        VlmWithheldResumeRunner resumeRunner = mock(VlmWithheldResumeRunner.class);
        new VlmResumeBridge(resumeRunner).onDeidentGateReopened(new DeidentGateReopenedEvent(RAW_SN));
        verify(resumeRunner).resumeAsync(eq(RAW_SN));
    }
}
