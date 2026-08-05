package kr.co.cudo.authoring.controlnotify.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.controlnotify.dto.TaskLabelsResponse;
import kr.co.cudo.authoring.controlnotify.dto.TaskMetaResponse;
import kr.co.cudo.authoring.controlnotify.dto.TaskSummaryResponse;
import kr.co.cudo.authoring.user.entity.LsAcntUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TaskQueryService 단위 테스트 (Mockito).
 *
 * <p>회귀 방어 대상:
 * <ul>
 *   <li><b>D-ISSUE-45 / CWE-770</b>: 요약은 COUNT 집계로만, 라벨 목록은 <b>페이징</b>으로 반환하며
 *       요청 size 가 {@link TaskQueryService#MAX_PAGE_SIZE} 로 클램프된다.</li>
 *   <li><b>D-ISSUE-41</b>: 검수자명은 상수(null 하드코딩)가 아니라 DB 실측 조회값이다.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class TaskQueryServiceTest {

    @Mock private VideoRepository videoRepository;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository lblRepository;
    @Mock private LsDataMetaRepository metaRepository;
    @Mock private LsTaskEventLogRepository taskEventLogRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private TaskQueryService taskQueryService;

    private LsDataRaw sampleRaw;
    private LsDataSrc frame0;
    private LsDataSrc frame1;

    @BeforeEach
    void setUp() {
        // 표시명 해석 헬퍼는 <b>실제 구현</b>(목 저장소 위)을 주입한다 — 이 테스트가 고정하는 것이
        // "검수자명은 DB 실측값"(D-ISSUE-41)이라 헬퍼를 목으로 바꾸면 그 계약이 검증에서 빠진다.
        // @InjectMocks 는 목이 없는 타입에 null 을 넣으므로 여기서 채운다.
        setField(taskQueryService, "userNameResolver",
                new kr.co.cudo.authoring.user.service.UserNameResolver(userRepository));

        sampleRaw = LsDataRaw.createFromIngest(
                "CLIP-001", "CCTV-001", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/clip.mp4",
                LocalDateTime.now(), 30);
        // rawSn is auto-generated, so we use reflection to set it for unit test
        setField(sampleRaw, "rawSn", 100L);

        frame0 = LsDataSrc.create(100L, 0, "/var/raw/frame_0.jpg", LocalDateTime.now());
        setField(frame0, "srcSn", 10L);
        frame1 = LsDataSrc.create(100L, 1, "/var/raw/frame_1.jpg", LocalDateTime.now());
        setField(frame1, "srcSn", 11L);
    }

    // ==================== getSummary ====================

    @Test
    @DisplayName("getSummary_정상_라벨_메타_카운트_정확")
    void getSummary_normal_countsCorrect() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        when(srcRepository.countByRawSn(100L)).thenReturn(2L);
        when(lblRepository.countLabeledFramesByRawSn(100L)).thenReturn(2L);
        when(lblRepository.countByRawSn(100L)).thenReturn(3L);
        when(metaRepository.countByRawSn(100L)).thenReturn(2L);
        seedApprover(7L, "검수자김");

        // when
        TaskSummaryResponse response = taskQueryService.getSummary(100L);

        // then
        assertThat(response.rawSn()).isEqualTo(100L);
        assertThat(response.totalFrames()).isEqualTo(2);
        assertThat(response.labeledFrames()).isEqualTo(2); // both frames have labels
        assertThat(response.totalLabels()).isEqualTo(3);
        assertThat(response.totalMeta()).isEqualTo(2);
        assertThat(response.status()).isEqualTo("PENDING"); // default status from createFromIngest
    }

    @Test
    @DisplayName("getSummary_프레임_없는_영상_0_반환")
    void getSummary_noFrames_returnsZeros() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        when(srcRepository.countByRawSn(100L)).thenReturn(0L);
        when(lblRepository.countLabeledFramesByRawSn(100L)).thenReturn(0L);
        when(lblRepository.countByRawSn(100L)).thenReturn(0L);
        when(metaRepository.countByRawSn(100L)).thenReturn(0L);

        // when
        TaskSummaryResponse response = taskQueryService.getSummary(100L);

        // then
        assertThat(response.totalFrames()).isZero();
        assertThat(response.labeledFrames()).isZero();
        assertThat(response.totalLabels()).isZero();
        assertThat(response.totalMeta()).isZero();
    }

    @Test
    @DisplayName("getSummary_는_프레임_라벨을_전량적재하지_않고_COUNT_집계만_사용한다")
    void getSummary_usesCountAggregatesOnly() {
        // given — CWE-770: 10만 프레임 영상에서도 힙에 엔티티를 적재하면 안 된다(D-ISSUE-45).
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        when(srcRepository.countByRawSn(100L)).thenReturn(100_000L);
        when(lblRepository.countLabeledFramesByRawSn(100L)).thenReturn(99_000L);
        when(lblRepository.countByRawSn(100L)).thenReturn(500_000L);
        when(metaRepository.countByRawSn(100L)).thenReturn(12L);

        // when
        TaskSummaryResponse response = taskQueryService.getSummary(100L);

        // then — 카운트는 DB 집계 결과 그대로, 전량 적재 조회는 호출되지 않는다.
        assertThat(response.totalFrames()).isEqualTo(100_000L);
        assertThat(response.totalLabels()).isEqualTo(500_000L);
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
        verify(lblRepository, never()).findBySrcSnIn(anyCollection());
        verify(metaRepository, never()).findByRawSn(anyLong());
    }

    @Test
    @DisplayName("getSummary_검수자명이_null_하드코딩이_아니라_DB_실측값이다")
    void getSummary_reviewerNameComesFromDb() {
        // given — 마지막 APPROVE 이벤트의 actor 를 사용자 마스터에서 조회한다(D-ISSUE-41).
        stubSummaryCounts();
        seedApprover(7L, "검수자김");

        // when
        TaskSummaryResponse response = taskQueryService.getSummary(100L);

        // then
        assertThat(response.reviewerName()).isEqualTo("검수자김");
    }

    @Test
    @DisplayName("getSummary_승인이력이_없으면_검수자명을_지어내지_않고_null")
    void getSummary_noApproveEvent_reviewerNameNull() {
        // given
        stubSummaryCounts();
        when(taskEventLogRepository.findFirstByRawDataIdAndEventTypeCdOrderByOcrnDtDescEventSeqDesc(
                100L, LsTaskEventLog.EVENT_APPROVE)).thenReturn(Optional.empty());

        // when
        TaskSummaryResponse response = taskQueryService.getSummary(100L);

        // then
        assertThat(response.reviewerName()).isNull();
    }

    @Test
    @DisplayName("getSummary_사용자_마스터에_없으면_검수자명_null")
    void getSummary_unknownUser_reviewerNameNull() {
        // given — 승인 이력은 있으나 사용자 행이 없다.
        stubSummaryCounts();
        when(taskEventLogRepository.findFirstByRawDataIdAndEventTypeCdOrderByOcrnDtDescEventSeqDesc(
                100L, LsTaskEventLog.EVENT_APPROVE))
                .thenReturn(Optional.of(LsTaskEventLog.approve(100L, 7L)));
        when(userRepository.findByUserNo(7L)).thenReturn(Optional.empty());

        // when
        TaskSummaryResponse response = taskQueryService.getSummary(100L);

        // then
        assertThat(response.reviewerName()).isNull();
    }

    @Test
    @DisplayName("getSummary_미존재_rawSn_NOT_FOUND_예외")
    void getSummary_notFound() {
        // given
        when(videoRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> taskQueryService.getSummary(999L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ==================== getLabels ====================

    @Test
    @DisplayName("getLabels_전체_프레임_라벨_반환")
    void getLabels_allFrames() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        stubFramePage(List.of(frame0, frame1), 2L);

        LsDataLbl lbl1 = createLabel(10L, "BBOX", "person", "[10,10,50,50]");
        setField(lbl1, "lblSn", 1L);
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(lbl1));

        // when
        Page<TaskLabelsResponse> result = taskQueryService.getLabels(100L, null, PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).hasSize(2); // 2 frames
        assertThat(result.getTotalElements()).isEqualTo(2);
        TaskLabelsResponse firstFrame = result.getContent().stream()
                .filter(r -> r.srcSn().equals(10L))
                .findFirst().orElseThrow();
        assertThat(firstFrame.labels()).hasSize(1);
        assertThat(firstFrame.labels().get(0).label()).isEqualTo("person");
    }

    @Test
    @DisplayName("getLabels_nested_POINT_CN_그대로_포워딩")
    void getLabels_nestedPointCn_forwardedAsIs() {
        // given -- 요구 R3: write-time 정규화 + V66 백필 후 DB 의 POINT_CN 은 항상 nested [[x,y],...].
        //          관제-facing raw-forwarder 는 파싱 없이 그대로 노출해야 한다(회귀 가드).
        String nestedPoints = "[[10,10],[50,50]]";
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        stubFilteredFramePage(List.of(frame0), 1L);

        LsDataLbl nestedLabel = createLabel(10L, "BBOX", "person", nestedPoints);
        setField(nestedLabel, "lblSn", 1L);
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of(nestedLabel));

        // when
        Page<TaskLabelsResponse> result =
                taskQueryService.getLabels(100L, List.of(10L), PageRequest.of(0, 20));

        // then -- nested 입력이 nested 그대로 노출 (flat 변환·파싱 없음)
        assertThat(result.getContent()).hasSize(1);
        TaskLabelsResponse.LabelItem item = result.getContent().get(0).labels().get(0);
        assertThat(item.points()).isEqualTo(nestedPoints);
    }

    @Test
    @DisplayName("getLabels_frameIds_필터_적용")
    void getLabels_filteredByFrameIds() {
        // given — 필터는 리포지토리 조건으로 내려간다(페이징 前 적용, B-2).
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        stubFilteredFramePage(List.of(frame0), 1L);
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());

        // when -- filter to only frame with srcSn=10
        Page<TaskLabelsResponse> result =
                taskQueryService.getLabels(100L, List.of(10L), PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).srcSn()).isEqualTo(10L);
        // 필터가 걸리면 전체 페이지 조회는 사용되지 않는다(메모리 필터링 회귀 방지).
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(eq(100L), any(Pageable.class));
    }

    @Test
    @DisplayName("frameIds_로_지정한_프레임이_첫_페이지_밖에_있어도_조회된다")
    void getLabels_frameIdOutsideFirstPageIsStillReturned() {
        // given — 프레임 500건 영상에서 page 7 에 있는 프레임을 page=0 으로 요청한다.
        //         구 구현은 page 0(0~19)만 조회 후 메모리 필터라 결과가 항상 비었다(B-2).
        LsDataSrc farFrame = LsDataSrc.create(100L, 140, "/var/raw/frame_140.jpg", LocalDateTime.now());
        setField(farFrame, "srcSn", 777L);
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        stubFilteredFramePage(List.of(farFrame), 1L);
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());

        // when
        Page<TaskLabelsResponse> result =
                taskQueryService.getLabels(100L, List.of(777L), PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).extracting(TaskLabelsResponse::srcSn).containsExactly(777L);
    }

    @Test
    @DisplayName("frameIds_필터_적용시_totalElements_가_필터_결과를_반영한다")
    void getLabels_totalElementsReflectsFilter() {
        // given — 필터 전 전체는 500건이지만 필터 결과는 1건이다.
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        stubFilteredFramePage(List.of(frame0), 1L);
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());

        // when
        Page<TaskLabelsResponse> result =
                taskQueryService.getLabels(100L, List.of(10L), PageRequest.of(0, 20));

        // then — 내용 1건인데 totalElements 500 같은 모순이 나오면 안 된다.
        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getTotalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("frameIds_가_100개를_초과하면_INVALID_INPUT")
    void getLabels_tooManyFrameIdsRejected() {
        // given — 컨트롤러 @Size(max=100) 에만 의존하지 않고 서비스에서도 방어한다(CWE-770).
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, 101).boxed().toList();

        // when / then
        assertThatThrownBy(() -> taskQueryService.getLabels(100L, tooMany, PageRequest.of(0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("getLabels_빈_frameIds_전체_반환")
    void getLabels_emptyFrameIds_returnsAll() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        stubFramePage(List.of(frame0, frame1), 2L);
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());

        // when
        Page<TaskLabelsResponse> result =
                taskQueryService.getLabels(100L, List.of(), PageRequest.of(0, 20));

        // then
        assertThat(result.getContent()).hasSize(2); // empty list = no filter = all frames
    }

    @Test
    @DisplayName("getLabels_요청_size_10000_이어도_100_으로_클램프된다")
    void getLabels_oversizedPageIsClamped() {
        // given — CWE-770: 관제(혹은 임의 호출자)가 size=10000 을 보내도 전건 적재를 허용하지 않는다.
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        stubFramePage(List.of(frame0, frame1), 2L);
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());

        // when
        Page<TaskLabelsResponse> result =
                taskQueryService.getLabels(100L, null, PageRequest.of(0, 10_000));

        // then — 리포지토리에 실제로 넘어간 Pageable 이 상한으로 잘려야 한다(응답만 잘라선 무의미).
        Pageable used = capturePageable();
        assertThat(used.getPageSize()).isEqualTo(TaskQueryService.MAX_PAGE_SIZE);
        assertThat(used.getPageNumber()).isZero();
        assertThat(result.getSize()).isEqualTo(TaskQueryService.MAX_PAGE_SIZE);
    }

    @Test
    @DisplayName("getLabels_상한_이하_size_는_요청값과_페이지번호가_보존된다")
    void getLabels_withinLimitPageIsPreserved() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        stubFramePage(List.of(frame0, frame1), 500L);
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());

        // when
        Page<TaskLabelsResponse> result =
                taskQueryService.getLabels(100L, null, PageRequest.of(3, 50));

        // then
        Pageable used = capturePageable();
        assertThat(used.getPageSize()).isEqualTo(50);
        assertThat(used.getPageNumber()).isEqualTo(3);
        assertThat(result.getTotalElements()).isEqualTo(500);
    }

    @Test
    @DisplayName("getLabels_페이징_미지정이면_기본_20건_frameNo_오름차순이_적용된다")
    void getLabels_unpagedFallsBackToDefaultPage() {
        // given — Pageable 이 없거나 unpaged 여도 전건 조회로 흘러선 안 된다(CWE-770).
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        stubFramePage(List.of(frame0, frame1), 2L);
        when(lblRepository.findBySrcSnIn(anyCollection())).thenReturn(List.of());

        // when
        taskQueryService.getLabels(100L, null, Pageable.unpaged());

        // then
        Pageable used = capturePageable();
        assertThat(used.isPaged()).isTrue();
        assertThat(used.getPageSize()).isEqualTo(20);
        assertThat(used.getSort().getOrderFor("frameNo"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("getLabels_미존재_rawSn_NOT_FOUND_예외")
    void getLabels_notFound() {
        // given
        when(videoRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> taskQueryService.getLabels(999L, null, PageRequest.of(0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ==================== getMeta ====================

    @Test
    @DisplayName("getMeta_정상_메타_목록_반환")
    void getMeta_normal() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        LsDataMeta meta1 = LsDataMeta.create(100L, "weather", "sunny");
        setField(meta1, "metaSn", 1L);
        LsDataMeta meta2 = LsDataMeta.create(100L, "time", "morning");
        setField(meta2, "metaSn", 2L);
        stubMetaPage(List.of(meta1, meta2), 2L);

        // when
        TaskMetaResponse response = taskQueryService.getMeta(100L, PageRequest.of(0, 20));

        // then
        assertThat(response.rawSn()).isEqualTo(100L);
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).metaKey()).isEqualTo("weather");
        assertThat(response.items().get(0).metaVal()).isEqualTo("sunny");
    }

    @Test
    @DisplayName("getMeta_메타_없는_영상_빈_목록")
    void getMeta_noMeta_emptyList() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        stubMetaPage(List.of(), 0L);

        // when
        TaskMetaResponse response = taskQueryService.getMeta(100L, PageRequest.of(0, 20));

        // then
        assertThat(response.rawSn()).isEqualTo(100L);
        assertThat(response.items()).isEmpty();
    }

    @Test
    @DisplayName("getMeta_미존재_rawSn_NOT_FOUND_예외")
    void getMeta_notFound() {
        // given
        when(videoRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> taskQueryService.getMeta(999L, PageRequest.of(0, 20)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("메타_조회가_페이징_없이_전건을_반환하지_않는다")
    void getMeta_isPagedAndClamped() {
        // given — VLM 콜백 누적 시 메타가 수천 건이 될 수 있다(META_VL 최대 2000자, CWE-770).
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        LsDataMeta meta = LsDataMeta.create(100L, "weather", "sunny");
        setField(meta, "metaSn", 1L);
        stubMetaPage(List.of(meta), 5_000L);

        // when — 상한을 넘는 size 요청
        TaskMetaResponse response = taskQueryService.getMeta(100L, PageRequest.of(0, 10_000));

        // then — 전건 조회(findByRawSn(rawSn)) 는 호출되지 않고, 리포지토리에 넘어간 size 가 클램프된다.
        verify(metaRepository, never()).findByRawSn(anyLong());
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(metaRepository).findByRawSn(eq(100L), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(TaskQueryService.MAX_PAGE_SIZE);
        assertThat(response.totalElements()).isEqualTo(5_000L);
        assertThat(response.items()).hasSize(1);
    }

    @Test
    @DisplayName("메타_페이징_미지정이면_기본_20건이_적용된다")
    void getMeta_unpagedFallsBackToDefaultPage() {
        // given
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        stubMetaPage(List.of(), 0L);

        // when
        taskQueryService.getMeta(100L, Pageable.unpaged());

        // then
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(metaRepository).findByRawSn(eq(100L), captor.capture());
        assertThat(captor.getValue().isPaged()).isTrue();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }

    // ==================== Helpers ====================

    /** frameIds 필터가 걸린 조회 stub — 필터는 리포지토리 조건으로 내려간다(B-2). */
    private void stubFilteredFramePage(List<LsDataSrc> frames, long total) {
        when(srcRepository.findByRawSnAndSrcSnInOrderByFrameNoAsc(
                eq(100L), anyCollection(), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(frames, inv.getArgument(2), total));
    }

    /** 메타 페이지 stub. */
    private void stubMetaPage(List<LsDataMeta> metas, long total) {
        when(metaRepository.findByRawSn(eq(100L), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(metas, inv.getArgument(1), total));
    }

    /** getLabels 프레임 페이지 stub — 리포지토리가 받은 Pageable 을 그대로 되돌려주지 않고 총건수를 지정한다. */
    private void stubFramePage(List<LsDataSrc> frames, long total) {
        when(srcRepository.findByRawSnOrderByFrameNoAsc(eq(100L), any(Pageable.class)))
                .thenAnswer(inv -> new PageImpl<>(frames, inv.getArgument(1), total));
    }

    /** 리포지토리에 실제로 전달된 Pageable 캡처 — 클램프가 조회 자체에 적용됐는지 확인용. */
    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(srcRepository).findByRawSnOrderByFrameNoAsc(eq(100L), captor.capture());
        return captor.getValue();
    }

    private void stubSummaryCounts() {
        when(videoRepository.findById(100L)).thenReturn(Optional.of(sampleRaw));
        when(srcRepository.countByRawSn(100L)).thenReturn(2L);
        when(lblRepository.countLabeledFramesByRawSn(100L)).thenReturn(1L);
        when(lblRepository.countByRawSn(100L)).thenReturn(3L);
        when(metaRepository.countByRawSn(100L)).thenReturn(2L);
    }

    private void seedApprover(Long userNo, String userNm) {
        when(taskEventLogRepository.findFirstByRawDataIdAndEventTypeCdOrderByOcrnDtDescEventSeqDesc(
                100L, LsTaskEventLog.EVENT_APPROVE))
                .thenReturn(Optional.of(LsTaskEventLog.approve(100L, userNo)));
        LsAcntUser user = newInstance(LsAcntUser.class);
        setField(user, "userNo", userNo);
        setField(user, "userNm", userNm);
        when(userRepository.findByUserNo(userNo)).thenReturn(Optional.of(user));
    }

    private LsDataLbl createLabel(Long srcSn, String type, String label, String points) {
        return LsDataLbl.createManual(srcSn, type, null, label, points, 1L);
    }

    private static <T> T newInstance(Class<T> type) {
        try {
            var ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate: " + type.getName(), e);
        }
    }

    /**
     * 테스트용 리플렉션 필드 설정 — @Id @GeneratedValue 필드에 값 주입.
     */
    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
