package kr.co.cudo.authoring.label.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveRequest;
import kr.co.cudo.authoring.label.dto.VideoLabelSaveResponse;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.user.service.UserNameResolver;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.version.service.VersionSnapshotReader;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-1 — 회차 불러오기(API-195) → 확정 저장(API-196) <b>왕복</b>의 폐기 판정 기준선.
 *
 * <h3>무엇이 깨져 있었나 (로컬 실증)</h3>
 * 생산자({@code StartVersionService.loadVersionLabels})는 스냅샷이 없는 프레임
 * ({@code resolved=false})에도 폐기여부를 <b>항상 non-null</b> 로 싣고, 그렇게 해야 "이 세트를 그대로
 * 확정 저장해도 그 프레임은 no-op" 이라고 보장한다. 그런데 소비자가 <b>스냅샷 값 하나</b>만 비교
 * 기준으로 삼아, 스냅샷이 없으면(라벨 0건 프레임은 언제나 그렇다) 값이 <b>같아도</b> "새 조작"으로
 * 판정해 승인 이력 영상에서 {@code 400} 이 났다. 폐기한 프레임은 정의상 라벨 0건이라
 * <b>프레임 폐기를 쓴 영상은 사실상 항상</b> 확정 저장이 막혔다.
 *
 * <h3>고친 것 — 기준선을 두 단계로 본다</h3>
 * 스냅샷이 있으면 <b>스냅샷 값</b>, 없으면 <b>라이브 현재 값</b>({@code LS_DATA_SRC.DSCD_YN})이 기준선이다.
 * 그래서 세 성질이 동시에 성립한다:
 * <ol>
 *   <li>회차 적용(스냅샷 값 전송)은 라이브와 달라도 <b>허용</b> — 사용자 확정 「회차 적용은 예외」</li>
 *   <li>스냅샷 없는 프레임에 현재 값을 그대로 전송하면 <b>허용</b>(D-1 — 아무것도 바꾸지 않는 저장)</li>
 *   <li>어느 경우든 기준선과 <b>다른</b> 값을 전송하면 {@code 400} — 우회 차단은 그대로</li>
 * </ol>
 *
 * <p>이 클래스는 {@code VideoLabelSaveTxService} 와 <b>실제 저장 코어</b>({@code LabelService} ·
 * {@code FrameDiscardApplier})를 함께 태운다 — 판정 플래그만 단언하면 그 플래그를 실제로 소비하는
 * 게이트가 400 을 내는지 알 수 없어, 이 결함이 정확히 그 틈으로 통과했다.
 *
 * @design API-195
 * @design API-196
 * @req R4
 * @req R5
 * @req R6
 */
class VideoLabelSaveDiscardBaselineTest {

    private static final Long RAW_SN = 22L;
    /** 승인 스냅샷이 있는 프레임(라벨 보유). */
    private static final Long SRC_RESOLVED = 133L;
    /** 라벨 0건이라 어느 회차에도 스냅샷이 없는 프레임 — D-1 의 무대. */
    private static final Long SRC_UNRESOLVED = 144L;
    private static final int VERSION = 2;

    private LabelAccessGuard accessGuard;
    private VideoRepository videoRepository;
    private WorkLockService workLockService;
    private LsDataSrcRepository srcRepository;
    private LsTaskEventLogRepository taskEventLogRepository;
    private VersionSnapshotReader snapshotReader;
    private LsDataLblRepository labelRepository;
    private ReviewApprovalGate approvalGate;

    private VideoLabelSaveTxService service;
    private final Map<Long, LsDataSrc> frames = new HashMap<>();
    private final Map<Long, String> liveDiscardFlags = new HashMap<>();

    @BeforeEach
    void setUp() {
        accessGuard = mock(LabelAccessGuard.class);
        videoRepository = mock(VideoRepository.class);
        workLockService = mock(WorkLockService.class);
        srcRepository = mock(LsDataSrcRepository.class);
        taskEventLogRepository = mock(LsTaskEventLogRepository.class);
        snapshotReader = mock(VersionSnapshotReader.class);
        labelRepository = mock(LsDataLblRepository.class);
        approvalGate = mock(ReviewApprovalGate.class);

        FrameDiscardApplier applier = new FrameDiscardApplier(srcRepository, taskEventLogRepository);
        LabelService labelService = new LabelService(labelRepository,
                srcRepository, videoRepository, workLockService,
                accessGuard, new ObjectMapper(), mock(LsLabelRepository.class),
                mock(ApplicationEventPublisher.class), approvalGate,
                mock(LsDataLblHstryRepository.class), mock(LsDataLblAttrValRepository.class),
                mock(FrameBoundsResolver.class), mock(UserNameResolver.class), applier);
        service = new VideoLabelSaveTxService(accessGuard, videoRepository, workLockService,
                srcRepository, labelService, taskEventLogRepository, snapshotReader,
                labelRepository, new ObjectMapper());

        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(accessGuard.parseUserNo("1")).thenReturn(1L);
        when(snapshotReader.versionExists(RAW_SN, VERSION)).thenReturn(true);
        when(snapshotReader.resolveTargets(eq(RAW_SN), eq(VERSION))).thenReturn(Map.of());
        // 저장 코어가 쓰는 최소 스텁 — 라벨은 0건 시나리오라 본문 경로는 타지 않는다.
        when(srcRepository.lockAndReadLabelVersion(anyLong())).thenReturn(Optional.of(1L));
        when(labelRepository.findBySrcSn(anyLong())).thenReturn(List.of());
        when(labelRepository.findDistinctSrcSnsWithLabelIn(anyCollection())).thenReturn(List.of());
        // 라이브 폐기여부는 <b>행 락 이후 DB 현재 값</b>이 유일한 기준이다(FrameDiscardApplier 규약).
        when(srcRepository.readDiscardFlag(anyLong()))
                .thenAnswer(inv -> Optional.of(liveDiscardFlags.getOrDefault(
                        inv.getArgument(0), LsDataSrc.DSCD_NO)));
        when(srcRepository.applyDiscardFlag(anyLong(), any())).thenAnswer(inv -> {
            liveDiscardFlags.put(inv.getArgument(0), inv.getArgument(1));
            return 1;
        });
        // ★ 이 영상은 한번이라도 검수가 완료됐다 — 폐기·복원 차단이 살아 있는 상태다.
        when(approvalGate.hasEverApprovedCached(eq(RAW_SN), any())).thenReturn(true);
    }

    // ───────────── D-1 재현 ─────────────

    @Test
    @DisplayName("★회차_응답을_그대로_확정저장하면_스냅샷_없는_프레임도_통과한다")
    void 회차_응답을_그대로_확정저장하면_스냅샷_없는_프레임도_통과한다() {
        // 불러오기 응답 그대로: resolved 프레임은 스냅샷 값("N"), unresolved 프레임은 현재 작업본 값("Y").
        //   생산자는 두 경우 모두 <b>non-null</b> 을 싣는다(StartVersionService — "그래야 이 세트를 그대로
        //   확정 저장해도 그 프레임은 no-op").
        givenFrame(SRC_RESOLVED, 0, LsDataSrc.DSCD_NO);
        givenFrame(SRC_UNRESOLVED, 11, LsDataSrc.DSCD_YES);
        givenSnapshot(SRC_RESOLVED, LsDataSrc.DSCD_NO);
        givenNoSnapshot(SRC_UNRESOLVED);

        VideoLabelSaveResponse res = save(
                edit(SRC_RESOLVED, LsDataSrc.DSCD_NO),
                edit(SRC_UNRESOLVED, LsDataSrc.DSCD_YES));

        assertThat(res.savedFrameCount()).isEqualTo(2);
        assertThat(res.discardedFrameCount())
                .as("폐기 상태가 그대로 유지되어야 한다(아무것도 바꾸지 않는 저장)")
                .isEqualTo(1);
        // 값이 같으므로 상태 전이도 감사도 없다(멱등).
        verify(srcRepository, never()).applyDiscardFlag(anyLong(), any());
    }

    @Test
    @DisplayName("폐기_프레임이_포함된_영상도_회차_확정저장이_통과한다")
    void 폐기_프레임이_포함된_영상도_회차_확정저장이_통과한다() {
        // 실사용 시나리오 — 폐기한 프레임은 라벨이 0건이라 <b>언제나</b> unresolved 다.
        //   이 경로가 막히면 프레임 폐기를 쓴 영상은 확정 저장 자체를 못 한다.
        givenFrame(SRC_RESOLVED, 0, LsDataSrc.DSCD_NO);
        givenFrame(SRC_UNRESOLVED, 11, LsDataSrc.DSCD_YES);
        givenFrame(140L, 7, LsDataSrc.DSCD_NO);
        givenSnapshot(SRC_RESOLVED, LsDataSrc.DSCD_NO);
        givenNoSnapshot(SRC_UNRESOLVED);
        givenNoSnapshot(140L);

        assertThatCode(() -> save(
                edit(SRC_RESOLVED, LsDataSrc.DSCD_NO),
                edit(SRC_UNRESOLVED, LsDataSrc.DSCD_YES),
                edit(140L, LsDataSrc.DSCD_NO)))
                .doesNotThrowAnyException();
    }

    // ───────────── 우회 차단은 유지된다 ─────────────

    @Test
    @DisplayName("승인_이력_영상에서_스냅샷_있는_프레임의_폐기값을_바꾸면_400이다")
    void 승인_이력_영상에서_스냅샷_있는_프레임의_폐기값을_바꾸면_400이다() {
        givenFrame(SRC_RESOLVED, 0, LsDataSrc.DSCD_NO);
        givenSnapshot(SRC_RESOLVED, LsDataSrc.DSCD_NO);

        // 회차는 "N" 인데 "Y" 를 지정했다 — 회차를 불러온 뒤 폐기를 새로 하는 우회 시도다.
        assertThatThrownBy(() -> save(edit(SRC_RESOLVED, LsDataSrc.DSCD_YES)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        verify(srcRepository, never()).applyDiscardFlag(anyLong(), any());
    }

    @Test
    @DisplayName("승인_이력_영상에서_스냅샷_없는_프레임의_폐기값을_바꾸면_400이다")
    void 승인_이력_영상에서_스냅샷_없는_프레임의_폐기값을_바꾸면_400이다() {
        // ★ D-1 수정이 이 축까지 열어버리면 "회차를 한 번 불러오는 것만으로 우회"가 성립한다.
        //   스냅샷이 없어도 <b>라이브 값</b>이라는 기준선이 남아 있어야 한다.
        givenFrame(SRC_UNRESOLVED, 11, LsDataSrc.DSCD_NO);
        givenNoSnapshot(SRC_UNRESOLVED);

        assertThatThrownBy(() -> save(edit(SRC_UNRESOLVED, LsDataSrc.DSCD_YES)))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        verify(srcRepository, never()).applyDiscardFlag(anyLong(), any());
    }

    // ───────────── 회차 적용 예외(사용자 확정)는 깨지지 않는다 ─────────────

    @Test
    @DisplayName("불러온_회차의_폐기상태_적용은_라이브와_달라도_허용된다")
    void 불러온_회차의_폐기상태_적용은_라이브와_달라도_허용된다() {
        // 라이브는 "N" 인데 회차 스냅샷은 "Y" — 그 시점으로 되돌아가는 것이므로 막지 않는다.
        //   기준선을 라이브로만 잡으면 이 정당한 동선이 400 이 되어 "한 영상 = 한 회차"가 깨진다.
        givenFrame(SRC_RESOLVED, 0, LsDataSrc.DSCD_NO);
        givenSnapshot(SRC_RESOLVED, LsDataSrc.DSCD_YES);

        VideoLabelSaveResponse res = save(edit(SRC_RESOLVED, LsDataSrc.DSCD_YES));

        assertThat(res.discardedFrameCount()).isEqualTo(1);
        // 실제로 폐기 전이가 적용된다(허용만 하고 반영하지 않으면 의미가 없다).
        verify(srcRepository).applyDiscardFlag(SRC_RESOLVED, LsDataSrc.DSCD_YES);
    }

    // ───────────── 고정 스텁 ─────────────

    private void givenFrame(Long srcSn, int frameNo, String dscdYn) {
        LsDataSrc frame = LsDataSrc.create(RAW_SN, frameNo, "/raw/" + frameNo + ".jpg", null);
        ReflectionTestUtils.setField(frame, "srcSn", srcSn);
        if (LsDataSrc.DSCD_YES.equals(dscdYn)) {
            frame.discard();
        }
        frames.put(srcSn, frame);
        liveDiscardFlags.put(srcSn, dscdYn);
    }

    private void givenSnapshot(Long srcSn, String dscdYn) {
        when(snapshotReader.readFrame(any(), eq(srcSn)))
                .thenReturn(Optional.of(new VersionSnapshotReader.FrameSnapshot(dscdYn, List.of())));
    }

    private void givenNoSnapshot(Long srcSn) {
        when(snapshotReader.readFrame(any(), eq(srcSn))).thenReturn(Optional.empty());
    }

    private VideoLabelSaveRequest.FrameEdit edit(Long srcSn, String dscdYn) {
        return new VideoLabelSaveRequest.FrameEdit(srcSn, List.of(), dscdYn);
    }

    private VideoLabelSaveResponse save(VideoLabelSaveRequest.FrameEdit... edits) {
        List<LsDataSrc> ordered = new ArrayList<>(frames.values());
        ordered.sort(java.util.Comparator.comparing(LsDataSrc::getFrameNo));
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn((long) ordered.size());
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(ordered);

        List<VideoLabelSaveRequest.FrameVersion> versions = new ArrayList<>();
        for (LsDataSrc frame : ordered) {
            versions.add(new VideoLabelSaveRequest.FrameVersion(frame.getSrcSn(), 1L));
        }
        VideoLabelSaveRequest req = new VideoLabelSaveRequest(VERSION, versions, List.of(edits));
        return service.saveInTx(RAW_SN, req, reviewer(), Map.of());
    }

    private TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(600));
    }

    private LsDataRaw raw() {
        LsDataRaw raw = LsDataRaw.createFromIngest("CLIP-D1", "CCTV", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/clip.mp4", LocalDateTime.now(), 30);
        ReflectionTestUtils.setField(raw, "rawSn", RAW_SN);
        return raw;
    }
}
