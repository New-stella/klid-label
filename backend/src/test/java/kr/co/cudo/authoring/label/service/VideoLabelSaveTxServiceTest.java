package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveRequest;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveResponse;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * API-196 — 영상 라벨 <b>일괄 확정 저장</b> 단위 테스트.
 *
 * <p>고정하는 계약:
 * <ul>
 *   <li>저장 규칙을 재구현하지 않는다 — 프레임마다 {@code LabelService.applyFrameSave} <b>같은 코어</b>를
 *       호출하고 {@code lblVer}·{@code dscdYn}·{@code items} 를 그대로 전달한다.</li>
 *   <li>{@code labelId} 를 보존해 전달한다 — 잃으면 재조회 시 라벨 마스터 조인이 끊긴다.</li>
 *   <li>순회 순서는 요청 순서가 아니라 <b>{@code FRAME_NO} 오름차순</b>(ABBA 교착 방지).</li>
 *   <li>그 영상 소속이 아닌 프레임은 <b>404</b>(IDOR — CWE-639), 같은 프레임 중복은 <b>400</b>.</li>
 *   <li>게이트 순서 — <b>신고(412)가 작업락(409)보다 먼저</b>(CWE-209).</li>
 *   <li>{@code loadedVersion} 이 있을 때만 "어느 회차에서 시작했는가" 감사를 남긴다(CWE-778).</li>
 *   <li><b>영상 전 프레임 락을 {@code SRC_SN} 축으로 먼저 선점</b>한 뒤 프레임별 저장으로 들어간다 —
 *       {@code FRM_NO} 순서 개별 잠금은 기존 3경로와 40P01 을 만든다.</li>
 *   <li><b>좌표 경계 기준값은 트랜잭션 밖에서 받은 값을 그대로 쓴다</b> — 코어가 트랜잭션 안에서
 *       이미지를 디코딩하지 않게 한다.</li>
 * </ul>
 *
 * <p>판번호 불일치 409·전체 롤백은 코어({@code LabelService.applyFrameSave} → {@code requireLabelVersionMatch})
 * 가 소유하므로 여기서는 <b>전달과 전파</b>만 검증한다(같은 판정을 두 곳에서 재구현하지 않았다는 뜻).
 *
 * @design API-196
 * @req R6
 */
@ExtendWith(MockitoExtension.class)
class VideoLabelSaveTxServiceTest {

    private static final Long RAW_SN = 9L;
    private static final Long SRC_A = 51L;
    private static final Long SRC_B = 52L;

    @Mock private LabelAccessGuard accessGuard;
    @Mock private VideoRepository videoRepository;
    @Mock private WorkLockService workLockService;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LabelService labelService;
    @Mock private LsTaskEventLogRepository taskEventLogRepository;

    private VideoLabelSaveTxService service;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        service = new VideoLabelSaveTxService(accessGuard, videoRepository, workLockService,
                srcRepository, labelService, taskEventLogRepository);
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    // ---------- 인가·게이트 ----------

    @Test
    @DisplayName("본인_배정이_아닌_영상_확정저장_시_403")
    void 본인_배정이_아닌_영상_확정저장_시_403() {
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."))
                .when(accessGuard).verifyRawAccess(eq(RAW_SN), any());

        assertThatThrownBy(() -> service.saveInTx(RAW_SN, request(frameReq(SRC_A, 1L)), reviewer, Map.of()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        verify(labelService, never()).applyFrameSave(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("비식별_신고_구간이면_확정저장도_412")
    void 비식별_신고_구간이면_확정저장도_412() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED, "비식별 재처리 대기 중인 영상입니다."))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);

        assertThatThrownBy(() -> service.saveInTx(RAW_SN, request(frameReq(SRC_A, 1L)), reviewer, Map.of()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRECONDITION_FAILED);
        // ★ 신고 구간의 응답은 락 유무와 무관해야 한다 — 락을 조회조차 하지 않는다(CWE-209).
        verify(workLockService, never()).isRawLocked(anyLong());
        verify(labelService, never()).applyFrameSave(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("작업락이_걸린_영상_확정저장_시_409")
    void 작업락이_걸린_영상_확정저장_시_409() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(true);

        assertThatThrownBy(() -> service.saveInTx(RAW_SN, request(frameReq(SRC_A, 1L)), reviewer, Map.of()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        verify(labelService, never()).applyFrameSave(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("그_영상에_속하지_않는_프레임은_404 — 남의_프레임을_끼워_저장할_수_없다")
    void 그_영상에_속하지_않는_프레임은_404() {
        stubGatesOpen();
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));

        assertThatThrownBy(() -> service.saveInTx(RAW_SN, request(frameReq(999L, 1L)), reviewer, Map.of()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(labelService, never()).applyFrameSave(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("같은_프레임이_두_번_실려_있으면_400")
    void 같은_프레임이_두_번_실려있으면_400() {
        stubGatesOpen();

        assertThatThrownBy(() -> service.saveInTx(RAW_SN,
                request(frameReq(SRC_A, 1L), frameReq(SRC_A, 2L)), reviewer, Map.of()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    // ---------- 저장 위임 ----------

    @Test
    @DisplayName("API_196_은_labelId_를_보존해_저장한다")
    void API_196_은_labelId_를_보존해_저장한다() {
        stubGatesOpen();
        stubActor();
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        stubCoreSaves(5L);

        service.saveInTx(RAW_SN, request(frameReq(SRC_A, 4L)), reviewer, Map.of());

        ArgumentCaptor<LabelBulkUpsertRequest> captor =
                ArgumentCaptor.forClass(LabelBulkUpsertRequest.class);
        verify(labelService).applyFrameSave(any(), any(), captor.capture(), eq(1L), any());
        LabelBulkUpsertRequest forwarded = captor.getValue();
        // ★ labelId 가 빠지면 재조회 시 마스터 조인이 끊겨 색상·라벨명·속성 정의가 함께 사라진다.
        assertThat(forwarded.items()).singleElement()
                .satisfies(item -> {
                    assertThat(item.labelId()).isEqualTo(12L);
                    assertThat(item.id()).isEqualTo(9001L);
                });
        // 판번호는 그대로 코어의 CAS 입력으로 전달된다(여기서 재검증하지 않는다).
        assertThat(forwarded.labelVersion()).isEqualTo(4L);
    }

    @Test
    @DisplayName("API_196_은_폐기상태를_함께_확정한다")
    void API_196_은_폐기상태를_함께_확정한다() {
        stubGatesOpen();
        stubActor();
        LsDataSrc a = frame(SRC_A, 0);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(a));
        stubCoreSaves(2L);
        // 코어가 폐기 전이를 반영하면 응답도 그 값을 따른다(응답이 확정 상태를 그대로 보여줘야 한다).
        when(labelService.applyFrameSave(any(), any(), any(), any(), any())).thenAnswer(inv -> {
            a.discard();
            return new LabelService.FrameSaveOutcome(List.of(), 2L,
                    FrameDiscardApplier.Outcome.DISCARDED, false);
        });

        VideoLabelSaveResponse res = service.saveInTx(RAW_SN,
                new VideoLabelSaveRequest(List.of(new VideoLabelSaveRequest.Frame(
                        SRC_A, 1L, List.of(), "Y")), null), reviewer, Map.of());

        ArgumentCaptor<LabelBulkUpsertRequest> captor =
                ArgumentCaptor.forClass(LabelBulkUpsertRequest.class);
        verify(labelService).applyFrameSave(any(), any(), captor.capture(), any(), any());
        assertThat(captor.getValue().dscdYn()).isEqualTo("Y");
        assertThat(res.discardedFrameCount()).isEqualTo(1);
        assertThat(res.frames()).singleElement()
                .satisfies(f -> assertThat(f.dscdYn()).isEqualTo("Y"));
    }

    @Test
    @DisplayName("폐기여부를_보내지_않으면_현재_값을_유지한다 — 필드를_지어내지_않는다")
    void 폐기여부를_보내지_않으면_현재_값을_유지한다() {
        stubGatesOpen();
        stubActor();
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        stubCoreSaves(1L);

        service.saveInTx(RAW_SN, request(frameReq(SRC_A, 1L)), reviewer, Map.of());

        ArgumentCaptor<LabelBulkUpsertRequest> captor =
                ArgumentCaptor.forClass(LabelBulkUpsertRequest.class);
        verify(labelService).applyFrameSave(any(), any(), captor.capture(), any(), any());
        assertThat(captor.getValue().dscdYn()).isNull();
    }

    @Test
    @DisplayName("요청_순서가_아니라_프레임번호_오름차순으로_저장한다 — ABBA_교착_방지")
    void 요청_순서가_아니라_프레임번호_오름차순으로_저장한다() {
        stubGatesOpen();
        stubActor();
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_B, 1)));
        stubCoreSaves(1L);

        // 요청은 역순(프레임 1 → 0)으로 들어온다.
        service.saveInTx(RAW_SN, request(frameReq(SRC_B, 1L), frameReq(SRC_A, 1L)), reviewer, Map.of());

        ArgumentCaptor<LsDataSrc> frames = ArgumentCaptor.forClass(LsDataSrc.class);
        verify(labelService, org.mockito.Mockito.times(2))
                .applyFrameSave(any(), frames.capture(), any(), any(), any());
        assertThat(frames.getAllValues()).extracting(LsDataSrc::getSrcSn)
                .containsExactly(SRC_A, SRC_B);
    }

    @Test
    @DisplayName("판번호가_어긋나면_코어의_409_가_그대로_전파된다 — 부분_저장이_없다")
    void 판번호가_어긋나면_409_가_전파된다() {
        stubGatesOpen();
        stubActor();
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_B, 1)));
        when(labelService.applyFrameSave(any(), any(), any(), any(), any()))
                .thenThrow(new CustomException(ErrorCode.CONFLICT, "다른 사용자가 먼저 저장했습니다."));

        assertThatThrownBy(() -> service.saveInTx(RAW_SN,
                request(frameReq(SRC_A, 1L), frameReq(SRC_B, 1L)), reviewer, Map.of()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        // 감사도 남기지 않는다 — 확정되지 않은 저장이다.
        verify(taskEventLogRepository, never()).save(any());
    }

    // ---------- 감사 ----------

    @Test
    @DisplayName("불러온_회차가_있으면_어느_회차에서_시작했는지_감사로_남긴다")
    void 불러온_회차가_있으면_감사로_남긴다() {
        stubGatesOpen();
        stubActor();
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        stubCoreSaves(1L);

        service.saveInTx(RAW_SN, new VideoLabelSaveRequest(List.of(frameReq(SRC_A, 1L)), "3"), reviewer, Map.of());

        ArgumentCaptor<LsTaskEventLog> captor = ArgumentCaptor.forClass(LsTaskEventLog.class);
        verify(taskEventLogRepository).save(captor.capture());
        assertThat(captor.getValue().getEventTypeCd())
                .isEqualTo(LsTaskEventLog.EVENT_START_VERSION_APPLY);
        // RSN 에는 회차 번호 한 토큰만 싣는다(자유 문구·본문 금지 — CWE-359/117).
        assertThat(captor.getValue().getRsn())
                .isEqualTo(LsTaskEventLog.RSN_START_VERSION_PREFIX + "3");
    }

    @Test
    @DisplayName("불러오기를_거치지_않은_평상시_저장은_시작회차_감사를_남기지_않는다")
    void 평상시_저장은_시작회차_감사를_남기지_않는다() {
        stubGatesOpen();
        stubActor();
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        stubCoreSaves(1L);

        service.saveInTx(RAW_SN, request(frameReq(SRC_A, 1L)), reviewer, Map.of());

        verify(taskEventLogRepository, never()).save(any());
    }

    // ---------- 잠금 규약 (항목 ③) ----------

    @Test
    @DisplayName("영상_전_프레임_락을_먼저_선점한_뒤_프레임별_저장으로_들어간다 — ABBA_교착_방지")
    void 영상_전_프레임_락을_먼저_선점한다() {
        stubGatesOpen();
        stubActor();
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_B, 1)));
        stubCoreSaves(1L);

        service.saveInTx(RAW_SN, request(frameReq(SRC_A, 1L), frameReq(SRC_B, 1L)), reviewer, Map.of());

        // ★ SRC_SN 축 단일 문장 선점(lockFramesByRawSn)이 <b>프레임별 저장보다 먼저</b> 와야 한다.
        //   FRM_NO 순서로 프레임마다 개별 락을 잡으면 TrackEdit/TrackMerge/TrackInterpolation(SRC_SN 축)과
        //   순환 대기 → 40P01 → 500 으로 영상 전체 저장이 롤백된다.
        InOrder order = inOrder(srcRepository, labelService);
        order.verify(srcRepository).lockFramesByRawSn(RAW_SN);
        order.verify(labelService, org.mockito.Mockito.atLeastOnce())
                .applyFrameSave(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("게이트에_막힌_요청은_전_프레임_락을_잡지_않는다 — 거부될_요청이_정상_저장을_대기시키지_않는다")
    void 게이트에_막힌_요청은_락을_잡지_않는다() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED, "비식별 재처리 대기 중인 영상입니다."))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);

        assertThatThrownBy(() -> service.saveInTx(RAW_SN, request(frameReq(SRC_A, 1L)), reviewer, Map.of()))
                .isInstanceOf(CustomException.class);
        verify(srcRepository, never()).lockFramesByRawSn(anyLong());
    }

    // ---------- 좌표 경계 기준값 (항목 ④) ----------

    @Test
    @DisplayName("좌표_경계_기준값은_트랜잭션_밖에서_받은_값을_그대로_코어에_넘긴다 — 트랜잭션_안_이미지_디코딩_금지")
    void 좌표_경계_기준값을_그대로_코어에_넘긴다() {
        stubGatesOpen();
        stubActor();
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        stubCoreSaves(1L);

        service.saveInTx(RAW_SN, request(frameReq(SRC_A, 1L)), reviewer,
                Map.of(SRC_A, new int[] {1920, 1080}));

        ArgumentCaptor<LabelService.FrameSaveOptions> opts =
                ArgumentCaptor.forClass(LabelService.FrameSaveOptions.class);
        verify(labelService).applyFrameSave(any(), any(), any(), any(), opts.capture());
        // boundsResolved=true 여야 코어가 FrameBoundsResolver(=ImageIO 디코딩)를 호출하지 않는다.
        assertThat(opts.getValue().boundsResolved()).isTrue();
        assertThat(opts.getValue().preResolvedBounds()).containsExactly(1920, 1080);
    }

    @Test
    @DisplayName("측정_불가_프레임도_재해석을_요청하지_않는다 — null_을_미시도로_오해하면_트랜잭션_안에서_다시_파일을_연다")
    void 측정_불가_프레임도_재해석하지_않는다() {
        stubGatesOpen();
        stubActor();
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        stubCoreSaves(1L);

        // 워밍이 측정에 실패한 프레임 — 값은 null 이지만 "이미 시도했다"는 사실이 전달돼야 한다.
        java.util.Map<Long, int[]> warmed = new java.util.HashMap<>();
        warmed.put(SRC_A, null);
        service.saveInTx(RAW_SN, request(frameReq(SRC_A, 1L)), reviewer, warmed);

        ArgumentCaptor<LabelService.FrameSaveOptions> opts =
                ArgumentCaptor.forClass(LabelService.FrameSaveOptions.class);
        verify(labelService).applyFrameSave(any(), any(), any(), any(), opts.capture());
        assertThat(opts.getValue().boundsResolved()).isTrue();
        assertThat(opts.getValue().preResolvedBounds()).isNull();
    }

    // ---------- 고정 스텁 ----------

    private void stubGatesOpen() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
    }

    private void stubActor() {
        when(accessGuard.parseUserNo("1")).thenReturn(1L);
    }

    private void stubCoreSaves(long newVersion) {
        when(labelService.applyFrameSave(any(), any(), any(), any(), any())).thenReturn(
                new LabelService.FrameSaveOutcome(List.of(), newVersion,
                        FrameDiscardApplier.Outcome.UNCHANGED, true));
    }

    private VideoLabelSaveRequest request(VideoLabelSaveRequest.Frame... frames) {
        return new VideoLabelSaveRequest(List.of(frames), null);
    }

    private VideoLabelSaveRequest.Frame frameReq(Long srcSn, Long lblVer) {
        return new VideoLabelSaveRequest.Frame(srcSn, lblVer, List.of(new LabelItemDto(
                9001L, "BBOX", 12L, "사람", List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)), null)), null);
    }

    private LsDataSrc frame(Long srcSn, int frameNo) {
        LsDataSrc src = LsDataSrc.create(RAW_SN, frameNo, "/raw/" + frameNo + ".jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", srcSn);
        return src;
    }

    private LsDataRaw raw() {
        LsDataRaw raw = LsDataRaw.createFromIngest("CLIP-SV", "CCTV", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/clip.mp4", LocalDateTime.now(), 30);
        ReflectionTestUtils.setField(raw, "rawSn", RAW_SN);
        return raw;
    }
}
