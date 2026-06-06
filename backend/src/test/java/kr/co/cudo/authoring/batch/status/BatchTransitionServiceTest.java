package kr.co.cudo.authoring.batch.status;

import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
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
    private BatchTransitionService service;

    @BeforeEach
    void setUp() {
        rawDataStatusRepository = mock(LsRawDataStatusRepository.class);
        videoRepository = mock(VideoRepository.class);
        service = new BatchTransitionService(rawDataStatusRepository, videoRepository);
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
    @DisplayName("status_row_없으면_save_미호출_예외없이_통과")
    void status_row_없음_graceful() {
        // given
        when(rawDataStatusRepository.findById(any())).thenReturn(Optional.empty());

        // when
        service.markRawDataProcessing(99L);

        // then — 배치 진행을 막지 않음 (예외 없이 통과)
        verify(rawDataStatusRepository, never()).save(any());
    }
}
