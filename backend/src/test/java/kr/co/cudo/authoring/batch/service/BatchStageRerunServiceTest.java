package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.batch.dto.BatchStageRerunResponse;
import kr.co.cudo.authoring.batch.orchestrator.BatchStage;
import kr.co.cudo.authoring.batch.orchestrator.BatchStageBundle;
import kr.co.cudo.authoring.batch.pipeline.BatchBundleTogglePolicy;
import kr.co.cudo.authoring.batch.runner.AsyncBatchReprocessRunner;
import kr.co.cudo.authoring.batch.status.BatchStatusService;
import kr.co.cudo.authoring.batch.status.BatchTransitionService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskRejectedException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 되돌린 작업 묶음의 지목 재수행 <b>접수</b> 서비스 단위 테스트. [@design API-201]
 *
 * <p>핵심 수용 기준 셋:
 * <ol>
 *   <li><b>되돌린 묶음만 수락한다</b> — 요청이 임의 묶음을 지정할 수 있으면 앞 작업을 건너뛰도록
 *       강제해 전제 없는 산출물이 만들어진다.</li>
 *   <li><b>범위를 고르지 않는다</b> — 묶음이 곧 범위다(구 {@code scope} 요청 폐기).</li>
 *   <li><b>완주 축에서만 선점한다</b> — 실패 영상을 여기서 받으면 오케스트레이터가 루프 종료 후 무조건
 *       완료로 마감하므로, 프레임이 한 장도 없는 영상이 COMPLETED 가 되어 산출 축으로 흘러간다.</li>
 * </ol>
 */
class BatchStageRerunServiceTest {

    private VideoRepository videoRepository;
    private BatchStatusService batchStatusService;
    private BatchTransitionService transitionService;
    private ReviewApprovalGate reviewApprovalGate;
    private BatchBundleTogglePolicy togglePolicy;
    private AsyncBatchReprocessRunner reprocessRunner;
    private BatchStageRerunService service;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        batchStatusService = mock(BatchStatusService.class);
        transitionService = mock(BatchTransitionService.class);
        reviewApprovalGate = mock(ReviewApprovalGate.class);
        togglePolicy = mock(BatchBundleTogglePolicy.class);
        reprocessRunner = mock(AsyncBatchReprocessRunner.class);
        service = new BatchStageRerunService(videoRepository, batchStatusService, transitionService,
                reviewApprovalGate, togglePolicy, reprocessRunner);
    }

    /** 정상 접수가 되는 최소 조건 — 영상 존재 + 그 묶음을 되돌림 + 완주 축 선점 성공. */
    private void givenAcceptable(long rawSn, BatchStageBundle bundle) {
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(batchStatusService.hasClearedManualSkip(rawSn, bundle)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromCompleted(rawSn)).thenReturn(true);
    }

    @Test
    @DisplayName("★되돌린_묶음을_재수행하면_그_묶음만_켜진_토글로_접수된다")
    void acceptsClearedBundle() {
        long rawSn = 1L;
        givenAcceptable(rawSn, BatchStageBundle.AUTOLABEL);
        Map<String, Boolean> toggles = Map.of(
                BatchStage.YOLO.name(), true,
                BatchStage.SAM2.name(), true,
                BatchStage.INTERPOLATE.name(), true,
                BatchStage.VLM.name(), false);
        when(togglePolicy.togglesFor(BatchStageBundle.AUTOLABEL)).thenReturn(toggles);

        BatchStageRerunResponse res = service.rerun(rawSn, "AUTOLABEL");

        assertThat(res.rawSn()).isEqualTo(rawSn);
        assertThat(res.stage()).isEqualTo("AUTOLABEL");
        assertThat(res.accepted()).isTrue();
        // 보상 롤백이 완주 상태로 되돌아가도록 출발 상태를 함께 넘기고, 묶음 토글도 함께 넘긴다.
        verify(reprocessRunner).runBundleRerunAsync(
                rawSn, LsDataRaw.DATA_STTS_COMPLETED, toggles, false);
    }

    /**
     * ★★완주 출발 상태가 <b>DB 에</b> 남아야 회수가 이 영상을 {@code FAILED} 로 강등하지 않는다.
     *
     * <p>인자로만 존재하면 노드가 죽을 때 함께 사라지고, 회수는 출발 상태를 몰라 <b>추측</b>하게 된다.
     * 완주 영상을 {@code FAILED} 로 되돌리면 전체 재기동 경로가 열려 사람이 손댄 보간 라벨이 전량
     * 삭제·재생성된다 — 이 기능이 막으려던 바로 그 파괴다.
     */
    @Test
    @DisplayName("★선점하면_출발상태_COMPLETED를_표식으로_남기고_그_뒤에_디스패치한다")
    void recordsCompletedClaimOriginMarkerBeforeDispatch() {
        long rawSn = 51L;
        givenAcceptable(rawSn, BatchStageBundle.VLM);

        service.rerun(rawSn, "VLM");

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(batchStatusService, reprocessRunner);
        inOrder.verify(batchStatusService)
                .recordReprocessClaimOpened(rawSn, LsDataRaw.DATA_STTS_COMPLETED);
        inOrder.verify(reprocessRunner).runBundleRerunAsync(
                eq(rawSn), eq(LsDataRaw.DATA_STTS_COMPLETED), any(), anyBoolean());
        // 실패 축 출발 상태로 표식이 남으면 회수가 완주 영상을 FAILED 로 강등한다.
        verify(batchStatusService, never())
                .recordReprocessClaimOpened(anyLong(), eq(LsDataRaw.DATA_STTS_FAILED));
    }

    @Test
    @DisplayName("★접수가_거부되면_선점을_되돌리면서_표식도_닫는다")
    void closesClaimMarkerWhenDispatchRejected() {
        long rawSn = 52L;
        givenAcceptable(rawSn, BatchStageBundle.VLM);
        org.mockito.Mockito.doThrow(new org.springframework.core.task.TaskRejectedException("full"))
                .when(reprocessRunner).runBundleRerunAsync(eq(rawSn), anyString(), any(), anyBoolean());

        assertThatThrownBy(() -> service.rerun(rawSn, "VLM")).isInstanceOf(CustomException.class);

        verify(batchStatusService).recordReprocessClaimClosed(
                rawSn, BatchStageRerunService.CLAIM_CLOSED_DISPATCH_REJECTED);
    }

    /**
     * ★표식 닫기가 실패해도 <b>의도한 503</b> 이 나가야 한다.
     *
     * <p>기록 실패가 그대로 올라가면 사용자는 "접수 실패(잠시 후 재시도)" 대신 알 수 없는 오류를 본다 —
     * 상태(PROCESSING)는 이미 되돌아갔는데도. 러너({@code AsyncBatchReprocessRunner})가 같은 관례로
     * 감싸고 있으므로 여기만 비워 두면 같은 기록 실패가 경로에 따라 다르게 드러난다.
     */
    @Test
    @DisplayName("★표식_닫기가_실패해도_응답은_503으로_유지된다")
    void markerCloseFailureDoesNotFlipTheResponse() {
        long rawSn = 53L;
        givenAcceptable(rawSn, BatchStageBundle.VLM);
        doThrow(new TaskRejectedException("full"))
                .when(reprocessRunner).runBundleRerunAsync(eq(rawSn), anyString(), any(), anyBoolean());
        doThrow(new IllegalStateException("marker write failed"))
                .when(batchStatusService).recordReprocessClaimClosed(anyLong(), anyString());

        assertThatThrownBy(() -> service.rerun(rawSn, "VLM"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("★★재수행은_전용_비동기_진입을_탄다_전체재기동_진입을_쓰면_실패시_영상이_강등된다")
    void usesDedicatedRerunEntryPoint() {
        // 전체 재기동 진입(runAsync)을 쓰면 단계 실패가 markRawDataFailed + 자동 재시도로 마감돼
        //   완주 영상이 FAILED 로 강등되고 사람이 손댄 보간 라벨이 재시도에 지워진다.
        long rawSn = 12L;
        givenAcceptable(rawSn, BatchStageBundle.VLM);
        when(togglePolicy.togglesFor(any())).thenReturn(Map.of());

        service.rerun(rawSn, "VLM");

        verify(reprocessRunner).runBundleRerunAsync(eq(rawSn), anyString(), any(), anyBoolean());
        verify(reprocessRunner, never()).runAsync(anyLong(), anyString());
    }

    @Test
    @DisplayName("★되돌린_묶음이_아니면_400이고_상태를_선점하지_않는다")
    void rejectsBundleThatWasNotCleared() {
        // 요청이 대상 묶음을 자유롭게 고르지 못하게 하는 장치다 — 느슨해지면 앞 작업을 건너뛴 채
        //   뒤 작업만 돌아 전제 없는 산출물이 만들어진다.
        long rawSn = 2L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(batchStatusService.hasClearedManualSkip(rawSn, BatchStageBundle.AUTOLABEL)).thenReturn(false);

        assertThatThrownBy(() -> service.rerun(rawSn, "AUTOLABEL"))
                .isInstanceOf(CustomException.class)
                .hasMessage(BatchStageRerunService.NOT_CLEARED_BUNDLE_REASON)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(transitionService, never()).tryClaimReprocessFromCompleted(anyLong());
        verify(reprocessRunner, never()).runBundleRerunAsync(anyLong(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("★★구_단위였던_개별_단계값은_더_이상_수락되지_않는다_400")
    void rejectsLegacyPerStageValues() {
        // 단위가 묶음으로 반전됐다. YOLO 만 재수행하는 요청이 통과하면 뒤의 분할·보간이 옛 산출물로
        //   남아 어긋난다 — 그래서 값 자체를 받지 않는다.
        long rawSn = 3L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);

        for (String legacy : new String[]{"YOLO", "SAM2", "INTERPOLATE", "FRAME_EXTRACT"}) {
            assertThatThrownBy(() -> service.rerun(rawSn, legacy))
                    .isInstanceOf(CustomException.class)
                    .hasMessage(BatchStageRerunService.NOT_CLEARED_BUNDLE_REASON)
                    .extracting(e -> ((CustomException) e).getErrorCode())
                    .isEqualTo(ErrorCode.INVALID_INPUT);
        }

        verify(batchStatusService, never()).hasClearedManualSkip(anyLong(), any());
        verify(transitionService, never()).tryClaimReprocessFromCompleted(anyLong());
    }

    @Test
    @DisplayName("거부_메시지는_미지원_묶음과_되돌리지_않은_묶음을_구분해_알려주지_않는다")
    void rejectionMessageIsNotAnOracle() {
        long rawSn = 5L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(batchStatusService.hasClearedManualSkip(rawSn, BatchStageBundle.VLM)).thenReturn(false);

        assertThatThrownBy(() -> service.rerun(rawSn, "VLM"))
                .hasMessage(BatchStageRerunService.NOT_CLEARED_BUNDLE_REASON);
        assertThatThrownBy(() -> service.rerun(rawSn, "NOPE"))
                .hasMessage(BatchStageRerunService.NOT_CLEARED_BUNDLE_REASON);
    }

    @Test
    @DisplayName("거부_메시지는_요청_값을_되비추지_않는다")
    void rejectionDoesNotEchoInput() {
        long rawSn = 51L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);

        assertThatThrownBy(() -> service.rerun(rawSn, "<script>alert(1)</script>"))
                .isInstanceOf(CustomException.class)
                .hasMessageNotContaining("script");
    }

    @Test
    @DisplayName("★한번이라도_검수가_완료된_영상은_오토라벨_묶음이_400으로_거부되고_상태를_선점하지_않는다")
    void rejectsEverApprovedVideoBeforeClaiming() {
        // 오토라벨은 라벨을 <다시 만들어> 승인 시점 스냅샷과 어긋난다. 판정은 신고 차단·프레임 폐기
        //   차단과 같은 단일 원천을 주입해 쓰며(규칙 복제 금지), 재시도 여지가 없는 영구 조건이라
        //   412 가 아니라 400 이다.
        long rawSn = 6L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(batchStatusService.hasClearedManualSkip(rawSn, BatchStageBundle.AUTOLABEL)).thenReturn(true);
        when(reviewApprovalGate.hasEverApproved(rawSn)).thenReturn(true);

        assertThatThrownBy(() -> service.rerun(rawSn, "AUTOLABEL"))
                .isInstanceOf(CustomException.class)
                .hasMessage(BatchStageRerunService.EVER_APPROVED_REASON)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(transitionService, never()).tryClaimReprocessFromCompleted(anyLong());
        verify(reprocessRunner, never()).runBundleRerunAsync(anyLong(), anyString(), any(), anyBoolean());
    }

    /**
     * ★★승인 이력 거부는 <b>오토라벨 한정</b>이다 — 시계열은 승인 이력이 있어도 접수된다. [@design API-201]
     *
     * <p>시계열 재수행은 확정된 라벨을 되돌리지 않고 <b>메타만 더한다</b>. 승인 완료 영상에 서술이
     * 들어오는 경우는 하류({@code VlmResultService})가 검토행을 대기로 되돌리고 산출물 재생성·관제
     * 재통지를 걸도록 <b>이미 상정</b>돼 있으며, 값이 같으면 아무 일도 일어나지 않는다(멱등).
     * 벤더 연동이 늦어져 건너뛴 영상이 그대로 승인되더라도 시계열을 나중에 받을 수 있어야 한다.
     */
    @Test
    @DisplayName("★★승인_이력이_있어도_시계열_묶음은_재수행을_수락한다")
    void acceptsVlmBundleEvenWhenEverApproved() {
        long rawSn = 61L;
        givenAcceptable(rawSn, BatchStageBundle.VLM);
        when(reviewApprovalGate.hasEverApproved(rawSn)).thenReturn(true);
        // ★★승인 이력이 있는 영상은 <반드시> 검수 소유 작업 상태(APPROVED)다. 이 스텁을 빼면 Mockito
        //   기본값 false 가 다음 게이트를 그냥 통과시켜, 프로덕션에서 성립할 수 없는 조합을 검증하게
        //   된다(구 테스트가 실제로 그랬고 그래서 게이트 2겹째가 커버리지 밖이었다).
        when(transitionService.isReviewOwnedWorkStatus(rawSn)).thenReturn(true);
        Map<String, Boolean> toggles = Map.of(
                BatchStage.VLM.name(), true,
                BatchStage.YOLO.name(), false);
        when(togglePolicy.togglesFor(BatchStageBundle.VLM)).thenReturn(toggles);

        BatchStageRerunResponse res = service.rerun(rawSn, "VLM");

        assertThat(res.accepted()).isTrue();
        assertThat(res.stage()).isEqualTo("VLM");
        // 접수는 선점·디스패치까지 실제로 진행돼야 한다 — 200 만 주고 아무것도 돌지 않으면 사용자는
        //   접수됐다고 믿는데 시계열이 영영 오지 않는다.
        verify(transitionService).tryClaimReprocessFromCompleted(rawSn);
        // ★면제 사실이 실행 경로까지 전달돼야 한다 — false 로 나가면 오케스트레이터 진입 가드(세 번째
        //   겹)가 step 을 한 건도 돌리지 않고 SKIPPED 로 끝낸다(접수만 되고 아무 일도 일어나지 않는다).
        verify(reprocessRunner).runBundleRerunAsync(
                rawSn, LsDataRaw.DATA_STTS_COMPLETED, toggles, true);
    }

    /**
     * ★★<b>게이트 2겹째</b>의 회귀 가드 — 승인 이력 게이트만 풀면 동작이 바뀌지 않는다. [@design API-201]
     *
     * <p>구 구현은 승인 이력(400)만 시계열에 면제해 뒀는데, <b>바로 다음 줄</b>의 검수 소유 작업 상태
     * 검사가 {@code APPROVED} 를 409 로 다시 막았다. 즉 「벤더 연동이 늦어져 건너뛴 영상이 그대로
     * 승인되더라도 시계열을 나중에 받을 수 있어야 한다」가 <b>실제로는 성립하지 않았다</b>.
     *
     * <p>위 테스트와 별도로 두는 이유: 이쪽은 <b>409 축</b>이 다시 닫히는 회귀만 겨눈다. 한쪽이 죽어도
     * 다른 쪽이 살아 있으면 어느 겹이 되돌아갔는지 즉시 드러난다.
     */
    @Test
    @DisplayName("★★검수소유_상태여도_시계열_묶음은_409로_막히지_않는다_게이트2겹_회귀차단")
    void vlmBundleIsNotBlockedByReviewOwnedWorkStatus() {
        long rawSn = 63L;
        givenAcceptable(rawSn, BatchStageBundle.VLM);
        when(transitionService.isReviewOwnedWorkStatus(rawSn)).thenReturn(true);
        when(togglePolicy.togglesFor(BatchStageBundle.VLM))
                .thenReturn(Map.of(BatchStage.VLM.name(), true));

        BatchStageRerunResponse res = service.rerun(rawSn, "VLM");

        assertThat(res.accepted()).isTrue();
        verify(reprocessRunner).runBundleRerunAsync(
                eq(rawSn), eq(LsDataRaw.DATA_STTS_COMPLETED), any(), eq(true));
    }

    /**
     * ★회귀 차단 — 면제가 오토라벨로 새면 승인 완료 영상의 라벨이 재생성된다.
     *
     * <p>위 시계열 테스트와 <b>같은 조합</b>(승인 이력 + 검수 소유 상태)에서 오토라벨은 여전히 400
     * 이어야 한다. allowlist 를 비우거나 denylist 로 되돌리면 둘 중 하나가 즉시 죽는다.
     */
    @Test
    @DisplayName("★승인_이력과_검수소유_상태가_함께여도_오토라벨_묶음은_400이다")
    void autolabelStaysBlockedInTheSameCombination() {
        long rawSn = 64L;
        givenAcceptable(rawSn, BatchStageBundle.AUTOLABEL);
        when(reviewApprovalGate.hasEverApproved(rawSn)).thenReturn(true);
        when(transitionService.isReviewOwnedWorkStatus(rawSn)).thenReturn(true);

        assertThatThrownBy(() -> service.rerun(rawSn, "AUTOLABEL"))
                .isInstanceOf(CustomException.class)
                .hasMessage(BatchStageRerunService.EVER_APPROVED_REASON)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(transitionService, never()).tryClaimReprocessFromCompleted(anyLong());
        verify(reprocessRunner, never())
                .runBundleRerunAsync(anyLong(), anyString(), any(), anyBoolean());
    }

    /**
     * ★면제되지 <b>않는</b> 묶음은 면제 플래그도 꺼진 채로 디스패치돼야 한다.
     *
     * <p>플래그가 켜져 나가면 오케스트레이터가 승인 영상에서도 파이프라인을 돌리게 되고, 그 실행 범위가
     * 오토라벨이면 <b>확정된 학습데이터의 라벨이 재생성</b>된다(런타임 fail-closed 가 한 겹 더 막지만
     * 여기서 새는 것 자체가 회귀다).
     */
    @Test
    @DisplayName("★오토라벨_묶음은_면제_플래그가_꺼진_채로_디스패치된다")
    void autolabelDispatchesWithoutExemptionFlag() {
        long rawSn = 65L;
        givenAcceptable(rawSn, BatchStageBundle.AUTOLABEL);
        when(togglePolicy.togglesFor(BatchStageBundle.AUTOLABEL))
                .thenReturn(Map.of(BatchStage.YOLO.name(), true));

        service.rerun(rawSn, "AUTOLABEL");

        verify(reprocessRunner).runBundleRerunAsync(
                eq(rawSn), eq(LsDataRaw.DATA_STTS_COMPLETED), any(), eq(false));
    }

    /**
     * ★회귀 차단 — 예외를 오토라벨로 넓히면 승인 시점 스냅샷과 어긋난 라벨이 재생성된다.
     *
     * <p>위 시계열 예외와 <b>짝</b>으로 둔다. 하나만 있으면 "승인 이력 게이트를 통째로 없애는" 회귀가
     * 시계열 테스트만 통과시킨 채 지나간다.
     */
    @Test
    @DisplayName("★오토라벨_묶음은_승인_이력_예외를_받지_않는다")
    void autolabelBundleIsNotExemptFromEverApprovedGate() {
        long rawSn = 62L;
        givenAcceptable(rawSn, BatchStageBundle.AUTOLABEL);
        when(reviewApprovalGate.hasEverApproved(rawSn)).thenReturn(true);

        assertThatThrownBy(() -> service.rerun(rawSn, "AUTOLABEL"))
                .isInstanceOf(CustomException.class)
                .hasMessage(BatchStageRerunService.EVER_APPROVED_REASON)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);

        verify(transitionService, never()).tryClaimReprocessFromCompleted(anyLong());
        verify(reprocessRunner, never()).runBundleRerunAsync(anyLong(), anyString(), any(), anyBoolean());
    }

    /**
     * ★평가 순서 회귀 가드 — 되돌린 묶음 판정(4번)이 승인 이력 판정(5번)보다 <b>먼저</b>다.
     *
     * <p>순서가 뒤집히면 되돌리지도 않은 묶음에 대해 응답이 「이 영상은 승인된 적이 있다」를 먼저
     * 알려주게 되고, 묶음 판정이 감추려던 정보 축과 섞인다(CWE-209).
     */
    @Test
    @DisplayName("★승인_이력이_있어도_되돌린_묶음이_아니면_묶음_사유로_먼저_400이_난다")
    void clearedBundleGateIsEvaluatedBeforeApprovalGate() {
        long rawSn = 63L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(batchStatusService.hasClearedManualSkip(rawSn, BatchStageBundle.AUTOLABEL)).thenReturn(false);
        when(reviewApprovalGate.hasEverApproved(rawSn)).thenReturn(true);

        assertThatThrownBy(() -> service.rerun(rawSn, "AUTOLABEL"))
                .isInstanceOf(CustomException.class)
                .hasMessage(BatchStageRerunService.NOT_CLEARED_BUNDLE_REASON);

        verify(transitionService, never()).tryClaimReprocessFromCompleted(anyLong());
    }

    @Test
    @DisplayName("★검수소유_작업상태는_클레임_전에_409로_거부된다_접수됨으로_가려지지_않는다")
    void rejectsReviewOwnedBeforeClaiming() {
        long rawSn = 7L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(batchStatusService.hasClearedManualSkip(rawSn, BatchStageBundle.AUTOLABEL)).thenReturn(true);
        when(transitionService.isReviewOwnedWorkStatus(rawSn)).thenReturn(true);

        assertThatThrownBy(() -> service.rerun(rawSn, "AUTOLABEL"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(transitionService, never()).tryClaimReprocessFromCompleted(anyLong());
        verify(transitionService, never()).releaseReprocessClaim(anyLong(), anyString());
    }

    @Test
    @DisplayName("★완주_상태가_아니면_409다_실패영상은_이_경로로_들어오지_못한다")
    void rejectsWhenNotCompleted() {
        // 실패 영상을 받으면 루프 종료 후 무조건 완료 마감이라, 프레임 없는 영상이 COMPLETED 가 되어
        //   산출 축으로 흘러간다. 실패 복구는 파이프라인을 처음부터 도는 전체 재기동이 담당한다.
        long rawSn = 8L;
        when(videoRepository.existsById(rawSn)).thenReturn(true);
        when(batchStatusService.hasClearedManualSkip(rawSn, BatchStageBundle.VLM)).thenReturn(true);
        when(transitionService.tryClaimReprocessFromCompleted(rawSn)).thenReturn(false);

        assertThatThrownBy(() -> service.rerun(rawSn, "VLM"))
                .isInstanceOf(CustomException.class)
                .hasMessage(BatchStageRerunService.NOT_CLAIMABLE_REASON)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);

        verify(reprocessRunner, never()).runBundleRerunAsync(anyLong(), anyString(), any(), anyBoolean());
        // 실패 축 클레임은 이 경로에 존재하지 않는다.
        verify(transitionService, never()).tryClaimReprocessFromFailed(anyLong());
    }

    @Test
    @DisplayName("★선점은_단일_조건부UPDATE다_상태를_미리_읽어_판정하지_않는다")
    void claimIsAtomic() {
        long rawSn = 9L;
        givenAcceptable(rawSn, BatchStageBundle.VLM);
        when(togglePolicy.togglesFor(any())).thenReturn(Map.of());

        service.rerun(rawSn, "VLM");

        verify(transitionService).tryClaimReprocessFromCompleted(rawSn);
        // read-then-write 로 바꾸면 동시 요청이 둘 다 통과한다(CWE-362).
        verify(videoRepository, never()).findDataSttsCdByRawSn(anyLong());
        verify(videoRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("존재하지_않는_영상은_404이고_어떤_판정도_하지_않는다")
    void notFoundWhenVideoMissing() {
        when(videoRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.rerun(99L, "VLM"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);

        verify(batchStatusService, never()).hasClearedManualSkip(anyLong(), any());
        verify(transitionService, never()).tryClaimReprocessFromCompleted(anyLong());
    }

    @Test
    @DisplayName("★디스패치가_거부되면_접수_실패다_완주상태로_되돌리고_503으로_알린다")
    void compensatesClaimWhenDispatchRejected() {
        long rawSn = 10L;
        givenAcceptable(rawSn, BatchStageBundle.AUTOLABEL);
        when(togglePolicy.togglesFor(BatchStageBundle.AUTOLABEL)).thenReturn(Map.of());
        doThrow(new TaskRejectedException("queue full"))
                .when(reprocessRunner)
                .runBundleRerunAsync(eq(rawSn), eq(LsDataRaw.DATA_STTS_COMPLETED), any(), anyBoolean());

        assertThatThrownBy(() -> service.rerun(rawSn, "AUTOLABEL"))
                .isInstanceOf(CustomException.class)
                .hasMessage(BatchStageRerunService.DISPATCH_REJECTED_REASON)
                .hasMessageNotContaining("queue")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);

        // 아무것도 실패하지 않았는데 FAILED 로 되돌리면 화면이 "실패"로 보이고 완주 사실이 지워진다.
        verify(transitionService).releaseReprocessClaim(rawSn, LsDataRaw.DATA_STTS_COMPLETED);
        verify(transitionService, never()).releaseReprocessClaim(rawSn, LsDataRaw.DATA_STTS_FAILED);
    }

    @Test
    @DisplayName("정상_접수시에는_보상롤백을_하지_않는다")
    void doesNotCompensateOnNormalDispatch() {
        long rawSn = 11L;
        givenAcceptable(rawSn, BatchStageBundle.AUTOLABEL);
        when(togglePolicy.togglesFor(any())).thenReturn(Map.of());

        service.rerun(rawSn, "autolabel"); // 대소문자 무시

        verify(transitionService, never()).releaseReprocessClaim(anyLong(), anyString());
        verify(reprocessRunner).runBundleRerunAsync(eq(rawSn), eq(LsDataRaw.DATA_STTS_COMPLETED), any(), anyBoolean());
    }
}
