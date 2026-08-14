package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.service.ReviewApprovalGate;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepositoryCustom;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.repository.LsDataLblAttrValRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
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
 * HIGH 1+2 회귀 가드 (CWE-362) — rollback 의 비관적 잠금 획득이 라벨 교체(DELETE+INSERT)보다
 * <b>먼저</b> 일어나는지(InOrder), 라벨 복원이 낱건 save 가 아닌 일괄 경로인지 검증한다.
 *
 * <p>D-ISSUE-22/23 보강 — 복원이 <b>스냅샷의 LBL_SN 을 명시 지정</b>하는 경로
 * ({@code insertRestoredWithExplicitIds}) 를 사용하는지, PK 충돌 시 전체 실패 없이 신규 발급으로
 * 폴백하는지, 삭제가 고아 방지 순서(ATTR_VAL → AI_INFO → LBL)를 지키는지 함께 단언한다.
 *
 * <p>Mockito 단위 테스트로 호출 순서/배치 호출만 단언한다(DB 미사용 — 실제 명시 PK·시퀀스·FK 동작은
 * {@code VersionRollbackRestoreIT} 가 Testcontainers 로 검증).
 */
@ExtendWith(MockitoExtension.class)
class VersionServiceRollbackLockOrderTest {

    @Mock private LsLabelVersionRepository labelVersionRepository;
    @Mock private LabelAccessGuard accessGuard;
    @Mock private VideoRepository videoRepository;
    @Mock private WorkLockService workLockService;
    @Mock private LsDataSrcRepository srcRepository;
    @Mock private LsDataLblRepository labelRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ReviewApprovalGate approvalGate;
    @Mock private LsDataLblAttrValRepository attrValRepository;
    @Mock private kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository labelHistoryRepository;

    private VersionService versionService;

    private static final Long SRC_SN = 50L;
    private static final Long RAW_SN = 9L;
    private static final Long EXISTING_LBL_SN = 99L;
    // commitApproved 스냅샷 형식(items[]) — 라벨 2건 복원(id = 옛 LBL_SN).
    private static final String SNAPSHOT = """
            {"srcSn":50,"frameNo":0,"items":[
              {"id":1,"lblTypeCd":"BBOX","label":"person","labelId":null,"points":[[0,0],[10,10]]},
              {"id":2,"lblTypeCd":"BBOX","label":"car","labelId":null,"points":[[5,5],[20,20]]}
            ]}""";

    private TokenClaims reviewer;

    @BeforeEach
    void setUp() {
        versionService = new VersionService(
                labelVersionRepository, accessGuard, videoRepository, workLockService,
                srcRepository, labelRepository, new ObjectMapper(), eventPublisher,
                approvalGate, attrValRepository, labelHistoryRepository,
                org.mockito.Mockito.mock(kr.co.cudo.authoring.user.service.UserNameResolver.class));
        reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(60));
    }

    private LsDataSrc src() {
        LsDataSrc src = LsDataSrc.create(RAW_SN, 0, "/raw/0.jpg", LocalDateTime.now());
        ReflectionTestUtils.setField(src, "srcSn", SRC_SN);
        return src;
    }

    private LsDataRaw raw() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-LOCK", "CCTV", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/raw/clip.mp4", LocalDateTime.now(), 30);
        ReflectionTestUtils.setField(raw, "rawSn", RAW_SN);
        return raw;
    }

    /** 롤백 진입에 필요한 공통 stub (라벨 교체 경로까지 도달). */
    private void stubRollbackFlow(List<LsDataLbl> existing) {
        LsDataSrc src = src();
        LsLabelVersion target = LsLabelVersion.create(
                RAW_SN, SRC_SN, "abc", SNAPSHOT, 1, LsLabelVersion.SAVE_REASON_APPROVED, "1");

        when(accessGuard.verifyAndGet(eq(SRC_SN), any())).thenReturn(src);
        when(labelVersionRepository.findByDataSrcSnAndVersionHash(eq(SRC_SN), eq("abc")))
                .thenReturn(java.util.Optional.of(target));
        when(videoRepository.findById(RAW_SN)).thenReturn(java.util.Optional.of(raw()));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        // 잠금 조회는 빈 active (멱등 미해당) → 라벨 교체 + 대상 버전 재활성 경로.
        when(labelVersionRepository.findActiveForUpdate(eq(RAW_SN), eq(SRC_SN), any()))
                .thenReturn(List.of());
        // DEV_FIX-B(H2) — 프레임 행 락(FOR UPDATE)을 멱등 판정 전에 취득한다.
        when(srcRepository.lockAndReadLabelVersion(SRC_SN)).thenReturn(java.util.Optional.of(1L));
        // 롤백 결과 해시(=sha256(SNAPSHOT))로는 기존 행이 없음 → 대상 행 자체를 재활성(D-ISSUE-21).
        when(labelVersionRepository.findByDataSrcSnAndVersionHash(eq(SRC_SN),
                org.mockito.ArgumentMatchers.argThat(h -> !"abc".equals(h))))
                .thenReturn(java.util.Optional.empty());
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(existing);
        when(approvalGate.isApproved(any())).thenReturn(false);
    }

    private LsDataLbl existingLabel() {
        LsDataLbl existing = LsDataLbl.createManual(SRC_SN, "BBOX", null, "old", "[]", 1L);
        ReflectionTestUtils.setField(existing, "lblSn", EXISTING_LBL_SN);
        return existing;
    }

    @Test
    @DisplayName("rollback_비관적_잠금_획득이_라벨_DELETE_보다_먼저_그리고_명시PK_일괄삽입_사용")
    void lockAcquiredBeforeLabelReplace_andExplicitIdBatchInsert() {
        stubRollbackFlow(List.of(existingLabel()));
        // 스냅샷의 두 LBL_SN 모두 명시 삽입 성공 → 폴백(saveAll) 불필요.
        when(labelRepository.insertRestoredWithExplicitIds(eq(SRC_SN), anyList()))
                .thenReturn(Set.of(1L, 2L));

        versionService.rollback("abc", SRC_SN, reviewer);

        // CWE-362 — 잠금(findActiveForUpdate) 이 라벨 교체(DELETE/INSERT) 보다 먼저 호출되어야 한다.
        //
        // D-ISSUE-21(3차) — 잠금 순서 규약(VERSION → SRC → LBL)을 유지하면서, <b>활성 버전 목록 조회를
        //   프레임 행 락(직렬화 앵커) 이후로</b> 옮겼다. 앵커 이전 값으로 판정하면 동시 롤백이 ACTIVE 를
        //   2건 남긴다(write skew). 따라서 순서는 VERSION 선취 → SRC 앵커 → VERSION <b>재조회</b> →
        //   라벨 DELETE → 명시 PK INSERT 여야 한다.
        InOrder order = inOrder(labelVersionRepository, srcRepository, labelRepository);
        order.verify(labelVersionRepository).findActiveForUpdate(eq(RAW_SN), eq(SRC_SN), any());
        order.verify(srcRepository).lockAndReadLabelVersion(SRC_SN);
        order.verify(labelVersionRepository).findActiveForUpdate(eq(RAW_SN), eq(SRC_SN), any());
        order.verify(labelRepository).deleteAllByIdInBatch(anyList());
        order.verify(labelRepository).insertRestoredWithExplicitIds(eq(SRC_SN), anyList());

        // 낱건 save 금지 + 명시 PK 삽입이 성공했으므로 재발급(saveAll) 경로를 타지 않는다.
        verify(labelRepository, never()).save(any());
        verify(labelRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("rollback_복원이_스냅샷의_LBL_SN_을_그대로_명시지정해_1회_배치삽입")
    void restoredLabelsInsertedWithSnapshotIdsInSingleBatch() {
        stubRollbackFlow(List.of());
        when(labelRepository.insertRestoredWithExplicitIds(eq(SRC_SN), anyList()))
                .thenReturn(Set.of(1L, 2L));

        versionService.rollback("abc", SRC_SN, reviewer);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLblRepositoryCustom.RestoreRow>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(labelRepository).insertRestoredWithExplicitIds(eq(SRC_SN), captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).extracting(LsDataLblRepositoryCustom.RestoreRow::lblSn)
                .containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("rollback_LBL_SN_충돌건만_신규발급_폴백_전체는_실패하지_않음")
    void conflictingIdFallsBackToNewIdWithoutFailingWholeRollback() {
        stubRollbackFlow(List.of());
        // id=1 만 삽입 성공, id=2 는 이미 점유(충돌) → 2건 중 1건만 폴백.
        when(labelRepository.insertRestoredWithExplicitIds(eq(SRC_SN), anyList()))
                .thenReturn(Set.of(1L));
        when(labelRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        LsLabelVersion result = versionService.rollback("abc", SRC_SN, reviewer);

        assertThat(result).isNotNull();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LsDataLbl>> captor = ArgumentCaptor.forClass(List.class);
        verify(labelRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).getLabelNm()).isEqualTo("car");
    }

    @Test
    @DisplayName("rollback_삭제가_고아방지_순서_ATTR_VAL_AI_INFO_LBL_로_수행")
    void deleteFollowsOrphanSafeOrder() {
        stubRollbackFlow(List.of(existingLabel()));
        when(labelRepository.insertRestoredWithExplicitIds(eq(SRC_SN), anyList()))
                .thenReturn(Set.of(1L, 2L));

        versionService.rollback("abc", SRC_SN, reviewer);

        InOrder order = inOrder(attrValRepository, labelRepository);
        order.verify(attrValRepository).deleteByLblSnIn(List.of(EXISTING_LBL_SN));
        // V6 — 스냅샷에 없는 기존 라벨(99)의 생산이력은 같은 행이라 라벨 삭제로 함께 사라진다
        //   (AI 메타 선삭제 단계가 없어졌고, 고아가 될 여지도 함께 사라졌다).
        order.verify(labelRepository).deleteAllByIdInBatch(List.of(EXISTING_LBL_SN));
    }
}
