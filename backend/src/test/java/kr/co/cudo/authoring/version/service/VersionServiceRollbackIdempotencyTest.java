package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblAiInfoRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 멱등 롤백 no-op(D-ISSUE-24) + 5C 통지 회귀 방지(S6) + 해시 계산 독립성(S12) 단위 테스트.
 *
 * <p>라벨 교체 여부·통지 발행 여부는 호출 유무로 판정해야 하므로 Mockito 로 검증한다
 * (실 DB 동작은 {@code VersionRollbackHistoryIT} 가 Testcontainers 로 확인).
 */
@ExtendWith(MockitoExtension.class)
class VersionServiceRollbackIdempotencyTest {

    @Mock private LsLabelVersionRepository labelVersionRepository;
    @Mock private LabelAccessGuard accessGuard;
    @Mock private VideoRepository videoRepository;
    @Mock private WorkLockService workLockService;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository labelRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private LsRawDataStatusRepository rawDataStatusRepository;
    @Mock private LsDataLblAiInfoRepository aiInfoRepository;
    @Mock private LsDataLblAttrValRepository attrValRepository;
    @Mock private LsDataLblHstryRepository labelHistoryRepository;

    private VersionService versionService;

    private static final Long SRC_SN = 50L;
    private static final Long RAW_SN = 9L;
    private static final String SNAPSHOT = """
            {"srcSn":50,"frameNo":0,"items":[\
            {"id":1,"lblTypeCd":"BBOX","label":"person","labelId":null,"points":[[0,0],[10,10]]}]}""";
    private static final String SNAPSHOT_HASH = sha256Hex(SNAPSHOT);

    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        versionService = new VersionService(
                labelVersionRepository, accessGuard, videoRepository, workLockService,
                srcRepository, labelRepository, new ObjectMapper(), eventPublisher,
                rawDataStatusRepository, aiInfoRepository, attrValRepository, labelHistoryRepository,
                org.mockito.Mockito.mock(kr.co.cudo.authoring.user.service.UserNameResolver.class));
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
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
        LsDataRaw raw = LsDataRaw.createFromIngest("CLIP-IDEM", "CCTV", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/clip.mp4", LocalDateTime.now(), 30);
        ReflectionTestUtils.setField(raw, "rawSn", RAW_SN);
        return raw;
    }

    private LsLabelVersion version(String hash, boolean active) {
        LsLabelVersion v = LsLabelVersion.create(RAW_SN, SRC_SN, hash, SNAPSHOT, 1,
                LsLabelVersion.SAVE_REASON_APPROVED, "1");
        if (!active) {
            v.deactivate();
        }
        return v;
    }

    /** 롤백 진입 공통 stub — active 목록만 시나리오별로 달라진다. */
    private LsLabelVersion stubEntry(List<LsLabelVersion> actives) {
        LsLabelVersion target = actives.stream()
                .filter(v -> SNAPSHOT_HASH.equals(v.getVersionHash()))
                .findFirst()
                .orElseGet(() -> version(SNAPSHOT_HASH, false));
        when(accessGuard.verifyAndGet(eq(SRC_SN), any())).thenReturn(src());
        when(labelVersionRepository.findByDataSrcSnAndVersionHash(SRC_SN, SNAPSHOT_HASH))
                .thenReturn(Optional.of(target));
        when(videoRepository.findById(RAW_SN)).thenReturn(Optional.of(raw()));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(labelVersionRepository.findActiveForUpdate(eq(RAW_SN), eq(SRC_SN), any()))
                .thenReturn(actives);
        // DEV_FIX-B(H2) — 멱등 판정 전에 프레임 행 락(FOR UPDATE)을 취득한다.
        when(srcRepository.lockAndReadLabelVersion(SRC_SN)).thenReturn(Optional.of(1L));
        return target;
    }

    /** 실질 변경 경로(현재 active 가 다른 스냅샷) 진입 — 라벨 교체까지 수행되도록 stub. */
    private void stubLabelReplace() {
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of());
        when(labelRepository.insertRestoredWithExplicitIds(eq(SRC_SN), anyList()))
                .thenReturn(Set.of(1L));
    }

    private void stubApproved() {
        LsRawDataStatus status = org.mockito.Mockito.mock(LsRawDataStatus.class);
        when(status.getDataSttsCd()).thenReturn(LsRawDataStatus.STTS_APPROVED);
        when(rawDataStatusRepository.findByRawDataIdIn(anyList())).thenReturn(List.of(status));
        when(accessGuard.parseUserNo("1")).thenReturn(1L);
    }

    // ---------- D-24 / S6 ----------

    @Test
    @DisplayName("멱등_롤백에서는_exportRegenerated_통지가_발행되지_않는다")
    void idempotentRollbackPublishesNoNotification() {
        // given — 현재 active 가 이미 롤백 대상 스냅샷이고, 작업본 라벨도 그 스냅샷과 동일.
        LsLabelVersion active = version(SNAPSHOT_HASH, true);
        stubEntry(List.of(active));
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(restoredLabel()));

        // when
        LsLabelVersion result = versionService.rollback(SNAPSHOT_HASH, SRC_SN, reviewer);

        // then — 라벨 교체·이력·통지 모두 없음(진짜 no-op).
        assertThat(result).isSameAs(active);
        verify(labelRepository, never()).deleteAllByIdInBatch(anyList());
        verify(labelRepository, never()).insertRestoredWithExplicitIds(any(), anyList());
        verify(labelRepository, never()).saveAll(anyList());
        verify(labelHistoryRepository, never()).save(any(LsDataLblHstry.class));
        verify(eventPublisher, never()).publishEvent(any(TaskModifiedEvent.class));
    }

    @Test
    @DisplayName("실질_변경이_있는_롤백에서는_exportRegenerated_통지가_발행된다")
    void changingRollbackPublishesExportRegeneratedNotification() {
        // given — 현재 active 는 다른 스냅샷(실질 변경) + 검수 완료(APPROVED) 영상.
        stubEntry(List.of(version("0000000000000000000000000000000000000000", true)));
        stubLabelReplace();
        stubApproved();

        // when
        versionService.rollback(SNAPSHOT_HASH, SRC_SN, reviewer);

        // then — export 전량 재생성을 동반하는 TASK_MODIFIED 통지가 발행된다(5C 회귀 방지).
        ArgumentCaptor<TaskModifiedEvent> captor = ArgumentCaptor.forClass(TaskModifiedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().exportRegenerated()).isTrue();
        assertThat(captor.getValue().rawSn()).isEqualTo(RAW_SN);
        assertThat(captor.getValue().srcSn()).isEqualTo(SRC_SN);
        // 롤백 이력(D-21)도 함께 기록된다.
        verify(labelHistoryRepository).save(any(LsDataLblHstry.class));
    }

    // ---------- S12: 해시 계산 독립성 ----------

    @Test
    @DisplayName("newHash_계산이_라벨교체_유무와_무관하게_동일하다_교체_스킵_경로")
    void hashIsSnapshotDerivedOnSkipPath() {
        LsLabelVersion active = version(SNAPSHOT_HASH, true);
        stubEntry(List.of(active));
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(restoredLabel()));

        LsLabelVersion result = versionService.rollback(SNAPSHOT_HASH, SRC_SN, reviewer);

        // 라벨 교체를 건너뛴 경로에서도 판정 기준 해시는 스냅샷 payload 의 SHA-256 이다.
        assertThat(result.getVersionHash()).isEqualTo(SNAPSHOT_HASH);
        assertThat(SNAPSHOT_HASH).isEqualTo(sha256Hex(result.getLabelPayload()));
        verify(labelRepository, never()).insertRestoredWithExplicitIds(any(), anyList());
    }

    @Test
    @DisplayName("newHash_계산이_라벨교체_유무와_무관하게_동일하다_실제_교체_경로")
    void hashIsSnapshotDerivedOnReplacePath() {
        LsLabelVersion target = stubEntry(List.of(version("1111111111111111111111111111111111111111", true)));
        stubLabelReplace();
        when(rawDataStatusRepository.findByRawDataIdIn(anyList())).thenReturn(List.of());

        LsLabelVersion result = versionService.rollback(SNAPSHOT_HASH, SRC_SN, reviewer);

        // 라벨을 실제 교체한 경로에서도 동일 해시로 대상 버전이 active 전환된다.
        assertThat(result).isSameAs(target);
        assertThat(result.getVersionHash()).isEqualTo(SNAPSHOT_HASH);
        assertThat(result.getActiveYn()).isEqualTo(LsLabelVersion.ACTIVE_YES);
        verify(labelRepository).insertRestoredWithExplicitIds(eq(SRC_SN), anyList());
    }

    /** 롤백 대상이 이미 삭제/미존재인 라벨 세트여도 이력 1건은 반드시 남는다(감사 추적). */
    @Test
    @DisplayName("롤백_이력은_라벨_델타가_없어도_기록된다")
    void rollbackHistoryRecordedEvenWithoutLabelDelta() {
        stubEntry(List.of(version("2222222222222222222222222222222222222222", true)));
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(restoredLabel()));
        when(labelRepository.insertRestoredWithExplicitIds(eq(SRC_SN), anyList()))
                .thenReturn(Set.of(1L));
        when(rawDataStatusRepository.findByRawDataIdIn(anyList())).thenReturn(List.of());

        versionService.rollback(SNAPSHOT_HASH, SRC_SN, reviewer);

        verify(labelHistoryRepository).save(any(LsDataLblHstry.class));
    }

    // ---------- DEV_FIX-B H2 / M1 ----------

    @Test
    @DisplayName("멱등_판정_전에_프레임_락을_취득한다")
    void frameLockAcquiredBeforeIdempotencyDecision() {
        // given — 멱등(조기 반환) 경로. 구 구현은 이 경로에서 프레임 락을 <b>전혀</b> 잡지 않아
        //   경쟁자(LabelService.bulkUpsert, 프레임 락만 취득)와 공유 락이 없었다(H2).
        LsLabelVersion active = version(SNAPSHOT_HASH, true);
        stubEntry(List.of(active));
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(restoredLabel()));

        // when
        versionService.rollback(SNAPSHOT_HASH, SRC_SN, reviewer);

        // then — 프레임 행 FOR UPDATE 가 라벨 조회(멱등 판정 frameLabelsMatch) 보다 먼저 수행된다.
        //   락 순서 규약(VERSION → SRC → LBL)도 함께 확인한다.
        InOrder order = inOrder(labelVersionRepository, srcRepository, labelRepository);
        order.verify(labelVersionRepository).findActiveForUpdate(eq(RAW_SN), eq(SRC_SN), any());
        order.verify(srcRepository).lockAndReadLabelVersion(SRC_SN);
        order.verify(labelRepository).findBySrcSn(SRC_SN);
    }

    @Test
    @DisplayName("조기_반환시에도_잉여_ACTIVE_가_정리된다")
    void earlyReturnAlsoDeactivatesSurplusActiveRows() {
        // given — active 가 2건(정본 + 잉여). 정본은 대상 스냅샷과 동일하여 조기 반환 경로로 진입한다.
        LsLabelVersion canonical = version(SNAPSHOT_HASH, true);
        LsLabelVersion surplus = version("3333333333333333333333333333333333333333", true);
        // 실 DB 행처럼 서로 다른 PK 를 부여한다(정본 판별 축).
        ReflectionTestUtils.setField(canonical, "labelVersionSn", 1L);
        ReflectionTestUtils.setField(surplus, "labelVersionSn", 2L);
        stubEntry(List.of(canonical, surplus));
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(restoredLabel()));

        // when
        LsLabelVersion result = versionService.rollback(SNAPSHOT_HASH, SRC_SN, reviewer);

        // then — 정본은 active 유지, 잉여 active 는 비활성화(자기치유). 라벨은 그대로 no-op.
        assertThat(result).isSameAs(canonical);
        assertThat(canonical.getActiveYn()).isEqualTo(LsLabelVersion.ACTIVE_YES);
        assertThat(surplus.getActiveYn()).isEqualTo(LsLabelVersion.ACTIVE_NO);
        verify(labelRepository, never()).deleteAllByIdInBatch(anyList());
        verify(labelHistoryRepository, never()).save(any(LsDataLblHstry.class));
    }

    /** 스냅샷과 동일 내용/동일 LBL_SN 인 기존 라벨 — 델타 0건 상황 재현용. */
    private LsDataLbl restoredLabel() {
        LsDataLbl l = LsDataLbl.createManual(SRC_SN, LsDataLbl.TYPE_BBOX, null, "person",
                "[[0.0,0.0],[10.0,10.0]]", 1L);
        ReflectionTestUtils.setField(l, "lblSn", 1L);
        return l;
    }
}
