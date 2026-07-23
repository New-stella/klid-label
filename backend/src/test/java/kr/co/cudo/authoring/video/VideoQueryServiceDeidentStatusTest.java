package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.video.dto.VideoSummaryResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.MngResourceCctvRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 2 (AC3) — 영상 목록 응답이 각 영상의 비식별 상태(IN_PROGRESS/FAILED/DONE/NONE)를
 * LS_DATA_RAW.DE_IDENT_YN + 최신 LS_DEIDENT_PROC_LOG 로 파생해 내려주는지 검증한다.
 *
 * <p>HIGH 시나리오 방어: ① 실패('F'/procLog FAILED)와 진행중을 섞지 않음, ② 목록 N건이어도
 * procLog 조회는 batch(IN) 1회만 — 건별 조회 금지.
 */
@ExtendWith(MockitoExtension.class)
class VideoQueryServiceDeidentStatusTest {

    @Mock private VideoRepository videoRepository;
    @Mock private MngResourceCctvRepository cctvRepository;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository lblRepository;
    @Mock private LsRawDataStatusRepository rawDataStatusRepository;
    @Mock private LsTaskAssignmentRepository taskAssignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private LsDeidentProcLogRepository deidentProcLogRepository;

    @InjectMocks private VideoQueryService videoQueryService;

    private LsDataRaw video(long rawSn, String deidYn) {
        LsDataRaw e = LsDataRaw.createFromIngest(
                "CLIP-" + rawSn, "CCTV-001", "EVT-FIRE", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/c" + rawSn + ".mp4",
                LocalDateTime.now(), 30);
        ReflectionTestUtils.setField(e, "rawSn", rawSn);
        if (!"N".equals(deidYn)) {
            e.markDeidentified(deidYn); // 'Y' / 'F'
        }
        return e;
    }

    private LsDeidentProcLog requestedLog(long rawSn) {
        return LsDeidentProcLog.request(rawSn, "req-" + rawSn, "/var/raw/c" + rawSn + ".mp4", "tester");
    }

    private LsDeidentProcLog failedLog(long rawSn) {
        LsDeidentProcLog log = requestedLog(rawSn);
        log.fail("ERR_TIMEOUT", "deidentify failed");
        return log;
    }

    /** KPST 위탁 직후 — POLL_STTS=WAITING, PROC_STTS=REQUESTED (진행 중). */
    private LsDeidentProcLog waitingLog(long rawSn) {
        LsDeidentProcLog log = requestedLog(rawSn);
        log.markKpstSubmitted(100L + rawSn, 200L + rawSn);
        return log;
    }

    /** KPST 폴링 진행 — POLL_STTS=POLLING (진행 중). */
    private LsDeidentProcLog pollingLog(long rawSn) {
        LsDeidentProcLog log = waitingLog(rawSn);
        log.markPolling();
        return log;
    }

    /** list(null,null) 경로에서 항상 호출되는 enrich 콜래보레이터를 빈 결과로 스텁한다. */
    private void stubEmptyEnrich(Page<LsDataRaw> page, Pageable pageable) {
        given(videoRepository.findAllByOrgnlRawSnIsNull(any(Pageable.class))).willReturn(page);
        given(videoRepository.findLatestExportsByRawSns(anyCollection())).willReturn(List.of());
        given(rawDataStatusRepository.findByRawDataIdIn(anyCollection())).willReturn(List.of());
        given(taskAssignmentRepository.findByTaskTypeCdAndRawDataIdInOrderByRegDtDesc(anyString(), anyCollection()))
                .willReturn(List.of());
    }

    @Test
    @DisplayName("비식별_진행중_영상은_deidentStatus가_IN_PROGRESS로_내려온다")
    void inProgress() {
        Pageable pageable = PageRequest.of(0, 20);
        LsDataRaw v = video(1L, "N");
        Page<LsDataRaw> page = new PageImpl<>(List.of(v), pageable, 1);
        stubEmptyEnrich(page, pageable);
        given(deidentProcLogRepository.findLatestByDataRawSnIn(anyCollection()))
                .willReturn(List.of(requestedLog(1L)));

        Page<VideoSummaryResponse> result = videoQueryService.list(pageable, null, null);

        assertThat(result.getContent().get(0).deidentStatus())
                .isEqualTo(VideoSummaryResponse.DeidentStatus.IN_PROGRESS);
        assertThat(result.getContent().get(0).deIdntfYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("비식별_실패_영상은_deidentStatus가_FAILED로_내려온다")
    void failed() {
        Pageable pageable = PageRequest.of(0, 20);
        // deIdntfYn='F' + 최신 procLog FAILED — 둘 다 실패 신호. 진행중과 섞이면 안 됨.
        LsDataRaw v = video(2L, "F");
        Page<LsDataRaw> page = new PageImpl<>(List.of(v), pageable, 1);
        stubEmptyEnrich(page, pageable);
        given(deidentProcLogRepository.findLatestByDataRawSnIn(anyCollection()))
                .willReturn(List.of(failedLog(2L)));

        Page<VideoSummaryResponse> result = videoQueryService.list(pageable, null, null);

        assertThat(result.getContent().get(0).deidentStatus())
                .isEqualTo(VideoSummaryResponse.DeidentStatus.FAILED);
    }

    @Test
    @DisplayName("비식별_실패는_deIdntfYn_F만으로도_procLog없이_FAILED로_파생된다")
    void failedByYnOnly() {
        Pageable pageable = PageRequest.of(0, 20);
        LsDataRaw v = video(22L, "F");
        Page<LsDataRaw> page = new PageImpl<>(List.of(v), pageable, 1);
        stubEmptyEnrich(page, pageable);
        given(deidentProcLogRepository.findLatestByDataRawSnIn(anyCollection())).willReturn(List.of());

        Page<VideoSummaryResponse> result = videoQueryService.list(pageable, null, null);

        assertThat(result.getContent().get(0).deidentStatus())
                .isEqualTo(VideoSummaryResponse.DeidentStatus.FAILED);
    }

    @Test
    @DisplayName("비식별_완료(deIdntfYn=Y)_영상은_deidentStatus가_DONE으로_내려온다")
    void done() {
        Pageable pageable = PageRequest.of(0, 20);
        // 'Y' 가 최우선 — 최신 procLog 가 진행/실패여도 DONE 이 이긴다.
        LsDataRaw v = video(3L, "Y");
        Page<LsDataRaw> page = new PageImpl<>(List.of(v), pageable, 1);
        stubEmptyEnrich(page, pageable);
        given(deidentProcLogRepository.findLatestByDataRawSnIn(anyCollection()))
                .willReturn(List.of(requestedLog(3L)));

        Page<VideoSummaryResponse> result = videoQueryService.list(pageable, null, null);

        assertThat(result.getContent().get(0).deidentStatus())
                .isEqualTo(VideoSummaryResponse.DeidentStatus.DONE);
        assertThat(result.getContent().get(0).deIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("procLog없고_deIdntfYn_N이면_deidentStatus가_NONE")
    void none() {
        Pageable pageable = PageRequest.of(0, 20);
        LsDataRaw v = video(4L, "N");
        Page<LsDataRaw> page = new PageImpl<>(List.of(v), pageable, 1);
        stubEmptyEnrich(page, pageable);
        given(deidentProcLogRepository.findLatestByDataRawSnIn(anyCollection())).willReturn(List.of());

        Page<VideoSummaryResponse> result = videoQueryService.list(pageable, null, null);

        assertThat(result.getContent().get(0).deidentStatus())
                .isEqualTo(VideoSummaryResponse.DeidentStatus.NONE);
    }

    @Test
    @DisplayName("비식별상태_procLog만FAILED이고_deIdntfYn_N이면_FAILED")
    void failedByProcLogOnlyWhenYnN() {
        // given: deIdntfYn='N' 인데 최신 procLog 만 FAILED — procLog FAILED 단독 분기 검증
        Pageable pageable = PageRequest.of(0, 20);
        LsDataRaw v = video(5L, "N");
        Page<LsDataRaw> page = new PageImpl<>(List.of(v), pageable, 1);
        stubEmptyEnrich(page, pageable);
        given(deidentProcLogRepository.findLatestByDataRawSnIn(anyCollection()))
                .willReturn(List.of(failedLog(5L)));

        // when
        Page<VideoSummaryResponse> result = videoQueryService.list(pageable, null, null);

        // then: deIdntfYn 은 'N' 이지만 procLog FAILED 로 FAILED 파생
        assertThat(result.getContent().get(0).deidentStatus())
                .isEqualTo(VideoSummaryResponse.DeidentStatus.FAILED);
        assertThat(result.getContent().get(0).deIdntfYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("비식별상태_KPST_폴링대기중이면_IN_PROGRESS")
    void inProgressWhenKpstWaiting() {
        // given: KPST 위탁 직후(POLL_STTS=WAITING, PROC_STTS=REQUESTED)
        Pageable pageable = PageRequest.of(0, 20);
        LsDataRaw v = video(6L, "N");
        Page<LsDataRaw> page = new PageImpl<>(List.of(v), pageable, 1);
        stubEmptyEnrich(page, pageable);
        given(deidentProcLogRepository.findLatestByDataRawSnIn(anyCollection()))
                .willReturn(List.of(waitingLog(6L)));

        // when
        Page<VideoSummaryResponse> result = videoQueryService.list(pageable, null, null);

        // then
        assertThat(result.getContent().get(0).deidentStatus())
                .isEqualTo(VideoSummaryResponse.DeidentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("비식별상태_KPST_폴링진행중이면_IN_PROGRESS")
    void inProgressWhenPolling() {
        // given: 폴링 진행 중(POLL_STTS=POLLING)
        Pageable pageable = PageRequest.of(0, 20);
        LsDataRaw v = video(7L, "N");
        Page<LsDataRaw> page = new PageImpl<>(List.of(v), pageable, 1);
        stubEmptyEnrich(page, pageable);
        given(deidentProcLogRepository.findLatestByDataRawSnIn(anyCollection()))
                .willReturn(List.of(pollingLog(7L)));

        // when
        Page<VideoSummaryResponse> result = videoQueryService.list(pageable, null, null);

        // then
        assertThat(result.getContent().get(0).deidentStatus())
                .isEqualTo(VideoSummaryResponse.DeidentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("비식별완료_deIdntfYn_Y는_procLog_FAILED여도_DONE_우선")
    void doneEvenWhenProcLogFailed() {
        // given: deIdntfYn='Y' 인데 최신 procLog 는 FAILED — 'Y' 최우선 보장
        Pageable pageable = PageRequest.of(0, 20);
        LsDataRaw v = video(8L, "Y");
        Page<LsDataRaw> page = new PageImpl<>(List.of(v), pageable, 1);
        stubEmptyEnrich(page, pageable);
        given(deidentProcLogRepository.findLatestByDataRawSnIn(anyCollection()))
                .willReturn(List.of(failedLog(8L)));

        // when
        Page<VideoSummaryResponse> result = videoQueryService.list(pageable, null, null);

        // then: FAILED procLog 가 있어도 DONE 이 우선
        assertThat(result.getContent().get(0).deidentStatus())
                .isEqualTo(VideoSummaryResponse.DeidentStatus.DONE);
        assertThat(result.getContent().get(0).deIdntfYn()).isEqualTo("Y");
    }

    @Test
    @DisplayName("목록_여러건_조회시_procLog를_건별조회하지_않고_batch로_1회_조회한다")
    void batchLookupOnce() {
        int size = 5;
        Pageable pageable = PageRequest.of(0, size);
        List<LsDataRaw> rows = IntStream.rangeClosed(1, size)
                .mapToObj(i -> video(i, i % 2 == 0 ? "N" : "Y")).toList();
        Page<LsDataRaw> page = new PageImpl<>(rows, pageable, size);
        stubEmptyEnrich(page, pageable);
        given(deidentProcLogRepository.findLatestByDataRawSnIn(anyCollection()))
                .willReturn(IntStream.rangeClosed(1, size)
                        .filter(i -> i % 2 == 0)
                        .mapToObj(i -> requestedLog(i)).toList());

        Page<VideoSummaryResponse> result = videoQueryService.list(pageable, null, null);

        // batch(IN) 1회 — 건별 조회(findAllByDataRawSnOrderByReqDtDesc) 금지
        verify(deidentProcLogRepository, times(1)).findLatestByDataRawSnIn(anyCollection());
        verify(deidentProcLogRepository, never()).findAllByDataRawSnOrderByReqDtDesc(anyLong());

        assertThat(result.getContent()).hasSize(size);
        // 홀수 rawSn=Y → DONE, 짝수 rawSn=N + REQUESTED procLog → IN_PROGRESS
        assertThat(result.getContent().get(0).deidentStatus())
                .isEqualTo(VideoSummaryResponse.DeidentStatus.DONE);
        assertThat(result.getContent().get(1).deidentStatus())
                .isEqualTo(VideoSummaryResponse.DeidentStatus.IN_PROGRESS);
    }
}
