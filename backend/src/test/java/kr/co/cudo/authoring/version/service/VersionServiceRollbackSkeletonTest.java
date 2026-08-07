package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.controlnotify.event.TaskModifiedEvent;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DEV_FIX-B(M2) — SKELETON(17-keypoint) 프레임의 롤백 정규화 단위 테스트.
 *
 * <p>구 구현은 SKELETON 만 스냅샷 원본 {@code points} JSON 문자열을 <b>정규화 없이</b> 복원값으로 썼다.
 * 스냅샷의 가시성 v 는 {@code LabelResponse} 가 {@code (double) kp.v()} 로 써서 {@code 2.0} 인데
 * DB {@code POINT_CN} 은 {@code KeypointSerializer} 가 정수 {@code 2} 로 쓰므로, 문자열 비교인 멱등 판정이
 * <b>영구 불일치</b>했다 → 같은 해시 롤백도 매번 교체 경로 → 이력 1건 + TASK_MODIFIED(exportRegenerated)
 * → export v{n+1} 전량 재생성(전 버전 보존이라 삭제도 없음) + {@code POINT_CN} 의 v 가 {@code 2.0} 으로 변질.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VersionServiceRollbackSkeletonTest {

    @Mock private LsLabelVersionRepository labelVersionRepository;
    @Mock private LabelAccessGuard accessGuard;
    @Mock private VideoRepository videoRepository;
    @Mock private WorkLockService workLockService;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository labelRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ReviewApprovalGate approvalGate;
    @Mock private LsDataLblAiInfoRepository aiInfoRepository;
    @Mock private LsDataLblAttrValRepository attrValRepository;
    @Mock private LsDataLblHstryRepository labelHistoryRepository;

    private VersionService versionService;
    private TokenClaims reviewer;

    private static final Long SRC_SN = 50L;
    private static final Long RAW_SN = 9L;
    private static final Long LBL_SN = 77L;

    /** DB 표현({@code KeypointSerializer.toJson}) — v 는 정수. */
    private static final String DB_POINTS = keypoints("2");
    /** 스냅샷 표현({@code LabelResponse}) — v 는 double. */
    private static final String SNAPSHOT_POINTS = keypoints("2.0");

    @BeforeEach
    void setUp() {
        versionService = new VersionService(
                labelVersionRepository, accessGuard, videoRepository, workLockService,
                srcRepository, labelRepository, new ObjectMapper(), eventPublisher,
                approvalGate, aiInfoRepository, attrValRepository, labelHistoryRepository,
                org.mockito.Mockito.mock(kr.co.cudo.authoring.user.service.UserNameResolver.class));
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    /** 17-keypoint 좌표 JSON — x,y 는 항상 double 표기, v 표기만 인자로 바꾼다. */
    private static String keypoints(String v) {
        StringJoiner sj = new StringJoiner(",", "[", "]");
        for (int i = 0; i < 17; i++) {
            sj.add("[" + (10.0 + i) + "," + (20.0 + i) + "," + v + "]");
        }
        return sj.toString();
    }

    private static String payload(String pointsJson) {
        String points = pointsJson == null ? "" : ",\"points\":" + pointsJson;
        return "{\"srcSn\":50,\"frameNo\":0,\"items\":[{\"id\":" + LBL_SN
                + ",\"lblTypeCd\":\"SKELETON\",\"label\":\"person\",\"labelId\":null" + points
                + ",\"autoLblYn\":null,\"confScore\":null,\"trackId\":null,\"lblSrcCd\":null}]}";
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private LsDataSrc src() {
        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "/raw/0.jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", SRC_SN);
        return src;
    }

    private LsDataRaw raw() {
        LsDataRaw raw = LsDataRaw.createFromIngest("CLIP-SKEL", "CCTV", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/clip.mp4", LocalDateTime.now(), 30);
        ReflectionTestUtils.setField(raw, "rawSn", RAW_SN);
        return raw;
    }

    /** DB 작업본 라벨 — SKELETON, 정규(DB) 좌표 표현. */
    private LsDataLbl dbLabel() {
        LsDataLbl l = LsDataLbl.createManual(SRC_SN, LsDataLbl.TYPE_SKELETON, null, "person", DB_POINTS, 1L);
        ReflectionTestUtils.setField(l, "lblSn", LBL_SN);
        return l;
    }

    /** 롤백 진입 stub — 대상 스냅샷이 곧 현재 active 인 멱등 후보 상황. */
    private String stubEntry(String snapshot) {
        String hash = sha256Hex(snapshot);
        LsLabelVersion active = LsLabelVersion.create(RAW_SN, SRC_SN, hash, snapshot, 1,
                LsLabelVersion.SAVE_REASON_APPROVED, "1");
        ReflectionTestUtils.setField(active, "labelVersionSn", 1L);
        when(accessGuard.verifyAndGet(eq(SRC_SN), any())).thenReturn(src());
        when(labelVersionRepository.findByDataSrcSnAndVersionHash(SRC_SN, hash)).thenReturn(Optional.of(active));
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(labelVersionRepository.findActiveForUpdate(eq(RAW_SN), eq(SRC_SN), any()))
                .thenReturn(List.of(active));
        when(srcRepository.lockAndReadLabelVersion(SRC_SN)).thenReturn(Optional.of(1L));
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(dbLabel()));
        when(approvalGate.isApproved(any())).thenReturn(false);
        return hash;
    }

    @Test
    @DisplayName("SKELETON_프레임의_멱등_롤백이_진짜_no_op_이다")
    void skeletonIdempotentRollbackIsTrueNoOp() {
        // given — 작업본(DB, v=2)과 스냅샷(v=2.0)은 표현만 다를 뿐 같은 키포인트다.
        String hash = stubEntry(payload(SNAPSHOT_POINTS));

        // when
        versionService.rollback(hash, SRC_SN, reviewer);

        // then — 교체·이력·통지가 모두 없어야 한다(구 구현은 문자열 불일치로 전부 발생했다).
        verify(labelRepository, never()).deleteAllByIdInBatch(anyList());
        verify(labelRepository, never()).insertRestoredWithExplicitIds(any(), anyList());
        verify(labelRepository, never()).saveAll(anyList());
        verify(labelHistoryRepository, never()).save(any(LsDataLblHstry.class));
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("SKELETON_왕복_롤백_후_POINT_CN_의_v_가_변질되지_않는다")
    void skeletonRoundTripKeepsVisibilityRepresentation() {
        // given — 작업본이 드리프트(라벨명 상이)해 실제 교체 경로를 타게 한다.
        String snapshot = payload(SNAPSHOT_POINTS);
        String hash = sha256Hex(snapshot);
        LsLabelVersion target = LsLabelVersion.create(RAW_SN, SRC_SN, hash, snapshot, 1,
                LsLabelVersion.SAVE_REASON_APPROVED, "1");
        when(accessGuard.verifyAndGet(eq(SRC_SN), any())).thenReturn(src());
        when(labelVersionRepository.findByDataSrcSnAndVersionHash(eq(SRC_SN), any()))
                .thenReturn(Optional.of(target));
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(labelVersionRepository.findActiveForUpdate(eq(RAW_SN), eq(SRC_SN), any())).thenReturn(List.of());
        when(srcRepository.lockAndReadLabelVersion(SRC_SN)).thenReturn(Optional.of(1L));
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of());
        when(labelRepository.insertRestoredWithExplicitIds(eq(SRC_SN), anyList())).thenReturn(java.util.Set.of(LBL_SN));
        when(approvalGate.isApproved(any())).thenReturn(false);

        // when
        versionService.rollback(hash, SRC_SN, reviewer);

        // then — DB 에 적재되는 좌표는 KeypointSerializer 정규형(v=정수)이어야 한다(2.0 변질 금지).
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<kr.co.cudo.authoring.batch.repository.LsDataLblRepositoryCustom.RestoreRow>>
                captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(labelRepository).insertRestoredWithExplicitIds(eq(SRC_SN), captor.capture());
        String storedPoints = captor.getValue().get(0).pointCn();
        assertThat(storedPoints).isEqualTo(DB_POINTS);
        assertThat(storedPoints).doesNotContain("2.0]");
    }

    @Test
    @DisplayName("points_키가_없는_SKELETON_스냅샷이_키포인트를_소실시키지_않는다")
    void skeletonSnapshotWithoutPointsIsRejected() {
        // given — points 키 자체가 없는 손상 스냅샷. MissingNode.toString() 은 "" 라 구 구현은
        //   POINT_CN="" 로 키포인트 17개를 <b>예외 없이</b> 소실시켰다.
        String snapshot = payload(null);
        String hash = sha256Hex(snapshot);
        LsLabelVersion target = LsLabelVersion.create(RAW_SN, SRC_SN, hash, snapshot, 1,
                LsLabelVersion.SAVE_REASON_APPROVED, "1");
        when(accessGuard.verifyAndGet(eq(SRC_SN), any())).thenReturn(src());
        when(labelVersionRepository.findByDataSrcSnAndVersionHash(eq(SRC_SN), any()))
                .thenReturn(Optional.of(target));
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(labelVersionRepository.findActiveForUpdate(eq(RAW_SN), eq(SRC_SN), any())).thenReturn(List.of());
        when(srcRepository.lockAndReadLabelVersion(SRC_SN)).thenReturn(Optional.of(1L));

        // when / then — 조용한 소실 대신 400 으로 명시 실패하고 라벨을 건드리지 않는다(fail-closed).
        assertThatThrownBy(() -> versionService.rollback(hash, SRC_SN, reviewer))
                .isInstanceOf(CustomException.class)
                .satisfies(e -> assertThat(((CustomException) e).getErrorCode())
                        .isEqualTo(ErrorCode.INVALID_INPUT));
        verify(labelRepository, never()).deleteAllByIdInBatch(anyList());
        verify(labelRepository, never()).insertRestoredWithExplicitIds(any(), anyList());
    }
}
