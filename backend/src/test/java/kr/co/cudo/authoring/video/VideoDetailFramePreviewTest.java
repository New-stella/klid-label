package kr.co.cudo.authoring.video;

import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.assignment.repository.LsTaskAssignmentRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.review.repository.IssueRepository;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 영상 상세({@code GET /v1/videos/{rawSn}})의 <b>프레임 미리보기 항목</b> 조립 검증.
 * [@design API-043] [@design SCREEN-009]
 *
 * <p>화면은 미리보기에 <b>이슈 점</b>을 찍고 확대 보기에 <b>프레임 번호 / 시각 / 이슈 여부</b>를
 * 그리도록 이미 만들어져 있었는데, 응답에 그 두 값이 없어 그릴 수 없었다. 이 시험이 고정하는 것은
 * "서버가 그 두 값을 무엇으로 채우는가" 다.
 *
 * <h2>고정하는 계약</h2>
 * <ol>
 *   <li><b>이슈 여부</b> — 그 프레임에 아직 해소되지 않은 문의가 있으면 참, 없으면 거짓.</li>
 *   <li><b>영상 단위 문의</b>(프레임을 가리키지 않는 문의)는 어느 프레임도 참으로 만들지 않는다.</li>
 *   <li><b>시각</b>은 추출 순번({@code FRM_NO})이 아니라 <b>실제 영상 내 위치</b>({@code VDO_FRM_NO})
 *       와 초당 프레임 수로 정한다. 두 값이 다른 프레임에서 순번을 쓰면 엉뚱한 시각이 나온다.</li>
 *   <li>위치나 초당 프레임 수를 알 수 없으면 시각을 <b>비운다</b> — {@code 0} 으로 채우지 않는다
 *       ({@code 0} 은 "영상 맨 앞"이라는 사실이라 "모른다"와 구분돼야 한다).</li>
 *   <li>기존 세 필드(프레임 식별자·프레임 번호·썸네일 주소)는 값·이름 그대로다.</li>
 *   <li><b>이슈 조회는 영상당 한 번</b>이다 — 프레임마다 부르면 N+1 이다.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class VideoDetailFramePreviewTest {

    private static final long RAW_SN = 77L;

    @Mock private VideoRepository videoRepository;
    @Mock private IngestSourceRepository ingestSourceRepository;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository lblRepository;
    @Mock private LsRawDataStatusRepository rawDataStatusRepository;
    @Mock private LsTaskAssignmentRepository taskAssignmentRepository;
    @Mock private UserRepository userRepository;
    @Mock private LsDeidentProcLogRepository deidentProcLogRepository;
    @Mock private IssueRepository issueRepository;
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

    /* ===================== 픽스처 ===================== */

    private LsDataRaw video() {
        LsDataRaw e = LsDataRaw.createFromIngest(
                "clip-preview", "CCTV-001", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_PRVC, "/nas/raw/clip.mp4", LocalDateTime.now(), 60);
        ReflectionTestUtils.setField(e, "rawSn", RAW_SN);
        return e;
    }

    /**
     * 프레임 1건. {@code frameNo}(추출 순번)와 {@code videoFrameNo}(영상 내 실제 위치)를 <b>일부러 다르게</b>
     * 넣는다 — 두 값이 같으면 어느 쪽으로 계산했는지 시험이 구분하지 못한다.
     */
    private LsDataSrc frame(long srcSn, long frameNo, Long videoFrameNo) {
        LsDataSrc src = LsDataSrc.create(RAW_SN, frameNo, videoFrameNo, "/nas/frames/raw/" + frameNo + ".jpg", null);
        ReflectionTestUtils.setField(src, "srcSn", srcSn);
        return src;
    }

    private void stubDetailBasics(List<LsDataSrc> frames) {
        given(videoRepository.findById(RAW_SN)).willReturn(Optional.of(video()));
        given(ingestSourceRepository.findSourceMeta(RAW_SN)).willReturn(null);
        given(srcRepository.countByRawSn(RAW_SN)).willReturn((long) frames.size());
        given(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).willReturn(frames);
        given(rawDataStatusRepository.findByRawDataIdIn(List.of(RAW_SN))).willReturn(List.of());
        given(deidentProcLogRepository.findAllByDataRawSnOrderByReqDtDesc(RAW_SN)).willReturn(List.of());
        given(batchStatusService.stagesFor(anyLong(), anyBoolean())).willReturn(List.of());
        given(batchStatusService.failureReasonFor(RAW_SN)).willReturn(null);
        given(batchStatusService.manuallySkippedBundles(RAW_SN)).willReturn(List.of());
        given(batchStatusService.clearedBundlesNeedingAction(RAW_SN)).willReturn(List.of());
        given(bundleFailureGate.failedBundles(RAW_SN)).willReturn(List.of());
    }

    /* ===================== ① 이슈 여부 ===================== */

    @Test
    @DisplayName("★해소되지_않은_문의가_달린_프레임만_이슈로_표시된다")
    void marksOnlyFramesWithUnresolvedInquiry() {
        stubDetailBasics(List.of(frame(101L, 0, 0L), frame(102L, 1, 25L), frame(103L, 2, 50L)));
        given(fpsResolver.resolveFps(RAW_SN)).willReturn(25.0);
        given(issueRepository.findUnresolvedSrcSnsByDataRawSn(RAW_SN)).willReturn(List.of(102L));

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.framePreviews())
                .extracting(VideoDetailResponse.FramePreviewDto::srcSn,
                        VideoDetailResponse.FramePreviewDto::hasIssue)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(101L, false),
                        org.assertj.core.groups.Tuple.tuple(102L, true),
                        org.assertj.core.groups.Tuple.tuple(103L, false));
    }

    /**
     * ★판정을 <b>이 서비스가 재유도하지 않는다</b>는 것이 계약이다 — 「해소 여부 축」·「영상 단위 문의
     * 제외」의 단일 진실원은 저장소 쿼리 하나다. 결과(빈 목록)만 단언하면 조회를 통째로 빼도 시험이
     * 통과하므로, <b>어떤 인자로 무엇을 불렀는지</b>를 함께 고정한다.
     */
    @Test
    @DisplayName("★이슈_판정은_그_영상의_미해소_문의_조회에만_의존한다_호출_인자_고정")
    void delegatesIssueDecisionToRepository() {
        stubDetailBasics(List.of(frame(101L, 0, 0L)));
        given(fpsResolver.resolveFps(RAW_SN)).willReturn(30.0);
        given(issueRepository.findUnresolvedSrcSnsByDataRawSn(RAW_SN)).willReturn(List.of());

        videoQueryService.getOne(RAW_SN);

        verify(issueRepository).findUnresolvedSrcSnsByDataRawSn(eq(RAW_SN));
    }

    /**
     * ★★영상 단위 문의(프레임을 가리키지 않는 문의)는 어느 프레임도 이슈로 만들지 않는다.
     *
     * <p>저장소 쿼리가 그런 행을 걸러 내지만, 여기서는 <b>걸러지지 않고 흘러들어온 경우에도</b>
     * 어떤 프레임에도 점이 찍히지 않는지를 본다 — 조립 지점이 그 값을 프레임 식별자처럼 다루면
     * 영상 단위 문의 하나로 화면 전체에 점이 번진다.
     */
    @Test
    @DisplayName("★★영상_단위_문의는_어느_프레임도_이슈로_만들지_않는다")
    void videoScopedInquiryMarksNoFrame() {
        stubDetailBasics(List.of(frame(101L, 0, 0L), frame(102L, 1, 25L)));
        given(fpsResolver.resolveFps(RAW_SN)).willReturn(25.0);
        // 프레임을 가리키지 않는 문의 = 프레임 식별자가 비어 있는 행.
        given(issueRepository.findUnresolvedSrcSnsByDataRawSn(RAW_SN))
                .willReturn(new ArrayList<>(Arrays.asList((Long) null)));

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.framePreviews())
                .extracting(VideoDetailResponse.FramePreviewDto::hasIssue)
                .containsExactly(false, false);
    }

    /**
     * ★★수용기준 6 — 이슈 조회는 <b>프레임 수와 무관하게 영상당 한 번</b>이다.
     *
     * <p>{@code mockingDetails(...).getInvocations()} 으로 세면 스터빙 호출까지 함께 세어져 숫자가
     * 실제 호출 수와 어긋나므로, 호출될 때마다 직접 증가시키는 계수기로 센다.
     */
    @Test
    @DisplayName("★★이슈_조회는_프레임이_많아도_영상당_한_번만_실행된다")
    void queriesIssuesOncePerVideo() {
        List<LsDataSrc> manyFrames = new ArrayList<>();
        for (long i = 0; i < 40; i++) {
            manyFrames.add(frame(1000L + i, i, i * 5));
        }
        stubDetailBasics(manyFrames);
        given(fpsResolver.resolveFps(RAW_SN)).willReturn(30.0);

        AtomicInteger calls = new AtomicInteger();
        given(issueRepository.findUnresolvedSrcSnsByDataRawSn(anyLong()))
                .willAnswer(inv -> {
                    calls.incrementAndGet();
                    return List.of(1002L);
                });

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.framePreviews()).hasSize(40);
        assertThat(calls.get()).isEqualTo(1);
    }

    /* ===================== ② 영상 내 시각 ===================== */

    /**
     * ★★이 시험이 「순번이 아니라 위치」를 가른다 — 순번(1)으로 계산하면 40ms 가, 실제 위치(25)로
     * 계산해야 1000ms 가 나온다.
     */
    @Test
    @DisplayName("★★시각은_추출_순번이_아니라_실제_영상_내_위치로_계산된다")
    void timestampUsesVideoPositionNotExtractionIndex() {
        stubDetailBasics(List.of(frame(101L, 0, 0L), frame(102L, 1, 25L), frame(103L, 2, 63L)));
        given(fpsResolver.resolveFps(RAW_SN)).willReturn(25.0);
        given(issueRepository.findUnresolvedSrcSnsByDataRawSn(RAW_SN)).willReturn(List.of());

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        // 25fps → 위치 25 = 1000ms, 위치 63 = 2520ms. 순번으로 계산하면 각각 40ms·80ms 가 된다.
        assertThat(response.framePreviews())
                .extracting(VideoDetailResponse.FramePreviewDto::timestampMs)
                .containsExactly(0L, 1000L, 2520L);
    }

    /**
     * 시각은 추출이 그 프레임을 뽑을 때 쓴 seek 위치를 그대로 재현한다(반올림 포함) — 화면이 보여주는
     * 시각과 실제로 뽑힌 지점이 어긋나면 안 되기 때문이다. 정수 절삭이면 33·29996 이 나온다.
     */
    @Test
    @DisplayName("시각은_추출이_쓴_seek_위치와_같은_식으로_반올림된다")
    void timestampRoundsLikeExtraction() {
        stubDetailBasics(List.of(frame(101L, 0, 1L), frame(102L, 1, 899L)));
        given(fpsResolver.resolveFps(RAW_SN)).willReturn(29.97);
        given(issueRepository.findUnresolvedSrcSnsByDataRawSn(RAW_SN)).willReturn(List.of());

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        // 1000/29.97 = 33.36... → 33 · 899000/29.97 = 29996.66... → 29997
        assertThat(response.framePreviews())
                .extracting(VideoDetailResponse.FramePreviewDto::timestampMs)
                .containsExactly(33L, 29997L);
    }

    /**
     * ★★수용기준 4 — 모르면 비운다. {@code 0} 은 "영상 맨 앞"이라는 <b>사실</b>이라 "모른다"와
     * 구분돼야 한다.
     */
    @Test
    @DisplayName("★★영상_내_위치를_모르는_프레임은_시각이_비어_있다_0_이_아니다")
    void timestampIsNullWhenPositionUnknown() {
        stubDetailBasics(List.of(frame(101L, 0, null), frame(102L, 1, 0L)));
        given(fpsResolver.resolveFps(RAW_SN)).willReturn(30.0);
        given(issueRepository.findUnresolvedSrcSnsByDataRawSn(RAW_SN)).willReturn(List.of());

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        // 위치 미상(레거시 행)은 null, 실제로 맨 앞인 프레임은 0 — 둘이 같은 값이 되면 안 된다.
        assertThat(response.framePreviews().get(0).timestampMs()).isNull();
        assertThat(response.framePreviews().get(1).timestampMs()).isEqualTo(0L);
    }

    /**
     * ★초당 프레임 수를 나눗셈에 그대로 넣지 않는다. 현재 해석기는 미상 시 상수로 폴백해 0 이하를
     * 돌려주지 않지만, 그 폴백은 <b>해석기의 사정</b>이라 이 조립 지점이 그것에 기대면 폴백이
     * 바뀌는 날 0 나눗셈(∞)이 시각으로 나간다.
     */
    @Test
    @DisplayName("★초당_프레임_수가_비정상이면_나눗셈에_넣지_않고_시각을_비운다")
    void timestampIsNullWhenFpsUnusable() {
        stubDetailBasics(List.of(frame(101L, 0, 25L)));
        given(fpsResolver.resolveFps(RAW_SN)).willReturn(0.0);
        given(issueRepository.findUnresolvedSrcSnsByDataRawSn(RAW_SN)).willReturn(List.of());

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.framePreviews().get(0).timestampMs()).isNull();
    }

    /** 음수 위치는 있을 수 없는 값이다 — 지어내지 않고 비운다(응답 계약상 시각은 0 이상). */
    @Test
    @DisplayName("음수_영상_내_위치는_시각을_비운다")
    void timestampIsNullWhenPositionNegative() {
        stubDetailBasics(List.of(frame(101L, 0, -1L)));
        given(fpsResolver.resolveFps(RAW_SN)).willReturn(30.0);
        given(issueRepository.findUnresolvedSrcSnsByDataRawSn(RAW_SN)).willReturn(List.of());

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.framePreviews().get(0).timestampMs()).isNull();
    }

    /* ===================== ③ 기존 세 필드 불변 ===================== */

    @Test
    @DisplayName("★기존_세_필드는_값도_의미도_그대로다_추가만_했다")
    void existingThreeFieldsUnchanged() {
        stubDetailBasics(List.of(frame(101L, 0, 0L), frame(102L, 7, 210L)));
        given(fpsResolver.resolveFps(RAW_SN)).willReturn(30.0);
        given(issueRepository.findUnresolvedSrcSnsByDataRawSn(RAW_SN)).willReturn(List.of(102L));

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        // 프레임 번호는 여전히 <b>추출 순번</b>(FRM_NO)이다 — 시각이 영상 내 위치를 쓴다고 해서
        // 이 필드까지 위치로 바뀌면 라벨링 도구 진입·정렬이 통째로 어긋난다.
        assertThat(response.framePreviews())
                .extracting(VideoDetailResponse.FramePreviewDto::srcSn,
                        VideoDetailResponse.FramePreviewDto::frameNo,
                        VideoDetailResponse.FramePreviewDto::thumbnailUrl)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(101L, 0, "/v1/frames/101/image"),
                        org.assertj.core.groups.Tuple.tuple(102L, 7, "/v1/frames/102/image"));
    }

    @Test
    @DisplayName("프레임이_없으면_미리보기는_빈_배열이고_이슈_조회_결과와_무관하다")
    void emptyPreviewsWhenNoFrames() {
        stubDetailBasics(List.of());
        given(fpsResolver.resolveFps(RAW_SN)).willReturn(30.0);
        given(issueRepository.findUnresolvedSrcSnsByDataRawSn(RAW_SN)).willReturn(List.of(999L));

        VideoDetailResponse response = videoQueryService.getOne(RAW_SN);

        assertThat(response.framePreviews()).isEmpty();
    }
}
