package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.video.dto.VideoDetailResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.IngestSourceRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoFpsResolver;
import kr.co.cudo.authoring.video.service.VideoQueryService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 영상 상세({@code GET /v1/videos/{rawSn}})의 <b>배치 실패 사유</b> 노출 검증. [@design API-043]
 *
 * <p>수용 기준 둘.
 * <ul>
 *   <li>실패가 아니면 {@code null} — 정상 영상에 사유가 뜨면 안 된다.</li>
 *   <li><b>단계를 특정할 수 없는 실패</b>({@code stages} 가 빈 배열)에도 사유는 내려간다 —
 *       그래서 사유는 {@code stages} 안이 아니라 <b>영상 단위 필드</b>다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class VideoDetailBatchFailureReasonTest {

    private static final long RAW_SN = 12L;

    @Mock private VideoRepository videoRepository;
    @Mock private IngestSourceRepository ingestSourceRepository;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository lblRepository;
    @Mock private LsRawDataStatusRepository rawDataStatusRepository;
    @Mock private LsTaskAssignmentRepository taskAssignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private LsDeidentProcLogRepository deidentProcLogRepository;
    @Mock private kr.co.cudo.authoring.batch.status.BatchStatusService batchStatusService;
    @Mock private kr.co.cudo.authoring.eventtype.service.EventTypeService eventTypeService;
    @Mock private VideoFpsResolver fpsResolver;
    @Mock private kr.co.cudo.authoring.assignment.service.ReviewApprovalGate approvalGate;

    @InjectMocks private VideoQueryService videoQueryService;

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
        given(fpsResolver.resolveFps(RAW_SN)).willReturn(30.0);
        given(deidentProcLogRepository.findAllByDataRawSnOrderByReqDtDesc(RAW_SN)).willReturn(List.of());
        given(batchStatusService.manuallySkippedBundles(RAW_SN)).willReturn(List.of());
    }

    @Test
    @DisplayName("실패가_아닌_영상은_배치실패사유가_null이다")
    void nullWhenNotFailed() {
        // given
        stubDetailBasics();
        given(batchStatusService.stagesFor(anyLong(), anyBoolean())).willReturn(List.of());
        given(batchStatusService.failureReasonFor(RAW_SN)).willReturn(null);

        // when
        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        // then
        assertThat(response.batchFailureReason()).isNull();
    }

    @Test
    @DisplayName("★단계를_특정할_수_없는_실패도_사유가_내려간다_stages가_비어도")
    void reasonSurvivesEmptyStages() {
        // given — dev 실측: rawSn=12 는 PROC_STEP_CD='FAILED' 라 stages 가 빈 배열이다.
        //   사유를 stages 안에 넣었다면 이 영상은 아무것도 볼 수 없었다.
        stubDetailBasics();
        given(batchStatusService.stagesFor(anyLong(), anyBoolean())).willReturn(List.of());
        given(batchStatusService.failureReasonFor(RAW_SN)).willReturn("배치 처리 중 오류가 발생했습니다.");

        // when
        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        // then
        assertThat(response.stages()).isEmpty();
        assertThat(response.batchFailureReason()).isEqualTo("배치 처리 중 오류가 발생했습니다.");
    }

    @Test
    @DisplayName("단계가_있는_실패는_단계별_사유와_stages를_함께_내린다")
    void reasonWithStages() {
        // given — dev 실측: rawSn=43·45 는 FRAME_EXTRACT 에서 실패했다.
        stubDetailBasics();
        given(batchStatusService.stagesFor(anyLong(), anyBoolean())).willReturn(List.of(
                new kr.co.cudo.authoring.batch.status.BatchStageProgressMapper.StageStatus(
                        "FRAME_EXTRACT", "FAIL", null)));
        given(batchStatusService.failureReasonFor(RAW_SN))
                .willReturn("영상에서 프레임을 추출하지 못했습니다.");

        // when
        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        // then
        assertThat(response.stages()).hasSize(1);
        assertThat(response.batchFailureReason()).isEqualTo("영상에서 프레임을 추출하지 못했습니다.");
    }

    @Test
    @DisplayName("배치실패사유는_내부_원문을_담지_않는다")
    void reasonNeverContainsRawInternals() {
        // given — 서비스는 정책이 만든 문구를 그대로 전달할 뿐이고, 정책은 ERR_MSG_CN 을 읽지 않는다.
        //   여기서는 응답 필드가 정책 산출물 이외의 것으로 채워지지 않음을 고정한다.
        stubDetailBasics();
        given(batchStatusService.stagesFor(anyLong(), anyBoolean())).willReturn(List.of());
        given(batchStatusService.failureReasonFor(RAW_SN))
                .willReturn("영상에서 프레임을 추출하지 못했습니다.");

        // when
        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        // then
        assertThat(response.batchFailureReason())
                .doesNotContain("constraint")
                .doesNotContain("ERROR:")
                .doesNotContain("Exception")
                .doesNotContain("/");
    }

    // ── 수동 스킵 단계 목록 (API-043 v8) ──────────────────────────────

    @Test
    @DisplayName("스킵된_단계가_없으면_빈_배열이다_null이_아니다")
    void skippedStagesEmptyByDefault() {
        // given — 화면이 null 분기를 하지 않아도 되도록 응답 계약은 "빈 배열"이다.
        stubDetailBasics();
        given(batchStatusService.stagesFor(anyLong(), anyBoolean())).willReturn(List.of());

        // when
        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        // then
        assertThat(response.skippedStages()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("★건너뛴_묶음은_진행축에_흔적이_없어도_목록으로_내려간다")
    void skippedBundlesExposedEvenWhenStagesEmpty() {
        // given — 건너뛴 묶음은 markStage 를 타지 않아 stages 만으로는 구분할 수 없다.
        //   이 필드가 없으면 화면이 되돌리기 버튼을 띄울 근거를 잃는다.
        stubDetailBasics();
        given(batchStatusService.stagesFor(anyLong(), anyBoolean())).willReturn(List.of());
        given(batchStatusService.manuallySkippedBundles(RAW_SN)).willReturn(List.of("VLM"));

        // when
        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        // then
        assertThat(response.stages()).isEmpty();
        assertThat(response.skippedStages()).containsExactly("VLM");
    }

    @Test
    @DisplayName("스킵_목록은_묶음_선언_순서로_내려간다")
    void skippedBundlesKeepDeclarationOrder() {
        // given — 판정기가 고정 순서로 만들어 준 목록을 서비스가 재정렬하지 않는다.
        stubDetailBasics();
        given(batchStatusService.stagesFor(anyLong(), anyBoolean())).willReturn(List.of());
        given(batchStatusService.manuallySkippedBundles(RAW_SN))
                .willReturn(List.of("VLM", "AUTOLABEL"));

        // when
        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        // then
        assertThat(response.skippedStages()).containsExactly("VLM", "AUTOLABEL");
    }
}
