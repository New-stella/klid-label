package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.dto.KpstDeidentReportSummary;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoFpsResolver;
import kr.co.cudo.authoring.video.service.VideoQueryService;
import kr.co.cudo.authoring.video.service.VideoResolutionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * R14 — 영상 상세({@code GET /v1/videos/{rawSn}})의 <b>비식별 이력</b> 노출 검증.
 *
 * <p>이력의 원천은 {@code LS_DEIDENT_PROC_LOG} 다 — 이 테이블은 회차마다 새 행을 INSERT 하므로
 * 별도 이력 테이블 없이 그 행들이 그대로 이력이 된다.
 */
@ExtendWith(MockitoExtension.class)
class VideoDetailDeidentHistoryTest {

    @Mock private VideoRepository videoRepository;
    @Mock private IngestSourceRepository ingestSourceRepository;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository lblRepository;
    @Mock private LsRawDataStatusRepository rawDataStatusRepository;
    @Mock private LsTaskAssignmentRepository taskAssignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private LsDeidentProcLogRepository deidentProcLogRepository;
    // 프레임 이슈 점 조달 — 이 시험들의 축은 아니지만 상세 조립이 영상당 1회 부른다(미스텁 시 빈 목록).
    @Mock private kr.co.cudo.authoring.review.repository.IssueRepository issueRepository;
    @Mock private kr.co.cudo.authoring.batch.status.BatchStatusService batchStatusService;
    @Mock private kr.co.cudo.authoring.eventtype.service.EventTypeService eventTypeService;
    @Mock private VideoFpsResolver fpsResolver;
    // 해상도 표시값 조달(video.resolution) — 미상이면 null 이라 폴백 스텁이 필요 없다.
    @Mock private VideoResolutionResolver resolutionResolver;
    /** P2b — 영상 상세가 승인 이력을 함께 내린다(화면이 신고·폐기 버튼을 미리 비활성화하는 근거). */
    @Mock private kr.co.cudo.authoring.assignment.service.ReviewApprovalGate approvalGate;
    @Mock private kr.co.cudo.authoring.batch.status.BatchBundleFailureGate bundleFailureGate;

    /**
     * 고를 수 있는 검증 이벤트 유형 카탈로그 — 관제가 유형을 보내지 않은 영상에서만 읽힌다.
     * {@code @InjectMocks} 는 목이 없는 타입에 null 을 넣으므로 여기서 채운다(미스텁 시 빈 목록).
     */
    @Mock private kr.co.cudo.authoring.sysconfig.repository.LsVrfcEvntTypeRepository vrfcEvntTypeRepository;

    @InjectMocks private VideoQueryService videoQueryService;

    private static final long RAW_SN = 9001L;

    @BeforeEach
    void wireRealUserNameResolver() {
        ReflectionTestUtils.setField(videoQueryService, "userNameResolver",
                new UserNameResolver(userRepository));
    }

    private LsDataRaw video() {
        LsDataRaw e = LsDataRaw.createFromIngest(
                "clip-x", "cctv-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/raw/clip.mp4", LocalDateTime.now(), 60);
        ReflectionTestUtils.setField(e, "rawSn", RAW_SN);
        return e;
    }

    private void stubDetailBasics() {
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(video()));
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(null);
        given(srcRepository.countByRawSn(RAW_SN)).willReturn(0L);
        given(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).willReturn(List.of());
        given(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).willReturn(List.of());
        given(batchStatusService.stagesFor(anyLong(), anyBoolean())).willReturn(List.of());
        given(fpsResolver.resolveFps(RAW_SN)).willReturn(30.0);
    }

    private LsDeidentProcLog procLog(long procLogSn, LocalDateTime reqDt,
                                     KpstDeidentReportSummary report) {
        LsDeidentProcLog log = LsDeidentProcLog.request(RAW_SN, "req-" + procLogSn,
                "/nas/raw/clip.mp4", "batch");
        ReflectionTestUtils.setField(log, "procLogSn", procLogSn);
        ReflectionTestUtils.setField(log, "reqDt", reqDt);
        if (report != null) {
            log.recordReport(report);
        }
        return log;
    }

    @Test
    @DisplayName("영상_상세가_비식별_이력을_리포트_집계값과_함께_내려준다")
    void detailExposesDeidentHistoryWithReport() {
        // given
        stubDetailBasics();
        LsDeidentProcLog done = procLog(2L, LocalDateTime.of(2026, 8, 11, 9, 0),
                new KpstDeidentReportSummary(12L, 3L, 5400L,
                        LocalDateTime.of(2026, 8, 11, 9, 1),
                        LocalDateTime.of(2026, 8, 11, 9, 6), "/nas/raw/clip.mp4"));
        done.markDownloaded("/nas/deid/clip-mask.mp4");
        given(deidentProcLogRepository.findAllByDataRawSnOrderByReqDtDesc(RAW_SN))
                .willReturn(List.of(done));

        // when
        VideoDetailResponse res = videoQueryService.getOne(RAW_SN);

        // then
        assertThat(res.deidentHistory()).hasSize(1);
        VideoDetailResponse.DeidentHistoryDto item = res.deidentHistory().get(0);
        assertThat(item.procLogSn()).isEqualTo(2L);
        assertThat(item.procSttsCd()).isEqualTo(LsDeidentProcLog.SUCCEEDED);
        assertThat(item.faceDtctCnt()).isEqualTo(12L);
        assertThat(item.noPltDtctCnt()).isEqualTo(3L);
        assertThat(item.frmeCnt()).isEqualTo(5400L);
        assertThat(item.prcsBgngDt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 9, 1));
        assertThat(item.prcsEndDt()).isEqualTo(LocalDateTime.of(2026, 8, 11, 9, 6));
    }

    @Test
    @DisplayName("비식별_이력에_파일_경로를_싣지_않는다")
    void deidentHistoryCarriesNoFilePath() {
        // given — 원본/비식별/리포트 경로는 모두 PII 위치 정보다(CWE-359). 응답에 넣지 않는다.
        stubDetailBasics();
        LsDeidentProcLog done = procLog(1L, LocalDateTime.now(),
                new KpstDeidentReportSummary(1L, 1L, 1L, null, null, "/nas/raw/clip.mp4"));
        done.markDownloaded("/nas/deid/clip-mask.mp4");
        given(deidentProcLogRepository.findAllByDataRawSnOrderByReqDtDesc(RAW_SN))
                .willReturn(List.of(done));

        // when
        VideoDetailResponse res = videoQueryService.getOne(RAW_SN);

        // then — DTO 어느 컴포넌트에도 경로 문자열이 없다.
        assertThat(res.deidentHistory().get(0).toString())
                .doesNotContain("/nas/raw").doesNotContain("/nas/deid");
    }

    @Test
    @DisplayName("이력이_없으면_빈_목록이며_null_이_아니다")
    void emptyHistoryIsEmptyList() {
        stubDetailBasics();
        given(deidentProcLogRepository.findAllByDataRawSnOrderByReqDtDesc(RAW_SN))
                .willReturn(List.of());

        VideoDetailResponse res = videoQueryService.getOne(RAW_SN);

        assertThat(res.deidentHistory()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("같은_요청일시_회차는_원장번호_내림차순으로_안정_정렬된다")
    void tiesAreOrderedByProcLogSnDesc() {
        // given — REQ_DT 만으로 정렬하면 재비식별 회차가 같은 초에 들어왔을 때 순서가 흔들린다.
        stubDetailBasics();
        LocalDateTime same = LocalDateTime.of(2026, 8, 11, 9, 0);
        given(deidentProcLogRepository.findAllByDataRawSnOrderByReqDtDesc(RAW_SN))
                .willReturn(List.of(procLog(1L, same, null), procLog(3L, same, null),
                        procLog(2L, same, null)));

        VideoDetailResponse res = videoQueryService.getOne(RAW_SN);

        assertThat(res.deidentHistory())
                .extracting(VideoDetailResponse.DeidentHistoryDto::procLogSn)
                .containsExactly(3L, 2L, 1L);
    }

    @Test
    @DisplayName("이력이_비정상적으로_많아도_상한_건수만_내려준다")
    void historyIsBounded() {
        // given — 재위탁 실패가 누적되면 행이 무한정 늘 수 있다(CWE-770).
        stubDetailBasics();
        List<LsDeidentProcLog> many = IntStream.rangeClosed(1, VideoQueryService.DEIDENT_HISTORY_MAX + 20)
                .mapToObj(i -> procLog(i, LocalDateTime.of(2026, 8, 11, 9, 0).plusMinutes(i), null))
                .toList();
        given(deidentProcLogRepository.findAllByDataRawSnOrderByReqDtDesc(RAW_SN)).willReturn(many);

        VideoDetailResponse res = videoQueryService.getOne(RAW_SN);

        assertThat(res.deidentHistory()).hasSize(VideoQueryService.DEIDENT_HISTORY_MAX);
    }

    @Test
    @DisplayName("기존_from_오버로드는_비식별_이력을_빈_목록으로_채운다_하위호환")
    void legacyFromOverloadsKeepWorking() {
        // given — 외부 FE 팀이 쓰는 응답 계약이라 기존 시그니처·필드는 그대로 살아 있어야 한다.
        LsDataRaw e = video();

        // then
        assertThat(VideoDetailResponse.from(e).deidentHistory()).isEmpty();
        assertThat(VideoDetailResponse.from(e, null, null, 0L).deidentHistory()).isEmpty();
        assertThat(VideoDetailResponse.from(e, null, null, 0L, List.of()).deidentHistory()).isEmpty();
        assertThat(VideoDetailResponse.from(e, null, null, 0L, List.of(), null)
                .deidentHistory()).isEmpty();
        assertThat(VideoDetailResponse.from(e, null, null, 0L, List.of(), null, List.of())
                .deidentHistory()).isEmpty();
        assertThat(VideoDetailResponse.from(e, null, null, 0L, List.of(), null, List.of(), 30.0)
                .deidentHistory()).isEmpty();
    }

    @Test
    @DisplayName("기존_응답_필드는_그대로_유지된다_하위호환")
    void legacyFieldsUnchanged() {
        LsDataRaw e = video();

        VideoDetailResponse res = VideoDetailResponse.from(e, "CCTV-1", "서울", 3L,
                List.of(), "APPROVED", List.of(), 25.0);

        assertThat(res.id()).isEqualTo(RAW_SN);
        assertThat(res.rawSn()).isEqualTo(RAW_SN);
        assertThat(res.cctvName()).isEqualTo("CCTV-1");
        assertThat(res.localGov()).isEqualTo("서울");
        assertThat(res.frameCount()).isEqualTo(3L);
        assertThat(res.reviewSttsCd()).isEqualTo("APPROVED");
        assertThat(res.fps()).isEqualTo(25.0);
        assertThat(res.derivative()).isFalse();
        assertThat(res.filePath()).isEqualTo("/nas/raw/clip.mp4");
    }
}
