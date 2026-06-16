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
    @DisplayName("markRawDataProcessing_상태_PROCESSING_전이_후_save_명시호출")
    void markRawDataProcessing_PROCESSING_영속() {
        // given
        LsRawDataStatus stts = assignedStatus(1L);
        when(rawDataStatusRepository.findById(1L)).thenReturn(Optional.of(stts));

        // when
        service.markRawDataProcessing(1L);

        // then — dirty checking 에 의존하지 않고 명시적으로 save 호출
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_PROCESSING);
        verify(rawDataStatusRepository).save(stts);
    }

    @Test
    @DisplayName("markRawDataCompleted_작업상태는_ASSIGNED복귀_배치단계만_LS_DATA_RAW_COMPLETED")
    void markRawDataCompleted_작업상태_ASSIGNED복귀() {
        // given — 배치 진행 중(PROCESSING) 인 작업 상태
        LsRawDataStatus stts = LsRawDataStatus.initial(2L);
        stts.markAssigned();
        stts.transitionTo(LsRawDataStatus.STTS_PROCESSING);
        when(rawDataStatusRepository.findById(2L)).thenReturn(Optional.of(stts));
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "clip-2", "cctv-1", "EVT", "GOV", LsDataRaw.PRVC_TYPE_ANONY,
                "raw/2.mp4", null, 60);
        when(videoRepository.findById(2L)).thenReturn(Optional.of(raw));

        // when — 배치 완료
        service.markRawDataCompleted(2L);

        // then — 작업(워크플로우) 상태는 ASSIGNED 로 복귀해 검수 제출(ASSIGNED→PENDING)이 가능해야 함.
        //        COMPLETED 는 검수 승인 시점의 종결 상태이므로 배치 완료가 점프시키면 안 됨.
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_ASSIGNED);
        // 배치 단계 상태(LS_DATA_RAW.DATA_STTS_CD)는 COMPLETED 유지
        assertThat(raw.getDataSttsCd()).isEqualTo("COMPLETED");
        verify(rawDataStatusRepository).save(stts);
    }

    @Test
    @DisplayName("배치완료_후_검수제출_ASSIGNED에서_PENDING_상태머신_허용")
    void 배치완료후_검수제출_상태머신_허용() {
        // given — 배치 완료 직후 작업 상태
        LsRawDataStatus stts = LsRawDataStatus.initial(5L);
        stts.markAssigned();
        stts.transitionTo(LsRawDataStatus.STTS_PROCESSING);
        when(rawDataStatusRepository.findById(5L)).thenReturn(Optional.of(stts));
        when(videoRepository.findById(5L)).thenReturn(Optional.empty());
        service.markRawDataCompleted(5L);

        // when/then — ASSIGNED → PENDING 검수 제출 전이가 상태 머신에서 허용
        ReviewStateMachine stateMachine = new ReviewStateMachine();
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_ASSIGNED);
        stateMachine.verify(stts.getDataSttsCd(), LsRawDataStatus.STTS_PENDING); // 예외 없으면 통과
    }

    @Test
    @DisplayName("markRawDataFailed_상태_FAILED_전이_후_save_명시호출")
    void markRawDataFailed_FAILED_영속() {
        // given
        LsRawDataStatus stts = assignedStatus(3L);
        when(rawDataStatusRepository.findById(3L)).thenReturn(Optional.of(stts));

        // when
        service.markRawDataFailed(3L);

        // then
        assertThat(stts.getDataSttsCd()).isEqualTo(LsRawDataStatus.STTS_FAILED);
        verify(rawDataStatusRepository).save(stts);
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
        service.markRawDataProcessing(99L);

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
}
