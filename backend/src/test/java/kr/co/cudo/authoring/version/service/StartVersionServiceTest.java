package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.ChangeType;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.service.FrameDiscardApplier;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.config.StartVersionProperties;
import kr.co.cudo.authoring.version.dto.SnapshotVersionRef;
import kr.co.cudo.authoring.version.dto.StartVersionApplyResult;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.version.repository.LsOutputVerSnpshRepository;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R6·D4·D5 — 영상 단위 「시작 버전 선택」 오케스트레이션 단위 테스트.
 *
 * <p>고정하는 계약:
 * <ul>
 *   <li>조회 규칙 — 프레임마다 <b>회차↔스냅샷 매핑</b>({@code LS_OUTPUT_VER_SNPSH})에서
 *       「회차 ≤ N 중 최대」를 고른다. {@code LS_LABEL_VERSION.VER_NO} 는 판정 원천이 <b>아니다</b>
 *       (한 스냅샷이 여러 회차의 내용일 수 있어 컬럼 하나로는 담기지 않는다).</li>
 *   <li>요청 버전이 그 영상에 실재하지 않으면 조용한 빈 결과가 아니라 <b>404</b> 다
 *       (화면이 "변경 없음"으로 표시해 거짓말이 되는 것을 막는다).</li>
 *   <li>스냅샷의 폐기여부를 프레임에 되돌린다 — 필드가 없는 옛 스냅샷은 <b>폐기 아님</b>이다.</li>
 *   <li>게이트 순서 — <b>신고(412)가 작업락(409)보다 먼저</b>이고, 루프가 끝난 뒤 신고를
 *       <b>한 번 더</b> 평가한다(CWE-367).</li>
 *   <li>자원 상한 — 동시 실행 409 · 프레임 수 초과 400 (CWE-770).</li>
 *   <li>비가역 조작의 영상 단위 감사(누가·어느 회차)를 남긴다(CWE-778).</li>
 * </ul>
 *
 * <p>라벨 본문 복원은 {@link VersionService#rollbackToSnapshot} 에 위임하므로 여기서는 <b>위임 여부</b>만
 * 검증한다(복원 시맨틱 자체는 기존 롤백 테스트·IT 가 이미 고정하고 있다 — 재구현하지 않았다는 뜻).
 * 롤백 후 재산출 회차의 <b>실동작</b> 재현은 {@code StartVersionRollbackReproIT} 가 담당한다.
 *
 * @design D4
 * @design D5
 * @req R6
 */
@ExtendWith(MockitoExtension.class)
class StartVersionServiceTest {

    private static final Long RAW_SN = 9L;
    private static final Long SRC_A = 51L;
    private static final Long SRC_B = 52L;
    private static final Long SRC_C = 53L;

    private static final int MAX_FRAMES = 3;

    /** 폐기 축을 담은 새 형식 스냅샷(D5 이후). */
    private static final String PAYLOAD_DISCARDED = """
            {"srcSn":51,"frameNo":0,"dscdYn":"Y","items":[]}""";
    /** 폐기 축이 없는 옛 형식 스냅샷(D5 이전) — 읽는 쪽이 "폐기 아님"으로 해석해야 한다. */
    private static final String PAYLOAD_LEGACY = """
            {"srcSn":52,"frameNo":1,"items":[]}""";

    @Mock private LabelAccessGuard accessGuard;
    @Mock private VideoRepository videoRepository;
    @Mock private WorkLockService workLockService;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsLabelVersionRepository labelVersionRepository;
    @Mock private LsOutputVerSnpshRepository outputVerSnpshRepository;
    @Mock private VersionService versionService;
    @Mock private FrameDiscardApplier frameDiscardApplier;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ReviewApprovalGate approvalGate;
    @Mock private LsTaskEventLogRepository taskEventLogRepository;

    private StartVersionService service;
    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        service = new StartVersionService(accessGuard, videoRepository, workLockService,
                srcRepository, labelVersionRepository, outputVerSnpshRepository, versionService,
                frameDiscardApplier, eventPublisher, approvalGate, taskEventLogRepository,
                new StartVersionProperties(MAX_FRAMES), new ObjectMapper());
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    // ---------- 입력·인가·게이트 ----------

    @Test
    @DisplayName("인증_토큰이_없으면_401")
    void 인증_토큰이_없으면_401() {
        assertThatThrownBy(() -> service.applyStartVersion(RAW_SN, 1, null))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UNAUTHORIZED);
    }

    @Test
    @DisplayName("버전번호가_1미만이면_400")
    void 버전번호가_1미만이면_400() {
        assertThatThrownBy(() -> service.applyStartVersion(RAW_SN, 0, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("본인에게_배정되지_않은_영상이면_403")
    void 본인에게_배정되지_않은_영상이면_403() {
        doThrow(new CustomException(ErrorCode.FORBIDDEN, "본인에게 배정되지 않은 영상입니다."))
                .when(accessGuard).verifyRawAccess(eq(RAW_SN), any());

        assertThatThrownBy(() -> service.applyStartVersion(RAW_SN, 1, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN);
        verify(versionService, never()).rollbackToSnapshot(any(), any(), any(), any());
    }

    @Test
    @DisplayName("작업이_잠긴_영상은_409")
    void 작업이_잠긴_영상은_409() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(true);

        assertThatThrownBy(() -> service.applyStartVersion(RAW_SN, 1, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    @DisplayName("비식별_신고_구간_영상은_412")
    void 비식별_신고_구간_영상은_412() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED, "비식별 재처리 대기 중인 영상입니다."))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);

        assertThatThrownBy(() -> service.applyStartVersion(RAW_SN, 1, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRECONDITION_FAILED);
    }

    @Test
    @DisplayName("신고와_작업락이_함께_걸려도_412_다 — 응답코드가_잠금상태_오라클이_되지_않는다")
    void 신고와_작업락이_함께_걸려도_412_다() {
        // given — 신고는 작업락과 DE_IDNTF_YN='F' 를 함께 세운다. 락을 먼저 보면 같은 영상이
        //   신고 직후엔 409, 락 회수(6h) 뒤엔 412 를 주어 응답이 내부 잠금 상태를 알려주게 된다.
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        doThrow(new CustomException(ErrorCode.PRECONDITION_FAILED, "비식별 재처리 대기 중인 영상입니다."))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);

        assertThatThrownBy(() -> service.applyStartVersion(RAW_SN, 1, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRECONDITION_FAILED);
        // 작업락은 조회조차 하지 않는다 — 신고 구간의 응답은 락 유무와 무관해야 한다.
        verify(workLockService, never()).isRawLocked(anyLong());
    }

    @Test
    @DisplayName("그_영상에_실재하지_않는_버전번호는_404_로_거부한다")
    void 그_영상에_실재하지_않는_버전번호는_404_로_거부한다() {
        stubGatesOpen();
        when(labelVersionRepository.existsByDataRawSnAndVersionNo(RAW_SN, 7)).thenReturn(false);

        assertThatThrownBy(() -> service.applyStartVersion(RAW_SN, 7, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
        verify(versionService, never()).rollbackToSnapshot(any(), any(), any(), any());
    }

    @Test
    @DisplayName("버전번호가_전부_NULL_인_과도기에는_빈_성공이_아니라_404_다")
    void 버전번호가_전부_NULL_인_과도기에는_빈_성공이_아니라_404_다() {
        // given — 스냅샷은 있으나 채번 배선 이전 행이라 VER_NO 가 전부 NULL 이다.
        stubGatesOpen();
        when(labelVersionRepository.existsByDataRawSnAndVersionNo(RAW_SN, 1)).thenReturn(false);

        // then — 조용히 0건 성공(=화면 "변경 없음")이 아니라 명시 거부다.
        assertThatThrownBy(() -> service.applyStartVersion(RAW_SN, 1, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    // ---------- 자원 상한 (CWE-770) ----------

    @Test
    @DisplayName("같은_영상에_대한_동시_실행은_대기하지_않고_409_다")
    void 같은_영상에_대한_동시_실행은_409() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(labelVersionRepository.tryAcquireVideoVersionLock(anyInt(), anyInt())).thenReturn(false);

        assertThatThrownBy(() -> service.applyStartVersion(RAW_SN, 1, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONFLICT);
        // 잠금을 못 잡았으면 프레임을 읽지도 않는다(커넥션을 쥔 채 줄 서지 않는다).
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
    }

    @Test
    @DisplayName("프레임_수가_상한을_넘으면_엔티티를_로드하기_전에_400_으로_거부한다")
    void 프레임_수가_상한을_넘으면_400() {
        stubGatesOpen();
        stubVersionExistsOnly(1);
        // 상한 3 을 넘는 4장 — 일부만 되돌리면 회차가 섞인 혼합 영상이 되므로 거부한다.
        when(srcRepository.countByRawSn(RAW_SN)).thenReturn(4L);

        assertThatThrownBy(() -> service.applyStartVersion(RAW_SN, 1, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        // ★ 상한은 <로드 이전>에 작동해야 한다 — 로드한 뒤에 세면 거부할 영상도 프레임 엔티티가
        //   전량 힙에 올라온 뒤에야 거부된다(상한이 자원을 지키지 못한다, CWE-770).
        verify(srcRepository, never()).findByRawSnOrderByFrameNoAsc(anyLong());
        verify(versionService, never()).rollbackToSnapshot(any(), any(), any(), any());
    }

    // ---------- 조회 규칙 (매핑에서 회차 <= N 중 최대) ----------

    @Test
    @DisplayName("프레임마다_요청회차_이하_중_가장_큰_회차의_스냅샷을_고른다")
    void 프레임마다_요청회차_이하_중_가장_큰_회차의_스냅샷을_고른다() {
        stubGatesOpen();
        stubVersionExists(3);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_B, 1)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 3)).thenReturn(List.of(
                new SnapshotVersionRef(SRC_A, 1, 1001L),
                new SnapshotVersionRef(SRC_A, 3, 1003L),
                new SnapshotVersionRef(SRC_B, 1, 1002L)));
        stubSnapshot(1003L, SRC_A, PAYLOAD_DISCARDED, 3);
        stubSnapshot(1002L, SRC_B, PAYLOAD_LEGACY, 1);
        stubDiscardApply();

        StartVersionApplyResult result = service.applyStartVersion(RAW_SN, 3, reviewer);

        ArgumentCaptor<LsLabelVersion> targets = ArgumentCaptor.forClass(LsLabelVersion.class);
        verify(versionService, org.mockito.Mockito.times(2))
                .rollbackToSnapshot(any(), any(), targets.capture(), any());
        assertThat(targets.getAllValues()).extracting(LsLabelVersion::getLabelVersionSn)
                .containsExactly(1003L, 1002L);
        assertThat(result.appliedFrames()).isEqualTo(2);
        assertThat(result.unresolvedFrames()).isZero();
    }

    @Test
    @DisplayName("롤백된_회차는_그_회차의_실제_내용으로_되돌린다 — 판정_원천은_매핑이지_VER_NO_가_아니다")
    void 롤백된_회차는_그_회차의_실제_내용으로_되돌린다() {
        // given — v1 내용 A(1001) / v2 내용 B(1002) / v3 은 롤백으로 다시 A(1001) 가 내용이 됐다.
        //   1002 는 비활성이지만 VER_NO=2 를 단 채 남아 있어, 번호 기반 규칙은 이것을 골랐다
        //   (= v3 에 존재한 적 없는 내용으로 되돌리는 조용한 오복원).
        stubGatesOpen();
        stubVersionExists(3);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 3)).thenReturn(List.of(
                new SnapshotVersionRef(SRC_A, 1, 1001L),
                new SnapshotVersionRef(SRC_A, 2, 1002L),
                new SnapshotVersionRef(SRC_A, 3, 1001L)));
        stubSnapshot(1001L, SRC_A, PAYLOAD_LEGACY, 1);
        stubDiscardApply();

        service.applyStartVersion(RAW_SN, 3, reviewer);

        ArgumentCaptor<LsLabelVersion> target = ArgumentCaptor.forClass(LsLabelVersion.class);
        verify(versionService).rollbackToSnapshot(any(), any(), target.capture(), any());
        assertThat(target.getValue().getLabelVersionSn())
                .as("v3 의 내용은 1001 이다 — 그 사이 회차의 비활성 스냅샷(1002)이면 안 된다")
                .isEqualTo(1001L);
        verify(labelVersionRepository, never()).findById(1002L);
    }

    @Test
    @DisplayName("요청회차_이하_매핑이_없는_프레임은_건드리지_않고_미해결로_집계한다")
    void 요청회차_이하_매핑이_없는_프레임은_건드리지_않고_미해결로_집계한다() {
        stubGatesOpen();
        stubVersionExists(2);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_C, 2)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 2))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_A, 2, 1003L)));
        stubSnapshot(1003L, SRC_A, PAYLOAD_DISCARDED, 2);
        stubDiscardApply();

        StartVersionApplyResult result = service.applyStartVersion(RAW_SN, 2, reviewer);

        assertThat(result.totalFrames()).isEqualTo(2);
        assertThat(result.appliedFrames()).isEqualTo(1);
        assertThat(result.unresolvedFrames()).isEqualTo(1);
        // 미해결 프레임은 라벨도 폐기여부도 건드리지 않는다(추측으로 지우지 않는다).
        verify(frameDiscardApplier, never()).apply(eq(SRC_C), any(), any(), any());
    }

    @Test
    @DisplayName("회차번호가_결측인_참조는_후보에서_제외한다")
    void 회차번호가_결측인_참조는_후보에서_제외한다() {
        stubGatesOpen();
        stubVersionExists(2);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 2)).thenReturn(List.of(
                new SnapshotVersionRef(SRC_A, null, 9999L),
                new SnapshotVersionRef(SRC_A, 1, 1001L)));
        stubSnapshot(1001L, SRC_A, PAYLOAD_LEGACY, 1);
        stubDiscardApply();

        service.applyStartVersion(RAW_SN, 2, reviewer);

        ArgumentCaptor<LsLabelVersion> targets = ArgumentCaptor.forClass(LsLabelVersion.class);
        verify(versionService).rollbackToSnapshot(any(), any(), targets.capture(), any());
        assertThat(targets.getValue().getLabelVersionSn()).isEqualTo(1001L);
    }

    // ---------- D5 폐기 상태 복원 ----------

    @Test
    @DisplayName("스냅샷의_폐기여부를_프레임에_되돌린다")
    void 스냅샷의_폐기여부를_프레임에_되돌린다() {
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_A, 1, 1001L)));
        stubSnapshot(1001L, SRC_A, PAYLOAD_DISCARDED, 1);
        when(frameDiscardApplier.apply(eq(SRC_A), any(), eq("Y"), any()))
                .thenReturn(FrameDiscardApplier.Outcome.DISCARDED);

        StartVersionApplyResult result = service.applyStartVersion(RAW_SN, 1, reviewer);

        verify(frameDiscardApplier).apply(eq(SRC_A), any(), eq("Y"), eq(1L));
        assertThat(result.discardedFrames()).isEqualTo(1);
    }

    @Test
    @DisplayName("폐기여부가_없는_옛_스냅샷은_폐기_아님으로_복원한다")
    void 폐기여부가_없는_옛_스냅샷은_폐기_아님으로_복원한다() {
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_B, 1)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_B, 1, 1002L)));
        stubSnapshot(1002L, SRC_B, PAYLOAD_LEGACY, 1);
        when(frameDiscardApplier.apply(eq(SRC_B), any(), eq("N"), any()))
                .thenReturn(FrameDiscardApplier.Outcome.RESTORED);

        StartVersionApplyResult result = service.applyStartVersion(RAW_SN, 1, reviewer);

        verify(frameDiscardApplier).apply(eq(SRC_B), any(), eq("N"), eq(1L));
        assertThat(result.revivedFrames()).isEqualTo(1);
    }

    @Test
    @DisplayName("라벨_복원이_먼저_실행되고_그_다음_폐기여부가_적용된다")
    void 라벨_복원이_먼저_실행되고_그_다음_폐기여부가_적용된다() {
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_A, 1, 1001L)));
        stubSnapshot(1001L, SRC_A, PAYLOAD_DISCARDED, 1);
        stubDiscardApply();

        service.applyStartVersion(RAW_SN, 1, reviewer);

        // 폐기 적용은 프레임 행 락을 이미 보유한 상태여야 한다 — 그 락은 롤백 코어가 잡는다.
        InOrder order = inOrder(versionService, frameDiscardApplier);
        order.verify(versionService).rollbackToSnapshot(any(), any(), any(), any());
        order.verify(frameDiscardApplier).apply(eq(SRC_A), any(), any(), any());
    }

    @Test
    @DisplayName("승인된_영상에서_폐기여부만_바뀌어도_재산출_통지를_발행한다")
    void 승인된_영상에서_폐기여부만_바뀌어도_재산출_통지를_발행한다() {
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_B, 1)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_B, 1, 1002L)));
        stubSnapshot(1002L, SRC_B, PAYLOAD_LEGACY, 1);
        when(frameDiscardApplier.apply(eq(SRC_B), any(), eq("N"), any()))
                .thenReturn(FrameDiscardApplier.Outcome.RESTORED);
        when(approvalGate.isApproved(RAW_SN)).thenReturn(true);

        service.applyStartVersion(RAW_SN, 1, reviewer);

        ArgumentCaptor<TaskModifiedEvent> event = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().changeType()).isEqualTo(ChangeType.FRAME_RESTORED);
        assertThat(event.getValue().exportRegenerated()).isTrue();
        assertThat(event.getValue().needsRecheck()).isTrue();
    }

    @Test
    @DisplayName("폐기여부가_그대로면_통지를_발행하지_않는다")
    void 폐기여부가_그대로면_통지를_발행하지_않는다() {
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_B, 1)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_B, 1, 1002L)));
        stubSnapshot(1002L, SRC_B, PAYLOAD_LEGACY, 1);
        stubDiscardApply();

        service.applyStartVersion(RAW_SN, 1, reviewer);

        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    // ---------- 신고 재판정 (CWE-367) ----------

    @Test
    @DisplayName("루프가_도는_동안_신고가_열리면_커밋_직전_재판정에서_412_로_전체를_되돌린다")
    void 루프가_도는_동안_신고가_열리면_재판정에서_412() {
        // given — 진입부에서는 통과했는데(1회차 doNothing) 프레임 순회 중 비식별 배치 실패 경로가
        //   작업락 없이 DE_IDNTF_YN='F' 를 커밋한 상황(2회차 throw).
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_A, 1, 1001L)));
        stubSnapshot(1001L, SRC_A, PAYLOAD_LEGACY, 1);
        stubDiscardApply();
        doNothing().doThrow(new CustomException(
                        ErrorCode.PRECONDITION_FAILED, "비식별 재처리 대기 중인 영상입니다."))
                .when(accessGuard).requireNotUnderDeidentReport(RAW_SN);

        // then — 예외가 전파되어 트랜잭션 전체(앞 프레임의 복원 포함)가 롤백된다.
        assertThatThrownBy(() -> service.applyStartVersion(RAW_SN, 1, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PRECONDITION_FAILED);
        verify(accessGuard, org.mockito.Mockito.times(2)).requireNotUnderDeidentReport(RAW_SN);
        // 롤백될 트랜잭션이므로 감사 이력도 남기지 않는다.
        verify(taskEventLogRepository, never()).save(any());
    }

    // ---------- 감사 (CWE-778) ----------

    @Test
    @DisplayName("누가_어느_회차를_골랐는지_영상_단위_감사_이력으로_남긴다")
    void 누가_어느_회차를_골랐는지_감사_이력으로_남긴다() {
        stubGatesOpen();
        stubVersionExists(3);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_A, 0)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 3))
                .thenReturn(List.of(new SnapshotVersionRef(SRC_A, 3, 1003L)));
        stubSnapshot(1003L, SRC_A, PAYLOAD_LEGACY, 3);
        stubDiscardApply();

        service.applyStartVersion(RAW_SN, 3, reviewer);

        ArgumentCaptor<LsTaskEventLog> log = ArgumentCaptor.forClass(LsTaskEventLog.class);
        verify(taskEventLogRepository).save(log.capture());
        assertThat(log.getValue().getEventTypeCd())
                .isEqualTo(LsTaskEventLog.EVENT_START_VERSION_APPLY);
        assertThat(log.getValue().getRawDataId()).isEqualTo(RAW_SN);
        assertThat(log.getValue().getActorUserNo()).isEqualTo(1L);
        assertThat(log.getValue().getRsn()).isEqualTo("versionNo=3");
    }

    @Test
    @DisplayName("전_프레임이_되돌릴_대상이_없어도_감사_이력은_남는다 — 흔적_0건_방지")
    void 전_프레임이_미해결이어도_감사_이력은_남는다() {
        stubGatesOpen();
        stubVersionExists(2);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN)).thenReturn(List.of(frame(SRC_C, 2)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 2)).thenReturn(List.of());

        StartVersionApplyResult result = service.applyStartVersion(RAW_SN, 2, reviewer);

        assertThat(result.unresolvedFrames()).isEqualTo(1);
        verify(taskEventLogRepository).save(any(LsTaskEventLog.class));
    }

    // ---------- 부분 실패 ----------

    @Test
    @DisplayName("중간_프레임에서_실패하면_예외가_전파되고_뒤_프레임은_처리하지_않는다")
    void 중간_프레임에서_실패하면_예외가_전파되고_뒤_프레임은_처리하지_않는다() {
        stubGatesOpen();
        stubVersionExists(1);
        when(srcRepository.findByRawSnOrderByFrameNoAsc(RAW_SN))
                .thenReturn(List.of(frame(SRC_A, 0), frame(SRC_B, 1)));
        when(outputVerSnpshRepository.findRefsUpTo(RAW_SN, 1)).thenReturn(List.of(
                new SnapshotVersionRef(SRC_A, 1, 1001L),
                new SnapshotVersionRef(SRC_B, 1, 1002L)));
        stubSnapshot(1001L, SRC_A, PAYLOAD_DISCARDED, 1);
        doThrow(new CustomException(ErrorCode.INVALID_INPUT, "손상된 버전 스냅샷이라 롤백할 수 없습니다."))
                .when(versionService).rollbackToSnapshot(any(), any(), any(), any());

        assertThatThrownBy(() -> service.applyStartVersion(RAW_SN, 1, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        // 첫 프레임에서 터졌으므로 두 번째 스냅샷은 조회조차 하지 않는다(전체 트랜잭션 롤백 전제).
        verify(labelVersionRepository, never()).findById(1002L);
        verify(frameDiscardApplier, never()).apply(any(), any(), any(), any());
    }

    @Test
    @DisplayName("다른_프레임의_스냅샷은_롤백_코어가_거부한다")
    void 다른_프레임의_스냅샷은_롤백_코어가_거부한다() {
        // given — 해석 결과가 어떤 이유로든 다른 프레임의 스냅샷을 가리키는 상황(데이터 오염 방어).
        LsDataSrc frame = frame(SRC_A, 0);
        LsLabelVersion foreign = snapshot(1002L, SRC_B, PAYLOAD_LEGACY, 1);

        assertThatThrownBy(() -> versionService().rollbackToSnapshot(raw(), frame, foreign, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    /** 코어 가드 검증용 — 협력자는 쓰이지 않는다(진입부에서 거부되므로). */
    private VersionService versionService() {
        return new VersionService(labelVersionRepository, accessGuard, videoRepository,
                workLockService, srcRepository, org.mockito.Mockito.mock(
                        kr.co.cudo.authoring.batch.repository.LsDataLblRepository.class),
                new ObjectMapper(), eventPublisher, approvalGate,
                org.mockito.Mockito.mock(
                        kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository.class),
                org.mockito.Mockito.mock(
                        kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository.class),
                org.mockito.Mockito.mock(
                        kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository.class),
                org.mockito.Mockito.mock(kr.co.cudo.authoring.user.service.UserNameResolver.class));
    }

    // ---------- 고정 스텁 ----------

    private void stubGatesOpen() {
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(labelVersionRepository.tryAcquireVideoVersionLock(anyInt(), anyInt())).thenReturn(true);
    }

    private void stubVersionExists(int versionNo) {
        stubVersionExistsOnly(versionNo);
        when(accessGuard.parseUserNo("1")).thenReturn(1L);
    }

    /** 프레임 상한 초과처럼 actor 해석 이전에 거부되는 경로용(불필요 스텁 방지). */
    private void stubVersionExistsOnly(int versionNo) {
        when(labelVersionRepository.existsByDataRawSnAndVersionNo(RAW_SN, versionNo)).thenReturn(true);
    }

    private void stubSnapshot(Long labelVersionSn, Long srcSn, String payload, int versionNo) {
        when(labelVersionRepository.findById(labelVersionSn))
                .thenReturn(Optional.of(snapshot(labelVersionSn, srcSn, payload, versionNo)));
    }

    private void stubDiscardApply() {
        when(frameDiscardApplier.apply(anyLong(), any(), any(), any()))
                .thenReturn(FrameDiscardApplier.Outcome.UNCHANGED);
    }

    private LsLabelVersion snapshot(Long labelVersionSn, Long srcSn, String payload, int versionNo) {
        LsLabelVersion v = LsLabelVersion.create(RAW_SN, srcSn, "h" + labelVersionSn, payload,
                versionNo, LsLabelVersion.SAVE_REASON_APPROVED, "1");
        ReflectionTestUtils.setField(v, "labelVersionSn", labelVersionSn);
        return v;
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
