package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.review.service.ReviewStateMachine;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BatchTransitionService 단위 테스트 (이슈 A 회귀).
 *
 * <p>process() 비트랜잭션 환경에서 dirty checking 이 동작하지 않는 문제를 회피하기 위해
 * load → transitionTo → save 가 명시적으로 수행되는지 검증한다.
 */
class BatchTransitionServiceTest {

    private LsRawDataStatusRepository rawDataStatusRepository;
    private VideoRepository videoRepository;
    private LsDeidentProcLogRepository procLogRepository;
    private BatchTransitionService service;

    @BeforeEach
    void setUp() {
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        videoRepository = mock(VideoRepository.class);
        procLogRepository = mock(LsDeidentProcLogRepository.class);
        service = new BatchTransitionService(rawDataStatusRepository, videoRepository, procLogRepository);
    }

    private LsRawDataStatus assignedStatus(Long rawSn) {
        LsRawDataStatus stts = LsRawDataStatus.initial(rawSn);
        stts.markAssigned();
        return stts;
    }

    @Test
    @DisplayName("markRawDataProcessingBlocked_검수소유상태_제외_조건부UPDATE로_PROCESSING_전이")
    void markRawDataProcessing_PROCESSING_영속() {
        // given — 조건부 UPDATE 가 1행 영향(전이 성공)
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(
                1L, LsRawDataStatus.STTS_PROCESSING, BatchTransitionService.REVIEW_OWNED_STATUSES))
                .thenReturn(1);
        when(videoRepository.claimForProcessing(1L, LsDataRaw.DATA_STTS_PROCESSING)).thenReturn(1);

        // when
        service.markRawDataProcessingBlocked(1L);

        // then — dirty checking·무조건 UPDATE 가 아니라 검수 소유 상태를 제외한 조건부 UPDATE 로 전이(B-ISSUE-03)
        verify(rawDataStatusRepository).transitionByBatchIfNotBlocked(
                1L, LsRawDataStatus.STTS_PROCESSING, BatchTransitionService.REVIEW_OWNED_STATUSES);
        verify(rawDataStatusRepository, never()).save(any());
    }

    @Test
    @DisplayName("검수소유상태라_영향행수0이면_예외없이_WARN만_남기고_진행 — 배치가_검수를_막지_않음")
    void transition_blocked_noException_noSave() {
        // given — APPROVED/IN_REVIEW 라 조건부 UPDATE 가 0행 영향
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(any(), any(), any())).thenReturn(0);
        LsRawDataStatus approved = LsRawDataStatus.initial(30L);
        approved.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findById(30L)).thenReturn(Optional.of(approved));
        when(videoRepository.findById(30L)).thenReturn(Optional.empty());

        // when / then — 예외를 던지지 않고, 상태도 건드리지 않는다
        service.markRawDataProcessingBlocked(30L);
        service.markRawDataCompleted(30L);
        service.markRawDataFailed(30L);

        assertThat(approved.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_APPROVED);
        verify(rawDataStatusRepository, never()).save(any());
    }

    @Test
    @DisplayName("검수소유상태_차단집합은_PENDING_IN_REVIEW_APPROVED_REJECTED_4종이고_ASSIGNED는_제외된다")
    void reviewOwnedStatuses_정확히_4종() {
        // then — H1-b: PENDING·REJECTED 에서 출발하는 정상 배치 전이는 코드에 존재하지 않으므로 차단 대상이다.
        //        반면 배치 완료의 ASSIGNED 복귀는 의도된 설계라 제외해야 한다(제외하지 않으면 파이프라인이 끊긴다).
        assertThat(BatchTransitionService.REVIEW_OWNED_STATUSES)
                .containsExactlyInAnyOrder(
                        LsRawDataStatus.STTS_PENDING,
                        LsRawDataStatus.STTS_IN_REVIEW,
                        LsRawDataStatus.STTS_APPROVED,
                        LsRawDataStatus.STTS_REJECTED)
                .doesNotContain(LsRawDataStatus.STTS_ASSIGNED);
    }

    @Test
    @DisplayName("검수소유상태면_markRawDataProcessingBlocked가_true를_반환하고_LS_DATA_RAW도_전이하지_않는다")
    void markRawDataProcessingBlocked_차단시_true_그리고_LS_DATA_RAW_불변() {
        // given — 조건부 UPDATE 0행 + 현재 상태가 APPROVED(검수 소유)
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(any(), any(), any())).thenReturn(0);
        LsRawDataStatus approved = LsRawDataStatus.initial(31L);
        approved.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findById(31L)).thenReturn(Optional.of(approved));
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-31", "cctv-1", "EVT", "GOV", LsDataRaw.PRVC_TYPE_PRVC, "raw/31.mp4", null, 60);
        raw.markMarkingReady();
        when(videoRepository.findById(31L)).thenReturn(Optional.of(raw));

        // when
        boolean blocked = service.markRawDataProcessingBlocked(31L);

        // then — 호출자(BatchOrchestrator)가 파이프라인을 중단할 수 있도록 true, 배치 단계도 그대로 둔다(H8/H2)
        assertThat(blocked).isTrue();
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
    }

    @Test
    @DisplayName("작업상태_row가_없으면_차단이_아니라서_false_반환하고_LS_DATA_RAW는_정상_클레임된다 — 파생RAW_경로")
    void markRawDataProcessingBlocked_row부재_false() {
        // given — 조건부 UPDATE 0행이지만 row 자체가 없는 파생 RAW 경로 + 배치 단계 클레임 성공(1행)
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(any(), any(), any())).thenReturn(0);
        when(rawDataStatusRepository.findById(32L)).thenReturn(Optional.empty());
        when(videoRepository.claimForProcessing(32L, LsDataRaw.DATA_STTS_PROCESSING)).thenReturn(1);

        // when
        boolean blocked = service.markRawDataProcessingBlocked(32L);

        // then — 배치 진행을 막지 않는다
        assertThat(blocked).isFalse();
        verify(videoRepository).claimForProcessing(32L, LsDataRaw.DATA_STTS_PROCESSING);
    }

    @Test
    @DisplayName("검수소유상태면_markRawDataFailed도_LS_DATA_RAW를_FAILED로_바꾸지_않는다 — 불일치쌍_금지")
    void markRawDataFailed_차단시_LS_DATA_RAW_불변() {
        // given — APPROVED 작업 상태 + 배치 단계 PROCESSING
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(any(), any(), any())).thenReturn(0);
        LsRawDataStatus approved = LsRawDataStatus.initial(33L);
        approved.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findById(33L)).thenReturn(Optional.of(approved));
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-33", "cctv-1", "EVT", "GOV", LsDataRaw.PRVC_TYPE_PRVC, "raw/33.mp4", null, 60);
        raw.markMarkingReady();
        when(videoRepository.findById(33L)).thenReturn(Optional.of(raw));

        // when
        service.markRawDataFailed(33L);

        // then — (work=APPROVED, stage=FAILED) 조합이 생기지 않는다
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
    }

    @Test
    @DisplayName("마킹완료로_배치가_시작되면_LS_DATA_RAW가_조건부UPDATE로_PROCESSING_클레임된다")
    void markRawDataProcessing_LS_DATA_RAW_PROCESSING() {
        // given — 마킹 완료 후 MARKING_READY 인 영상 (작업 상태 ASSIGNED → 조건부 UPDATE 1행 성공)
        LsRawDataStatus stts = assignedStatus(20L);
        when(rawDataStatusRepository.findById(20L)).thenReturn(Optional.of(stts));
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(
                20L, LsRawDataStatus.STTS_PROCESSING, BatchTransitionService.REVIEW_OWNED_STATUSES))
                .thenReturn(1);
        when(videoRepository.claimForProcessing(20L, LsDataRaw.DATA_STTS_PROCESSING)).thenReturn(1);

        // when — 배치 시작
        boolean blocked = service.markRawDataProcessingBlocked(20L);

        // then — B-ISSUE-01: 배치 단계 전이는 read-modify-write(findById+markProcessing) 가 아니라
        //        "PROCESSING 이 아닐 때만" 조건부 UPDATE 여야 동시 진입을 상호배제할 수 있다.
        assertThat(blocked).isFalse();
        verify(videoRepository).claimForProcessing(20L, LsDataRaw.DATA_STTS_PROCESSING);
        verify(videoRepository, never()).findById(20L);
    }

    // ── B-ISSUE-01: 배치 진입 원자 클레임(CWE-362) ──

    @Test
    @DisplayName("이미_PROCESSING이라_클레임이_0행이면_true를_반환해_파이프라인_중복실행을_막는다")
    void markRawDataProcessingBlocked_클레임실패시_true() {
        // given — 검수 소유 상태는 아니지만(전이 1행) 다른 주체가 이미 배치 단계를 클레임한 상태
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(any(), any(), any())).thenReturn(1);
        when(videoRepository.claimForProcessing(60L, LsDataRaw.DATA_STTS_PROCESSING)).thenReturn(0);
        when(videoRepository.findDataSttsCdByRawSn(60L))
                .thenReturn(Optional.of(LsDataRaw.DATA_STTS_PROCESSING));

        // when
        boolean blocked = service.markRawDataProcessingBlocked(60L);

        // then — 호출자(BatchOrchestrator)가 SKIPPED 로 즉시 종료해야 한다.
        assertThat(blocked).isTrue();
    }

    @Test
    @DisplayName("클레임_0행인데_영상row_자체가_없으면_차단이_아니다 — 종전_graceful_동작_보존")
    void markRawDataProcessingBlocked_영상row부재시_false() {
        // given — 클레임 0행의 또 다른 원인: 영상 row 부재
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(any(), any(), any())).thenReturn(1);
        when(videoRepository.claimForProcessing(61L, LsDataRaw.DATA_STTS_PROCESSING)).thenReturn(0);
        when(videoRepository.findDataSttsCdByRawSn(61L)).thenReturn(Optional.empty());

        // when / then — 예외 없이 진행(원인을 구분하지 않으면 정상 경로가 조용히 막힌다)
        assertThat(service.markRawDataProcessingBlocked(61L)).isFalse();
    }

    @Test
    @DisplayName("클레임_보유_진입은_재클레임하지_않는다 — 수동재처리가_자기_PROCESSING에_막히면_영구409")
    void markRawDataProcessingBlockedWithHeldClaim_재클레임_안함() {
        // given — 수동 재처리(tryClaimReprocessFromFailed)가 이미 FAILED→PROCESSING 을 선점한 상태
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(any(), any(), any())).thenReturn(1);
        when(videoRepository.findDataSttsCdByRawSn(62L))
                .thenReturn(Optional.of(LsDataRaw.DATA_STTS_PROCESSING));

        // when
        boolean blocked = service.markRawDataProcessingBlockedWithHeldClaim(62L);

        // then — 재클레임을 시도하면 자기가 찍은 PROCESSING 때문에 0행이 되어 스스로 막힌다.
        assertThat(blocked).isFalse();
        verify(videoRepository, never()).claimForProcessing(any(), any());
    }

    @Test
    @DisplayName("클레임을_보유하지_않은_채_인계_진입을_쓰면_원자_클레임으로_폴백한다 — 상호배제_소실_방지")
    void markRawDataProcessingBlockedWithHeldClaim_클레임미보유시_원자클레임_폴백() {
        // given — 호출자는 "클레임 보유"를 주장하지만 배치 단계는 아직 MARKING_READY(=아무도 소유 안 함).
        //         "보유했다"는 검증 불가능한 주장이므로 그대로 믿고 재클레임을 생략하면, 이 진입점 하나에서
        //         B-ISSUE-01 이 고친 실패 모드(동시 진입 전원 통과)가 그대로 되살아난다.
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(any(), any(), any())).thenReturn(1);
        when(videoRepository.findDataSttsCdByRawSn(64L))
                .thenReturn(Optional.of(LsDataRaw.DATA_STTS_MARKING_READY));
        when(videoRepository.claimForProcessing(64L, LsDataRaw.DATA_STTS_PROCESSING)).thenReturn(1);

        // when
        boolean blocked = service.markRawDataProcessingBlockedWithHeldClaim(64L);

        // then — 차단이 아니라 폴백이다. 여기서 차단(true)하면 tryClaimReprocessFromFailed 가 작업상태
        //        컬럼만 클레임한 정상 복구 형상(배치 단계는 PROCESSING 이 아니다)이 영구 409 로 죽는다.
        assertThat(blocked).isFalse();
        verify(videoRepository).claimForProcessing(64L, LsDataRaw.DATA_STTS_PROCESSING);
    }

    @Test
    @DisplayName("클레임_미보유_폴백에서_다른_주체가_선점했으면_true로_차단된다 — 이중진입_차단")
    void markRawDataProcessingBlockedWithHeldClaim_폴백클레임_실패시_차단() {
        // given — 판정 시점엔 MARKING_READY 였으나 그 사이 다른 주체가 원자 클레임에 성공했다
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(any(), any(), any())).thenReturn(1);
        when(videoRepository.findDataSttsCdByRawSn(65L))
                .thenReturn(Optional.of(LsDataRaw.DATA_STTS_MARKING_READY),
                        Optional.of(LsDataRaw.DATA_STTS_PROCESSING));
        when(videoRepository.claimForProcessing(65L, LsDataRaw.DATA_STTS_PROCESSING)).thenReturn(0);

        // when / then — 폴백 클레임이 0행이면 일반 진입과 동일하게 SKIPPED 로 종료시킨다
        assertThat(service.markRawDataProcessingBlockedWithHeldClaim(65L)).isTrue();
        verify(videoRepository).claimForProcessing(65L, LsDataRaw.DATA_STTS_PROCESSING);
    }

    @Test
    @DisplayName("클레임_보유_진입도_검수소유상태면_true로_차단된다 — 가드는_그대로_유효")
    void markRawDataProcessingBlockedWithHeldClaim_검수소유상태_차단() {
        // given — 작업 상태가 APPROVED(검수 소유) → 조건부 UPDATE 0행
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(any(), any(), any())).thenReturn(0);
        LsRawDataStatus approved = LsRawDataStatus.initial(63L);
        approved.transitionTo(LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findById(63L)).thenReturn(Optional.of(approved));

        // when / then
        assertThat(service.markRawDataProcessingBlockedWithHeldClaim(63L)).isTrue();
        verify(videoRepository, never()).claimForProcessing(any(), any());
    }

    @Test
    @DisplayName("markRawDataCompleted_작업상태는_ASSIGNED복귀_배치단계만_LS_DATA_RAW_COMPLETED")
    void markRawDataCompleted_작업상태_ASSIGNED복귀() {
        // given — 배치 진행 중(PROCESSING) 인 작업 상태 → 조건부 UPDATE 성공(1행)
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(
                2L, LsRawDataStatus.STTS_ASSIGNED, BatchTransitionService.REVIEW_OWNED_STATUSES))
                .thenReturn(1);
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-2", "cctv-1", "EVT", "GOV", LsDataRaw.PRVC_TYPE_ANONY,
                "raw/2.mp4", null, 60);
        when(videoRepository.findById(2L)).thenReturn(Optional.of(raw));

        // when — 배치 완료
        service.markRawDataCompleted(2L);

        // then — 작업(워크플로우) 상태는 ASSIGNED 로 복귀해 검수 제출(ASSIGNED→PENDING)이 가능해야 함.
        //        COMPLETED 는 검수 승인 시점의 종결 상태이므로 배치 완료가 점프시키면 안 됨.
        verify(rawDataStatusRepository).transitionByBatchIfNotBlocked(
                2L, LsRawDataStatus.STTS_ASSIGNED, BatchTransitionService.REVIEW_OWNED_STATUSES);
        // 배치 단계 상태(LS_DATA_RAW.DATA_STTS_CD)는 COMPLETED 유지
        assertThat(raw.getDataSttsCd()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("배치완료_후_검수제출_ASSIGNED에서_PENDING_상태머신_허용")
    void 배치완료후_검수제출_상태머신_허용() {
        // given — 배치 완료가 작업 상태를 되돌리는 목표값(ASSIGNED)을 캡처한다.
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(any(), any(), any())).thenReturn(1);
        when(videoRepository.findById(5L)).thenReturn(Optional.empty());
        service.markRawDataCompleted(5L);

        org.mockito.ArgumentCaptor<String> target = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(rawDataStatusRepository).transitionByBatchIfNotBlocked(
                org.mockito.ArgumentMatchers.eq(5L), target.capture(), any());

        // when/then — ASSIGNED → PENDING 검수 제출 전이가 상태 머신에서 허용
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        assertThat(target.getValue()).isEqualTo(LsRawDataStatus.STTS_ASSIGNED);
        stateMachine.verify(target.getValue(), LsRawDataStatus.STTS_PENDING); // 예외 없으면 통과
    }

    @Test
    @DisplayName("markRawDataFailed_검수소유상태_제외_조건부UPDATE로_FAILED_전이")
    void markRawDataFailed_FAILED_영속() {
        // given
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(
                3L, LsRawDataStatus.STTS_FAILED, BatchTransitionService.REVIEW_OWNED_STATUSES))
                .thenReturn(1);
        when(videoRepository.findById(3L)).thenReturn(Optional.empty());

        // when
        service.markRawDataFailed(3L);

        // then
        verify(rawDataStatusRepository).transitionByBatchIfNotBlocked(
                3L, LsRawDataStatus.STTS_FAILED, BatchTransitionService.REVIEW_OWNED_STATUSES);
        verify(rawDataStatusRepository, never()).save(any());
    }

    @Test
    @DisplayName("배치_실패_시_dataSttsCd_가_FAILED_로_전이된다(MARKING_READY_고착_금지)")
    void markRawDataFailed_LS_DATA_RAW_FAILED_고착금지() {
        // given — 배치 진행 중(PROCESSING) 인 영상 (마킹 완료 후 처리중, 조건부 UPDATE 1행 성공)
        LsRawDataStatus stts = assignedStatus(21L);
        when(rawDataStatusRepository.findById(21L)).thenReturn(Optional.of(stts));
        when(rawDataStatusRepository.transitionByBatchIfNotBlocked(
                21L, LsRawDataStatus.STTS_FAILED, BatchTransitionService.REVIEW_OWNED_STATUSES))
                .thenReturn(1);
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-21", "cctv-1", "EVT", "GOV", LsDataRaw.PRVC_TYPE_PRVC,
                "raw/21.mp4", null, 60);
        raw.markMarkingReady();
        raw.markProcessing();
        when(videoRepository.findById(21L)).thenReturn(Optional.of(raw));

        // when — 배치 실패
        service.markRawDataFailed(21L);

        // then — LS_DATA_RAW.DATA_STTS_CD = FAILED (MARKING_READY 로 고착되지 않음)
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_FAILED);
        assertThat(raw.getDataSttsCd()).isNotEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
    }

    @Test
    @DisplayName("markRawDataMarkingReady_가_LsDataRaw를_MARKING_READY로_전이")
    void markRawDataMarkingReady_LS_DATA_RAW_MARKING_READY() {
        // given — 적재 직후 PENDING 영상
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-mr", "cctv-1", "EVT", "GOV", LsDataRaw.PRVC_TYPE_PRVC,
                "raw/mr.mp4", null, 60);
        when(videoRepository.findById(7L)).thenReturn(Optional.of(raw));

        // when — 선두 비식별 성공 후 marking-ready 전이
        service.markRawDataMarkingReady(7L);

        // then — LS_DATA_RAW.DATA_STTS_CD = MARKING_READY (작업 상태 row 는 미변경)
        assertThat(raw.getDataSttsCd()).isEqualTo(LsDataRaw.DATA_STTS_MARKING_READY);
    }

    @Test
    @DisplayName("markRawDataMarkingReady_raw_row_없으면_graceful_예외없이_통과")
    void markRawDataMarkingReady_raw_없음_graceful() {
        // given
        when(videoRepository.findById(88L)).thenReturn(Optional.empty());

        // when / then — 예외 없이 통과 (WARN 로깅만)
        service.markRawDataMarkingReady(88L);
    }

    @Test
    @DisplayName("status_row_없으면_save_미호출_예외없이_통과")
    void status_row_없음_graceful() {
        // given
        when(rawDataStatusRepository.findById(any())).thenReturn(Optional.empty());

        // when
        service.markRawDataProcessingBlocked(99L);

        // then — 배치 진행을 막지 않음 (예외 없이 통과)
        verify(rawDataStatusRepository, never()).save(any());
    }

    @Test
    @DisplayName("recordDeidentFailure_가_DE_IDNTF_YN을_F로_마킹하고_FAIL_procLog를_저장한다")
    void recordDeidentFailure_marksF_andSavesFailLog() {
        // given — 적재 직후 영상(deIdntfYn='N')
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-fail", "cctv-1", "EVT", "GOV", LsDataRaw.PRVC_TYPE_PRVC,
                "raw/fail.mp4", null, 60);
        when(videoRepository.findById(11L)).thenReturn(Optional.of(raw));

        // when
        service.recordDeidentFailure(11L, "EXTERNAL_API_ERROR", "IllegalStateException");

        // then — 'F' 마킹 + FAIL procLog 저장
        assertThat(raw.getDeIdntfYn()).isEqualTo("F");
        org.mockito.ArgumentCaptor<LsDeidentProcLog> captor =
                org.mockito.ArgumentCaptor.forClass(LsDeidentProcLog.class);
        verify(procLogRepository).save(captor.capture());
        LsDeidentProcLog saved = captor.getValue();
        assertThat(saved.getProcSttsCd()).isEqualTo(LsDeidentProcLog.FAILED);
        assertThat(saved.getDataRawSn()).isEqualTo(11L);
        assertThat(saved.getErrorCd()).isEqualTo("EXTERNAL_API_ERROR");
    }

    @Test
    @DisplayName("recordDeidentFailure_raw_row_없어도_FAIL_procLog는_NA경로로_저장된다")
    void recordDeidentFailure_rawMissing_stillSavesLog() {
        // given — 영상 row 없음
        when(videoRepository.findById(404L)).thenReturn(Optional.empty());

        // when / then — 예외 없이 FAIL procLog 저장(ORGNL_FILE_PATH_NM=N/A 폴백)
        service.recordDeidentFailure(404L, "MOCK_SOURCE_MISSING", "source not found");

        verify(procLogRepository).save(any(LsDeidentProcLog.class));
    }

    // ── D1/D2 조건부 원자 전이(tryClaimBatchQueued) ──

    @Test
    @DisplayName("브리지가_BATCH_QUEUED_전이를_영속한다 — 조건부UPDATE_영향행수1이면_true")
    void tryClaimBatchQueued_affectedOne_returnsTrue_andPersists() {
        // given — 조건부 UPDATE 가 1행 영향(전이 성공)
        java.util.Set<String> skip = java.util.Set.of(
                LsRawDataStatus.STTS_BATCH_QUEUED,
                LsRawDataStatus.STTS_PROCESSING,
                LsRawDataStatus.STTS_COMPLETED);
        when(rawDataStatusRepository.transitionToBatchQueuedIfNotSkipped(
                40L, LsRawDataStatus.STTS_BATCH_QUEUED, skip)).thenReturn(1);

        // when
        boolean claimed = service.tryClaimBatchQueued(40L, skip);

        // then — 전이 권한 획득 + UPDATE 가 즉시 커밋되어 영속(AFTER_COMMIT dirty-write 비영속 결함 차단)
        assertThat(claimed).isTrue();
        verify(rawDataStatusRepository)
                .transitionToBatchQueuedIfNotSkipped(40L, LsRawDataStatus.STTS_BATCH_QUEUED, skip);
    }

    @Test
    @DisplayName("이미_진행중이면_조건부UPDATE_영향행수0_으로_false_반환 — 멱등성(D2)")
    void tryClaimBatchQueued_affectedZero_returnsFalse() {
        // given — 조건부 UPDATE 가 0행 영향(이미 SKIP 상태이거나 row 없음)
        java.util.Set<String> skip = java.util.Set.of(
                LsRawDataStatus.STTS_BATCH_QUEUED,
                LsRawDataStatus.STTS_PROCESSING,
                LsRawDataStatus.STTS_COMPLETED);
        when(rawDataStatusRepository.transitionToBatchQueuedIfNotSkipped(
                41L, LsRawDataStatus.STTS_BATCH_QUEUED, skip)).thenReturn(0);

        // when
        boolean claimed = service.tryClaimBatchQueued(41L, skip);

        // then — 전이 실패(skip)
        assertThat(claimed).isFalse();
    }

    @Test
    @DisplayName("rawSn_null이면_UPDATE_미수행_false")
    void tryClaimBatchQueued_nullRawSn_false() {
        // when
        boolean claimed = service.tryClaimBatchQueued(null, java.util.Set.of());

        // then
        assertThat(claimed).isFalse();
        verify(rawDataStatusRepository, never())
                .transitionToBatchQueuedIfNotSkipped(any(), any(), any());
    }

    // ── FIX B(CRITICAL 재설계): 작업 상태 row 부재 시 별도 tx 로 생성(tryCreateBatchQueuedRow) ──

    private static final java.util.Set<String> SKIP = java.util.Set.of(
            LsRawDataStatus.STTS_BATCH_QUEUED,
            LsRawDataStatus.STTS_PROCESSING,
            LsRawDataStatus.STTS_COMPLETED);

    @Test
    @DisplayName("tryCreateRow_row부재시_BATCH_QUEUED_row를_saveAndFlush로_생성하고_true — 미배정_직접마킹_고착제거")
    void tryCreateBatchQueuedRow_rowAbsent_createsRow_true() {
        // given — existsById=false(진짜 부재)
        when(rawDataStatusRepository.existsById(50L)).thenReturn(false);

        // when
        boolean created = service.tryCreateBatchQueuedRow(50L);

        // then — BATCH_QUEUED row 를 saveAndFlush 로 즉시 flush 생성 + 트리거 권한 획득
        assertThat(created).isTrue();
        org.mockito.ArgumentCaptor<LsRawDataStatus> captor =
                org.mockito.ArgumentCaptor.forClass(LsRawDataStatus.class);
        verify(rawDataStatusRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getRawDataId()).isEqualTo(50L);
        assertThat(captor.getValue().getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_BATCH_QUEUED);
        // 조건부 전이는 이 메서드가 수행하지 않는다(tx1 tryClaimBatchQueued 책임).
        verify(rawDataStatusRepository, never())
                .transitionToBatchQueuedIfNotSkipped(any(), any(), any());
    }

    @Test
    @DisplayName("tryCreateRow_row가_이미_존재하면_false_생성안함 — 멱등 스킵(tx1이 이미 SKIP판정)")
    void tryCreateBatchQueuedRow_existingRow_false() {
        // given — existsById=true(row 존재)
        when(rawDataStatusRepository.existsById(52L)).thenReturn(true);

        // when
        boolean created = service.tryCreateBatchQueuedRow(52L);

        // then — 멱등 스킵(생성 안 함)
        assertThat(created).isFalse();
        verify(rawDataStatusRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("tryCreateRow_동시노드가_먼저insert_PK충돌시_DataIntegrityViolationException을_그대로_전파 — 같은tx내_재시도금지")
    void tryCreateBatchQueuedRow_concurrentInsert_propagatesException() {
        // given — row 부재로 판정 후 saveAndFlush 가 PK 충돌(다른 노드가 먼저 insert).
        //         CRITICAL: 같은 tx 내 재시도는 PG abort 로 불가하므로, 여기서 잡지 않고 전파시켜
        //         이 REQUIRES_NEW tx 를 롤백한다(호출자 MarkingBatchBridge 가 AFTER_COMMIT 에서 skip).
        when(rawDataStatusRepository.existsById(53L)).thenReturn(false);
        when(rawDataStatusRepository.saveAndFlush(any()))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("dup pk"));

        // when / then — 예외가 삼켜지지 않고 그대로 전파된다
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.tryCreateBatchQueuedRow(53L))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        // 같은 tx 내 조건부 전이 재시도를 하지 않는다(PG aborted-tx 재사용 금지).
        verify(rawDataStatusRepository, never())
                .transitionToBatchQueuedIfNotSkipped(any(), any(), any());
    }

    @Test
    @DisplayName("tryCreateRow_rawSn_null이면_false_존재조회·생성_미수행")
    void tryCreateBatchQueuedRow_nullRawSn_false() {
        // when / then
        assertThat(service.tryCreateBatchQueuedRow(null)).isFalse();
        verify(rawDataStatusRepository, never()).existsById(any());
        verify(rawDataStatusRepository, never()).saveAndFlush(any());
    }
}
