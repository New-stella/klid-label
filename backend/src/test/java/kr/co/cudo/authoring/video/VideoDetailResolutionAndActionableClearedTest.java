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
import kr.co.cudo.authoring.video.service.VideoResolutionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
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
 * 영상 상세({@code GET /v1/videos/{rawSn}})의 <b>해상도</b> 노출과 <b>조치 필요 묶음</b> 조립 검증.
 * [@design API-043] [@design SCREEN-009] [@design AC-051]
 *
 * <p>두 축을 함께 본다 — 둘 다 이 조립 지점 하나가 소유하고, 둘 다 "서버가 무엇을 싣는가" 가 계약이다.
 *
 * <ul>
 *   <li><b>해상도</b>: 조달원은 {@code LS_DATA_META} 의 {@code video.resolution} 이고
 *       ({@code LS_DATA_RAW} 에는 컬럼이 <b>없다</b>), 미상이면 <b>{@code null}</b> 이다 —
 *       fps 와 달리 폴백을 두지 않는다(표시 전용 값이라 지어내면 화면이 사실이 아닌 해상도를
 *       실값처럼 보여준다).</li>
 *   <li><b>조치 필요 묶음</b>: 감사 축이 아니라 <b>화면 축</b>을 싣는다 — 표식은 append-only 라
 *       재수행이 성공해도 해제 표식이 계속 마지막이고, 감사 축을 그대로 실으면 재수행에 성공한
 *       영상마다 배너가 영구 잔존한다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class VideoDetailResolutionAndActionableClearedTest {

    private static final long RAW_SN = 31L;

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
    @Mock private VideoResolutionResolver resolutionResolver;
    @Mock private kr.co.cudo.authoring.assignment.service.ReviewApprovalGate approvalGate;
    @Mock private kr.co.cudo.authoring.batch.status.BatchBundleFailureGate bundleFailureGate;

    @InjectMocks private VideoQueryService videoQueryService;

    @BeforeEach
    void wireRealUserNameResolver() {
        ReflectionTestUtils.setField(videoQueryService, "userNameResolver",
                new UserNameResolver(userRepository));
    }

    private LsDataRaw video() {
        LsDataRaw e = LsDataRaw.createFromIngest(
                "clip-r", "CCTV-001", "EVT", "GOV",
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
        given(batchStatusService.stagesFor(anyLong(), anyBoolean())).willReturn(List.of());
        given(batchStatusService.failureReasonFor(RAW_SN)).willReturn(null);
        given(batchStatusService.manuallySkippedBundles(RAW_SN)).willReturn(List.of());
        given(batchStatusService.clearedBundlesNeedingAction(RAW_SN)).willReturn(List.of());
        given(bundleFailureGate.failedBundles(RAW_SN)).willReturn(List.of());
    }

    /* ================= 해상도 [@design API-043] [@design SCREEN-009] ================= */

    @Test
    @DisplayName("★해상도_메타가_있으면_적재된_값이_그대로_실린다")
    void resolutionIsCarriedVerbatim() {
        stubDetailBasics();
        given(resolutionResolver.resolveResolution(RAW_SN)).willReturn("1920x1440");

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        // 서버가 파싱해 재조립하지 않는다 — 형식의 진실원은 적재 지점 하나다.
        assertThat(response.resolution()).isEqualTo("1920x1440");
    }

    /**
     * ★★fps 와 갈리는 지점 — fps 는 마킹 frameIndex 계산의 <b>입력</b>이라 미상 시 상수로 폴백하지만,
     * 해상도는 <b>표시 전용</b>이라 폴백을 두면 화면이 사실이 아닌 해상도를 실값처럼 보여준다.
     * 대체 문자(「-」)도 서버가 만들지 않는다 — 그 표기는 화면의 몫이다.
     */
    @Test
    @DisplayName("★★해상도_메타가_없으면_null이다_폴백도_대체문자도_없다")
    void missingResolutionIsNullNotAFallback() {
        stubDetailBasics();
        given(resolutionResolver.resolveResolution(RAW_SN)).willReturn(null);

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.resolution()).isNull();
        // 폴백을 둔 fps 와 대비된다(같은 응답에서 한쪽은 값이 있고 한쪽은 null 이다).
        assertThat(response.fps()).isEqualTo(30.0);
    }

    @Test
    @DisplayName("★해상도는_조달_판정기_한_곳에서만_온다")
    void resolutionComesFromTheSingleResolver() {
        stubDetailBasics();
        given(resolutionResolver.resolveResolution(RAW_SN)).willReturn("1280x720");

        videoQueryService.getOne(RAW_SN);

        Mockito.verify(resolutionResolver).resolveResolution(RAW_SN);
    }

    /* ========= 조치 필요 묶음 [@design API-043] [@design SCREEN-009] [@design AC-051] ========= */

    /**
     * ★★감사 축({@code clearedBundles})이 아니라 화면 축을 싣는다. 감사 축을 실으면 재수행에 성공한
     * 영상마다 배너가 영구 잔존한다(표식이 append-only 라 해제 표식이 계속 마지막이기 때문).
     */
    @Test
    @DisplayName("★★clearedStages는_감사_축이_아니라_조치_필요_판정을_싣는다")
    void clearedStagesCarriesTheActionableAxis() {
        stubDetailBasics();
        given(batchStatusService.clearedBundlesNeedingAction(RAW_SN)).willReturn(List.of("VLM"));
        given(resolutionResolver.resolveResolution(RAW_SN)).willReturn(null);

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.clearedStages()).containsExactly("VLM");
        Mockito.verify(batchStatusService).clearedBundlesNeedingAction(RAW_SN);
        // 감사 축은 이 응답의 조달원이 아니다 — 부르는 순간 값의 의미가 되돌아간다.
        Mockito.verify(batchStatusService, Mockito.never()).clearedBundles(anyLong());
    }

    /**
     * ★산출물을 보유해 조치가 끝난 묶음은 응답에서 빠진다 — 사용자가 신고한 화면이 이 상태였다.
     * 다른 두 축({@code skippedStages}·{@code failedStages})은 <b>이 변경의 대상이 아니다</b>.
     */
    @Test
    @DisplayName("★조치가_끝난_묶음은_빠지고_건너뜀_실패_축은_그대로다")
    void finishedBundleDropsOutWhileOtherAxesStay() {
        stubDetailBasics();
        given(batchStatusService.manuallySkippedBundles(RAW_SN)).willReturn(List.of("AUTOLABEL"));
        given(batchStatusService.clearedBundlesNeedingAction(RAW_SN)).willReturn(List.of());
        given(bundleFailureGate.failedBundles(RAW_SN)).willReturn(List.of("VLM"));
        given(resolutionResolver.resolveResolution(RAW_SN)).willReturn(null);

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.clearedStages()).isEmpty();
        assertThat(response.skippedStages()).containsExactly("AUTOLABEL");
        assertThat(response.failedStages()).containsExactly("VLM");
    }
}
