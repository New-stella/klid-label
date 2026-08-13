package kr.co.cudo.authoring.label.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelBulkUpsertRequest;
import kr.co.cudo.authoring.label.dto.LabelItemDto;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveRequest;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveResponse;
import kr.co.cudo.authoring.version.service.VersionSnapshotReader;
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

import java.math.BigDecimal;
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
 * API-196 (v4) — 영상 라벨 <b>일괄 확정 저장</b> 쓰기 트랜잭션 단위 테스트.
 *
 * <p>고정하는 계약:
 * <ul>
 *   <li><b>회차 스냅샷을 읽어 전 프레임에 적용</b>하고 {@code edits} 에 온 프레임만 덮는다.</li>
 *   <li>회차 스냅샷의 <b>생산이력·추적 식별자가 복원</b>된다(요청이 주장하는 값이 아니다).</li>
 *   <li>{@code frameVersions} 가 <b>전 프레임을 덮지 않으면 400</b>(폐기 프레임도 포함).</li>
 *   <li>그 영상에 <b>없는 회차이면 400</b> — 감사에 검증되지 않은 값을 남기지 않는다.</li>
 *   <li>★<b>버전 축을 건드리지 않는다</b> — 회차 기록·활성 표식을 바꾸지 않는다(사용자 확정 원칙).</li>
 *   <li>저장 규칙을 재구현하지 않는다 — 프레임마다 {@code LabelService.applyFrameSave} 같은 코어.</li>
 *   <li>영상 전 프레임 락을 {@code SRC_SN} 축으로 <b>먼저 선점</b>한다(ABBA 교착 방지).</li>
 *   <li>좌표 경계 기준값은 <b>트랜잭션 밖에서</b> 받은 값을 그대로 쓴다.</li>
 * </ul>
 *
 * @design API-196
 * @req R6
 */
@ExtendWith(MockitoExtension.class)
class VideoLabelSaveTxServiceTest {

    private static final Long RAW_SN = 9L;
    private static final Long SRC_A = 51L;
    private static final Long SRC_B = 52L;
    private static final int VERSION = 3;

    @Mock private LabelAccessGuard accessGuard;
    @Mock private VideoRepository videoRepository;
    @Mock private WorkLockService workLockService;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LabelService labelService;
    @Mock private LsTaskEventLogRepository taskEventLogRepository;
    @Mock private VersionSnapshotReader snapshotReader;
    @Mock private LsDataLblRepository labelRepository;

    private VideoLabelSaveTxService service;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        service = new VideoLabelSaveTxService(accessGuard, videoRepository, workLockService,
                srcRepository, labelService, taskEventLogRepository, snapshotReader,
                labelRepository, new ObjectMapper());
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    // ---------- 인가·게이트 ----------

    @Test
    @DisplayName("본인_배정이_아닌_영상_확정저장_시_403")
    void 본인_배정이_아닌_영상_확정저장_시_403() {
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."))
                .when(accessGuard).verifyRawAccess(eq(RAW_SN), any());

        assertThatThrownBy(() -> save(request(VERSION, frameVer(SRC_A, 1L))))
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

        assertThatThrownBy(() -> save(request(VERSION, frameVer(SRC_A, 1L))))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRECONDITION_FAILED);
        // ★ 신고 구간의 응답은 락 유무와 무관해야 한다 — 락을 조회조차 하지 않는다(CWE-209).
        verify(workLockService, never()).isRawLocked(anyLong());
    }

    @Test
    @DisplayName("작업락이_걸린_영상_확정저장_시_409")
    void 작업락이_걸린_영상_확정저장_시_409() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(true);

        assertThatThrownBy(() -> save(request(VERSION, frameVer(SRC_A, 1L))))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("그_영상에_없는_회차이면_400")
    void 그_영상에_없는_회차이면_400() {
        stubGatesOpen();
        when(snapshotReader.versionExists(RAW_SN, VERSION)).thenReturn(false);

        assertThatThrownBy(() -> save(request(VERSION, frameVer(SRC_A, 1L))))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        // 검증되지 않은 클라이언트 주장을 감사로 남기지 않는다.
        verify(taskEventLogRepository, never()).save(any());
        verify(srcRepository, never()).lockFramesByRawSn(anyLong());
    }

    // ---------- 커버리지 강제 ----------

    @Test
    @DisplayName("프레임_판번호가_전_프레임을_덮지_않으면_400")
    void 프레임_판번호가_전_프레임을_덮지_않으면_400() {
        stubGatesOpen();
        stubVersionExists();
        // 영상은 2장인데 1장만 보냈다 — 일부만 확정하면 회차가 섞인 영상이 외부로 나간다.
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(2L);

        assertThatThrownBy(() -> save(request(VERSION, frameVer(SRC_A, 1L))))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        // ★ 커버리지는 <엔티티 로드 이전>에 count 로 판정한다(CWE-770).
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
        verify(srcRepository, never()).lockFramesByRawSn(anyLong());
    }

    @Test
    @DisplayName("폐기된_프레임도_전_프레임_판정에_포함된다")
    void 폐기된_프레임도_전_프레임_판정에_포함된다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        LsDataSrc discardedFrame = frame(SRC_B, 1);
        discardedFrame.discard();
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(2L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), discardedFrame));
        stubSnapshot(SRC_A, "N");
        stubSnapshot(SRC_B, "Y");
        stubCoreSaves(1L);

        // 폐기 프레임을 포함해 2장을 보내면 통과한다(불러오기가 전 프레임을 돌려주므로 왕복이 성립).
        VideoLabelSaveResponse res = save(request(VERSION, frameVer(SRC_A, 1L), frameVer(SRC_B, 1L)));

        assertThat(res.savedFrameCount()).isEqualTo(2);
        assertThat(res.discardedFrameCount()).isEqualTo(1);
    }

    // ---------- 회차 스냅샷 적용 · 복원 ----------

    @Test
    @DisplayName("회차_스냅샷의_자동라벨_이력과_추적_식별자가_복원된다")
    void 회차_스냅샷의_자동라벨_이력과_추적_식별자가_복원된다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        stubSingleFrame();
        // 스냅샷의 라벨은 AI 가 붙인 것이고 트랙에 묶여 있다.
        stubSnapshot(SRC_A, "N", autoItem(9001L, 12L, "7", "AUTO_YOLO", "0.87"));
        stubCoreSaves(1L);

        save(request(VERSION, frameVer(SRC_A, 1L)));

        ArgumentCaptor<LabelService.FrameSaveOptions> opts =
                ArgumentCaptor.forClass(LabelService.FrameSaveOptions.class);
        verify(labelService).applyFrameSave(any(), any(), any(), any(), opts.capture());
        // ★ 복원 힌트의 출처는 <서버가 읽은 스냅샷>이다 — 요청이 주장하는 값이 아니다(CWE-915).
        LabelService.RestoreHint hint = opts.getValue().hintFor(9001L);
        assertThat(hint).isNotNull();
        assertThat(hint.autoLblYn()).isEqualTo("Y");
        assertThat(hint.confScore()).isEqualByComparingTo("0.87");
        assertThat(hint.lblSrcCd()).isEqualTo("AUTO_YOLO");
        assertThat(hint.trackId()).isEqualTo("7");
    }

    @Test
    @DisplayName("edits_가_없으면_회차_스냅샷_본문이_그대로_확정된다")
    void edits_가_없으면_스냅샷_본문이_확정된다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        stubSingleFrame();
        stubSnapshot(SRC_A, "Y", item(9001L, 12L, "7"));
        stubCoreSaves(1L);

        save(request(VERSION, frameVer(SRC_A, 4L)));

        LabelBulkUpsertRequest forwarded = capturedRequest();
        assertThat(forwarded.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(9001L);
            // ★ labelId 를 잃으면 재조회 시 마스터 조인이 끊긴다.
            assertThat(item.labelId()).isEqualTo(12L);
            assertThat(item.trackId()).isEqualTo("7");
        });
        // 폐기 여부도 스냅샷 값을 따른다. 판번호는 요청 값이 CAS 입력으로 전달된다.
        assertThat(forwarded.dscdYn()).isEqualTo("Y");
        assertThat(forwarded.labelVersion()).isEqualTo(4L);
    }

    @Test
    @DisplayName("사람이_고친_프레임은_회차_값을_덮는다")
    void 사람이_고친_프레임은_회차_값을_덮는다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        stubSingleFrame();
        stubSnapshot(SRC_A, "Y", item(9001L, 12L, "7"));
        stubCoreSaves(1L);

        VideoLabelSaveRequest req = new VideoLabelSaveRequest(VERSION,
                List.of(frameVer(SRC_A, 1L)),
                List.of(new VideoLabelSaveRequest.FrameEdit(SRC_A,
                        List.of(editItem(null, 33L, null)), "N")));
        service.saveInTx(RAW_SN, req, reviewer, Map.of());

        LabelBulkUpsertRequest forwarded = capturedRequest();
        // 그 프레임에서는 사람이 보낸 것이 기준이다(스냅샷 라벨 9001 이 아니라 새 라벨).
        assertThat(forwarded.items()).singleElement()
                .satisfies(item -> assertThat(item.labelId()).isEqualTo(33L));
        assertThat(forwarded.dscdYn()).isEqualTo("N");
    }

    @Test
    @DisplayName("고친_프레임에서도_손대지_않은_라벨은_회차_생산이력으로_복원된다")
    void 고친_프레임에서도_복원_힌트가_전달된다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        stubSingleFrame();
        stubSnapshot(SRC_A, "N", autoItem(9001L, 12L, "7", "AUTO_SAM2", "0.5"));
        stubCoreSaves(1L);

        VideoLabelSaveRequest req = new VideoLabelSaveRequest(VERSION,
                List.of(frameVer(SRC_A, 1L)),
                List.of(new VideoLabelSaveRequest.FrameEdit(SRC_A,
                        List.of(editItem(9001L, 12L, null)), null)));
        service.saveInTx(RAW_SN, req, reviewer, Map.of());

        ArgumentCaptor<LabelService.FrameSaveOptions> opts =
                ArgumentCaptor.forClass(LabelService.FrameSaveOptions.class);
        verify(labelService).applyFrameSave(any(), any(), any(), any(), opts.capture());
        assertThat(opts.getValue().hintFor(9001L)).isNotNull();
    }

    @Test
    @DisplayName("edits_의_추적_식별자가_저장에_반영된다")
    void edits_의_추적_식별자가_저장에_반영된다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        stubSingleFrame();
        stubSnapshot(SRC_A, "N", item(9001L, 12L, "7"));
        stubCoreSaves(1L);

        VideoLabelSaveRequest req = new VideoLabelSaveRequest(VERSION,
                List.of(frameVer(SRC_A, 1L)),
                List.of(new VideoLabelSaveRequest.FrameEdit(SRC_A,
                        List.of(editItem(9001L, 12L, "99")), null)));
        service.saveInTx(RAW_SN, req, reviewer, Map.of());

        assertThat(capturedRequest().items()).singleElement()
                .satisfies(item -> assertThat(item.trackId()).isEqualTo("99"));
    }

    @Test
    @DisplayName("그_회차를_알_수_없는_프레임은_현재_라벨을_그대로_재전송한다 — 본문을_추측하지_않는다")
    void 회차를_알_수_없는_프레임은_현재_라벨을_재전송한다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        stubSingleFrame();
        when(snapshotReader.readFrame(any(), eq(SRC_A))).thenReturn(Optional.empty());
        when(labelRepository.findBySrcSn(SRC_A))
                .thenReturn(List.of(kr.co.cudo.authoring.batch.entity.LsDataLbl.createManual(
                        SRC_A, "BBOX", 33L, "차량", "[[1.0,2.0],[3.0,4.0]]", 1L)));
        stubCoreSaves(1L);

        save(request(VERSION, frameVer(SRC_A, 1L)));

        LabelBulkUpsertRequest forwarded = capturedRequest();
        assertThat(forwarded.items()).singleElement()
                .satisfies(item -> assertThat(item.labelId()).isEqualTo(33L));
        // 폐기 여부는 "현재 값 유지"(null) — 없는 과거를 지어내지 않는다.
        assertThat(forwarded.dscdYn()).isNull();
    }

    // ---------- F-1: FE 페이로드 형태와 판정의 계약 ----------

    @Test
    @DisplayName("승인_이력_영상에서_라벨만_고친_저장은_통과한다 — 화면이_회차_폐기값을_그대로_실어_보낸다")
    void 승인_이력_영상에서_라벨만_고친_저장은_통과한다() {
        // ★ FE payload 형태를 <b>그대로</b> 재현한다: `loadedVersionDraft` 는 폐기를 토글하지 않아도
        //   `dscdYn = 사용자값 ?? 회차값` 으로 <b>항상 non-null</b> 을 싣는다(전 프레임 세트를 왕복시키는
        //   구조라 그렇다). 판정이 "필드 존재"였을 때 이 정상 동선이 400 이었다(F-1).
        //   ⚠ 이 테스트가 없어서 계약 불일치가 초록으로 통과했다 — 기존 단위테스트는 옵션 플래그를
        //     직접 주입해 FE 가 만드는 payload 를 한 번도 지나가지 않았다.
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        stubSingleFrame();
        stubSnapshot(SRC_A, "N", item(9001L, 12L, "7"));
        stubCoreSaves(1L);

        VideoLabelSaveRequest req = new VideoLabelSaveRequest(VERSION,
                List.of(frameVer(SRC_A, 1L)),
                // 라벨만 고쳤다(좌표 이동). 폐기값은 회차와 <b>같은</b> "N" 이 실려 온다.
                List.of(new VideoLabelSaveRequest.FrameEdit(SRC_A,
                        List.of(editItem(9001L, 12L, null)), "N")));
        service.saveInTx(RAW_SN, req, reviewer, Map.of());

        ArgumentCaptor<LabelService.FrameSaveOptions> opts =
                ArgumentCaptor.forClass(LabelService.FrameSaveOptions.class);
        verify(labelService).applyFrameSave(any(), any(), any(), any(), opts.capture());
        // 회차와 같은 값이면 새 조작이 아니다 → 승인 이력 영상에서도 게이트가 발동하지 않는다.
        assertThat(opts.getValue().discardFromApprovedVersion())
                .as("라벨만 고친 저장이 폐기 차단에 걸리면 승인 영상의 수정이 통째로 막힌다")
                .isTrue();
    }

    @Test
    @DisplayName("회차와_다른_폐기값을_지정하면_새_조작으로_본다 — 우회_차단은_유지된다")
    void 회차와_다른_폐기값은_새_조작이다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        stubSingleFrame();
        stubSnapshot(SRC_A, "N", item(9001L, 12L, "7"));
        stubCoreSaves(1L);

        VideoLabelSaveRequest req = new VideoLabelSaveRequest(VERSION,
                List.of(frameVer(SRC_A, 1L)),
                // 회차는 "N" 인데 "Y" 를 지정했다 — 회차를 불러온 뒤 폐기를 새로 하는 우회 시도다.
                List.of(new VideoLabelSaveRequest.FrameEdit(SRC_A, List.of(), "Y")));
        service.saveInTx(RAW_SN, req, reviewer, Map.of());

        ArgumentCaptor<LabelService.FrameSaveOptions> opts =
                ArgumentCaptor.forClass(LabelService.FrameSaveOptions.class);
        verify(labelService).applyFrameSave(any(), any(), any(), any(), opts.capture());
        assertThat(opts.getValue().discardFromApprovedVersion())
                .as("회차와 다른 값을 예외로 통과시키면 폐기 차단이 통째로 우회된다")
                .isFalse();
    }

    @Test
    @DisplayName("회차를_알_수_없는_프레임도_라이브와_다른_폐기값이면_새_조작으로_본다 — 우회_차단_유지")
    void 회차를_알_수_없는_프레임도_라이브와_다르면_새_조작이다() {
        // 스냅샷이 없으면 기준선은 <b>라이브 현재 값</b>이다(D-1). 라이브 "N" 에 "Y" 를 실었으므로
        //   사람이 새로 폐기하는 조작이며, 승인 이력 영상에서 차단돼야 한다.
        //   ⚠ 구 판정("스냅샷이 없으면 무조건 새 조작")과 결과는 같지만 <b>근거가 다르다</b> —
        //     구 판정은 값이 <b>같아도</b> 막아 회차 응답 왕복 자체를 400 으로 만들었다(D-1).
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        stubSingleFrame();   // frame(SRC_A, 0) 의 라이브 폐기여부는 "N"
        when(snapshotReader.readFrame(any(), eq(SRC_A))).thenReturn(Optional.empty());
        stubCoreSaves(1L);

        VideoLabelSaveRequest req = new VideoLabelSaveRequest(VERSION,
                List.of(frameVer(SRC_A, 1L)),
                List.of(new VideoLabelSaveRequest.FrameEdit(SRC_A, List.of(), "Y")));
        service.saveInTx(RAW_SN, req, reviewer, Map.of());

        ArgumentCaptor<LabelService.FrameSaveOptions> opts =
                ArgumentCaptor.forClass(LabelService.FrameSaveOptions.class);
        verify(labelService).applyFrameSave(any(), any(), any(), any(), opts.capture());
        assertThat(opts.getValue().discardFromApprovedVersion()).isFalse();
    }

    @Test
    @DisplayName("★회차를_알_수_없는_프레임에_현재_폐기값을_그대로_실으면_새_조작이_아니다 — D_1")
    void 회차를_알_수_없는_프레임의_현재값_재전송은_새_조작이_아니다() {
        // ★ 불러오기(API-195)는 스냅샷 없는 프레임에도 <b>현재 작업본의 폐기값</b>을 항상 싣는다
        //   ("그래야 이 세트를 그대로 확정 저장해도 그 프레임은 no-op"). 그 보장을 소비자가 지키는지
        //   고정한다 — 구 판정은 값이 같아도 새 조작으로 봐서 승인 이력 영상 저장이 전량 400 이었다.
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        LsDataSrc discarded = frame(SRC_A, 0);
        discarded.discard();
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(1L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(discarded));
        when(snapshotReader.readFrame(any(), eq(SRC_A))).thenReturn(Optional.empty());
        stubCoreSaves(1L);

        VideoLabelSaveRequest req = new VideoLabelSaveRequest(VERSION,
                List.of(frameVer(SRC_A, 1L)),
                // 라이브가 "Y" 인 폐기 프레임 — 불러오기가 돌려준 값을 그대로 되보낸다.
                List.of(new VideoLabelSaveRequest.FrameEdit(SRC_A, List.of(), "Y")));
        service.saveInTx(RAW_SN, req, reviewer, Map.of());

        ArgumentCaptor<LabelService.FrameSaveOptions> opts =
                ArgumentCaptor.forClass(LabelService.FrameSaveOptions.class);
        verify(labelService).applyFrameSave(any(), any(), any(), any(), opts.capture());
        assertThat(opts.getValue().discardFromApprovedVersion())
                .as("폐기 프레임은 라벨 0건이라 언제나 스냅샷이 없다 — 여기서 막히면 폐기를 쓴 영상은 저장 자체가 불가능하다")
                .isTrue();
    }

    @Test
    @DisplayName("프레임_루프는_승인_판정_캐시를_공유한다 — 락_보유_중_반복_조회_금지")
    void 프레임_루프는_승인_판정_캐시를_공유한다() {
        // ★ F-2 — 캐시가 없으면 프레임마다 최대 3쿼리(최대 6,000)가 돌고, 그 루프는 영상 전 프레임 행
        //   락을 보유한 상태라 지연이 곧 락 보유 시간이다(트랙 편집·병합·보간이 대기).
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(2L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_B, 1)));
        stubSnapshot(SRC_A, "N");
        stubSnapshot(SRC_B, "N");
        stubCoreSaves(1L);

        save(request(VERSION, frameVer(SRC_A, 1L), frameVer(SRC_B, 1L)));

        ArgumentCaptor<LabelService.FrameSaveOptions> opts =
                ArgumentCaptor.forClass(LabelService.FrameSaveOptions.class);
        verify(labelService, org.mockito.Mockito.times(2))
                .applyFrameSave(any(), any(), any(), any(), opts.capture());
        // 두 프레임이 <b>같은 맵 인스턴스</b>를 받아야 캐시가 실제로 적중한다(프레임마다 새 맵이면 무효).
        assertThat(opts.getAllValues().get(0).approvalCache())
                .as("프레임마다 새 캐시를 만들면 적중률 0 — 캐시가 있는 척만 하게 된다")
                .isNotNull()
                .isSameAs(opts.getAllValues().get(1).approvalCache());
    }

    // ---------- ★버전 축 불변 (사용자 확정 원칙) ----------

    @Test
    @DisplayName("확정_저장은_회차_기록과_활성_표식을_바꾸지_않는다")
    void 확정_저장은_회차_기록과_활성_표식을_바꾸지_않는다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        stubSingleFrame();
        stubSnapshot(SRC_A, "N", item(9001L, 12L, "7"));
        stubCoreSaves(1L);

        save(request(VERSION, frameVer(SRC_A, 1L)));

        // ★ 각 회차는 서로 간섭해선 안 된다 — 저장은 덮어쓰기가 아니라 새로 저장이다. 그래야 검수
        //   완료 시점마다 만들어진 데이터마트가 각각 유지된다. 롤백 시맨틱(대상 스냅샷 재활성)을
        //   재사용하면 그 순간 이 원칙이 깨진다.
        //
        // ⚠ <b>주입되지 않은 협력자에 never() 를 걸면 공허한 단언이다</b>(어떤 구현이든 통과한다).
        //   그래서 이 서비스가 <b>실제로 주입받은</b> 스냅샷 리더에 대해 "읽기 메서드 둘 외에는 아무것도
        //   호출하지 않았음"을 단언한다. 회차 기록·활성 표식을 바꾸는 협력자를 새로 주입하는 순간
        //   VersionAxisImmutableGuardTest(정적)가 잡는다 — 두 축이 짝이다.
        verify(snapshotReader).versionExists(RAW_SN, VERSION);
        verify(snapshotReader).resolveTargets(RAW_SN, VERSION);
        verify(snapshotReader).readFrame(any(), eq(SRC_A));
        org.mockito.Mockito.verifyNoMoreInteractions(snapshotReader);
    }

    // ---------- 잠금 규약 ----------

    @Test
    @DisplayName("영상_전_프레임_락을_먼저_선점한_뒤_프레임별_저장으로_들어간다 — ABBA_교착_방지")
    void 영상_전_프레임_락을_먼저_선점한다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(2L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_B, 1)));
        stubSnapshot(SRC_A, "N");
        stubSnapshot(SRC_B, "N");
        stubCoreSaves(1L);

        save(request(VERSION, frameVer(SRC_A, 1L), frameVer(SRC_B, 1L)));

        // ★ SRC_SN 축 단일 문장 선점이 프레임별 저장보다 먼저 와야 한다. FRM_NO 순서로 프레임마다
        //   개별 락을 잡으면 TrackEdit/TrackMerge/TrackInterpolation(SRC_SN 축)과 순환 대기 → 40P01.
        InOrder order = inOrder(srcRepository, labelService);
        order.verify(srcRepository).lockFramesByRawSn(RAW_SN);
        order.verify(labelService, org.mockito.Mockito.atLeastOnce())
                .applyFrameSave(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("요청_순서가_아니라_프레임번호_오름차순으로_저장한다")
    void 프레임번호_오름차순으로_저장한다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(2L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_B, 1)));
        stubSnapshot(SRC_A, "N");
        stubSnapshot(SRC_B, "N");
        stubCoreSaves(1L);

        // 요청은 역순(프레임 1 → 0)으로 들어온다.
        save(request(VERSION, frameVer(SRC_B, 1L), frameVer(SRC_A, 1L)));

        ArgumentCaptor<LsDataSrc> frames = ArgumentCaptor.forClass(LsDataSrc.class);
        verify(labelService, org.mockito.Mockito.times(2))
                .applyFrameSave(any(), frames.capture(), any(), any(), any());
        assertThat(frames.getAllValues()).extracting(LsDataSrc::getSrcSn)
                .containsExactly(SRC_A, SRC_B);
    }

    // ---------- 좌표 경계 기준값 ----------

    @Test
    @DisplayName("좌표_경계_기준값은_트랜잭션_밖에서_받은_값을_그대로_코어에_넘긴다")
    void 좌표_경계_기준값을_그대로_코어에_넘긴다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        stubSingleFrame();
        stubSnapshot(SRC_A, "N");
        stubCoreSaves(1L);

        service.saveInTx(RAW_SN, request(VERSION, frameVer(SRC_A, 1L)), reviewer,
                Map.of(SRC_A, new int[] {1920, 1080}));

        ArgumentCaptor<LabelService.FrameSaveOptions> opts =
                ArgumentCaptor.forClass(LabelService.FrameSaveOptions.class);
        verify(labelService).applyFrameSave(any(), any(), any(), any(), opts.capture());
        // boundsResolved=true 여야 코어가 FrameBoundsResolver(=ImageIO 디코딩)를 호출하지 않는다.
        assertThat(opts.getValue().boundsResolved()).isTrue();
        assertThat(opts.getValue().preResolvedBounds()).containsExactly(1920, 1080);
    }

    // ---------- F-02 · F-05 · F-06 ----------

    @Test
    @DisplayName("확정_저장은_요청의_생산이력_주장을_무시한다")
    void 확정_저장은_요청의_생산이력_주장을_무시한다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        stubSingleFrame();
        stubSnapshot(SRC_A, "N");
        stubCoreSaves(1L);

        // 요청이 "이건 AI 가 만들었다"고 주장한다 — 이 경로의 출처는 회차 스냅샷 하나여야 한다.
        LabelItemDto claimed = new LabelItemDto(null, "BBOX", 12L, "사람",
                List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)), null,
                "AUTO_YOLO", 0.99, "YOLO", null);
        VideoLabelSaveRequest req = new VideoLabelSaveRequest(VERSION,
                List.of(frameVer(SRC_A, 1L)),
                List.of(new VideoLabelSaveRequest.FrameEdit(SRC_A, List.of(claimed), null)));
        service.saveInTx(RAW_SN, req, reviewer, Map.of());

        ArgumentCaptor<LabelService.FrameSaveOptions> opts =
                ArgumentCaptor.forClass(LabelService.FrameSaveOptions.class);
        verify(labelService).applyFrameSave(any(), any(), any(), any(), opts.capture());
        // ★ 코어가 요청 출처를 읽지 않도록 경로 축에서 막는다(CWE-915).
        assertThat(opts.getValue().acceptRequestProvenance()).isFalse();
        assertThat(opts.getValue().requestSourceOf(claimed)).isNull();
        // trackId 는 이 경로가 사양상 수용하는 축이라 열려 있다.
        assertThat(opts.getValue().acceptTrackId()).isTrue();
    }

    @Test
    @DisplayName("편집분은_판번호_목록_기준으로_대조된다 — 판번호_없는_프레임은_저장되지_않는다")
    void 편집분은_판번호_목록_기준으로_대조된다() {
        // ⚠ <b>현재는 도달 불가</b>다 — 커버리지 강제가 "frameVersions == 전 프레임"을 요구해 판번호
        //   목록과 소속 집합이 항상 같기 때문이다. 그래서 이 입력은 커버리지 단계에서 먼저 거부된다.
        //   그럼에도 대조 집합을 소속이 아니라 <b>판번호 목록</b>으로 둔 이유는, 커버리지 규칙이
        //   완화되는 순간 판번호 없는 프레임이 edits 로 들어와 requireLabelVersionMatch 가
        //   "요청값 null → 검사 skip" 으로 빠져 낙관적 동시성 검증이 통째로 꺼지기 때문이다.
        //   이 테스트는 그 입력이 <b>어느 단계에서든 거부되고 저장으로 새지 않는다</b>를 고정한다.
        stubGatesOpen();
        stubVersionExists();
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(2L);

        VideoLabelSaveRequest req = new VideoLabelSaveRequest(VERSION,
                List.of(frameVer(SRC_A, 1L)),
                List.of(new VideoLabelSaveRequest.FrameEdit(SRC_B, List.of(), null)));

        assertThatThrownBy(() -> service.saveInTx(RAW_SN, req, reviewer, Map.of()))
                .isInstanceOf(CustomException.class);
        verify(labelService, never()).applyFrameSave(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("락_이후_프레임_수가_달라지면_거부한다")
    void 락_이후_프레임_수가_달라지면_거부한다() {
        stubGatesOpen();
        stubVersionExists();
        // 커버리지 판정 시점엔 1장이었는데(count=1), 락 이후 조회에서 2장이 된다(그 사이 추출).
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(1L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_B, 1)));

        assertThatThrownBy(() -> save(request(VERSION, frameVer(SRC_A, 1L))))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        // 부분집합인 채로 저장되면 "한 영상 = 한 회차"가 그 프레임 하나에서 조용히 깨진다.
        verify(labelService, never()).applyFrameSave(any(), any(), any(), any(), any());
    }

    // ---------- 전량 거부 · 감사 ----------

    @Test
    @DisplayName("판번호가_어긋나면_코어의_409_가_그대로_전파된다 — 부분_저장이_없다")
    void 판번호가_어긋나면_409_가_전파된다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        stubSingleFrame();
        stubSnapshot(SRC_A, "N");
        when(labelService.applyFrameSave(any(), any(), any(), any(), any()))
                .thenThrow(new CustomException(ErrorCode.CONFLICT, "다른 사용자가 먼저 저장했습니다."));

        assertThatThrownBy(() -> save(request(VERSION, frameVer(SRC_A, 1L))))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        // 감사도 남기지 않는다 — 확정되지 않은 저장이다.
        verify(taskEventLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("같은_프레임이_두_번_실려_있으면_400")
    void 같은_프레임이_두_번_실려있으면_400() {
        stubGatesOpen();
        stubVersionExists();

        assertThatThrownBy(() -> save(request(VERSION, frameVer(SRC_A, 1L), frameVer(SRC_A, 2L))))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("판번호_목록에_없는_프레임이_edits_에만_오면_404 — 동시성_검증_우회_차단")
    void 판번호_목록에_없는_프레임이_edits_에만_오면_404() {
        stubGatesOpen();
        stubVersionExists();
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(1L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));

        VideoLabelSaveRequest req = new VideoLabelSaveRequest(VERSION,
                List.of(frameVer(SRC_A, 1L)),
                List.of(new VideoLabelSaveRequest.FrameEdit(999L, List.of(), null)));

        assertThatThrownBy(() -> service.saveInTx(RAW_SN, req, reviewer, Map.of()))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    @DisplayName("어느_회차로_확정했는지_영상_단위_감사로_남긴다")
    void 어느_회차로_확정했는지_감사로_남긴다() {
        stubGatesOpen();
        stubVersionExists();
        stubActor();
        stubSingleFrame();
        stubSnapshot(SRC_A, "N");
        stubCoreSaves(1L);

        save(request(VERSION, frameVer(SRC_A, 1L)));

        ArgumentCaptor<LsTaskEventLog> captor = ArgumentCaptor.forClass(LsTaskEventLog.class);
        verify(taskEventLogRepository).save(captor.capture());
        assertThat(captor.getValue().getEventTypeCd())
                .isEqualTo(LsTaskEventLog.EVENT_START_VERSION_APPLY);
        // RSN 에는 검증을 통과한 회차 번호 한 토큰만 싣는다(CWE-359).
        assertThat(captor.getValue().getRsn())
                .isEqualTo(LsTaskEventLog.RSN_START_VERSION_PREFIX + VERSION);
    }

    // ---------- 고정 스텁 ----------

    private VideoLabelSaveResponse save(VideoLabelSaveRequest req) {
        return service.saveInTx(RAW_SN, req, reviewer, Map.of());
    }

    private void stubGatesOpen() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
    }

    private void stubVersionExists() {
        when(snapshotReader.versionExists(RAW_SN, VERSION)).thenReturn(true);
    }

    private void stubActor() {
        when(accessGuard.parseUserNo("1")).thenReturn(1L);
    }

    private void stubSingleFrame() {
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(1L);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
    }

    private void stubSnapshot(Long srcSn, String dscdYn, LabelResponse.Item... items) {
        when(snapshotReader.readFrame(any(), eq(srcSn)))
                .thenReturn(Optional.of(new VersionSnapshotReader.FrameSnapshot(dscdYn, List.of(items))));
    }

    private void stubCoreSaves(long newVersion) {
        when(labelService.applyFrameSave(any(), any(), any(), any(), any())).thenReturn(
                new LabelService.FrameSaveOutcome(List.of(), newVersion,
                        FrameDiscardApplier.Outcome.UNCHANGED, true));
    }

    private LabelBulkUpsertRequest capturedRequest() {
        ArgumentCaptor<LabelBulkUpsertRequest> captor =
                ArgumentCaptor.forClass(LabelBulkUpsertRequest.class);
        verify(labelService).applyFrameSave(any(), any(), captor.capture(), any(), any());
        return captor.getValue();
    }

    private VideoLabelSaveRequest request(int version, VideoLabelSaveRequest.FrameVersion... versions) {
        return new VideoLabelSaveRequest(version, List.of(versions), List.of());
    }

    private VideoLabelSaveRequest.FrameVersion frameVer(Long srcSn, Long lblVer) {
        return new VideoLabelSaveRequest.FrameVersion(srcSn, lblVer);
    }

    private LabelItemDto editItem(Long id, Long labelId, String trackId) {
        return new LabelItemDto(id, "BBOX", labelId, "사람",
                List.of(List.of(1.0, 2.0), List.of(3.0, 4.0)), null, null, null, null, trackId);
    }

    private LabelResponse.Item item(Long id, Long labelId, String trackId) {
        return new LabelResponse.Item(id, "BBOX", "사람", labelId, "사람", "#EF4444",
                List.of(List.of(120.0, 80.0), List.of(260.0, 400.0)), "N", null, trackId, null);
    }

    private LabelResponse.Item autoItem(Long id, Long labelId, String trackId,
                                        String lblSrcCd, String conf) {
        return new LabelResponse.Item(id, "BBOX", "사람", labelId, "사람", "#EF4444",
                List.of(List.of(120.0, 80.0), List.of(260.0, 400.0)), "Y",
                new BigDecimal(conf), trackId, lblSrcCd);
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
