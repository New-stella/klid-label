package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HIGH 1+2 회귀 가드 (CWE-362) — rollback 의 비관적 잠금 획득이 라벨 교체(DELETE+INSERT)보다
 * <b>먼저</b> 일어나는지(InOrder), 그리고 라벨 재생성이 낱건 save 가 아닌 saveAll 로 이뤄지는지를 검증한다.
 *
 * <p>Mockito 단위 테스트로 호출 순서/배치 호출만 단언한다(DB 미사용).
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
    @Mock private LsRawDataStatusRepository rawDataStatusRepository;

    private VersionService versionService;

    private static final Long SRC_SN = 50L;
    private static final Long RAW_SN = 9L;
    // commitApproved 스냅샷 형식(items[]) — 라벨 2건 복원.
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
                rawDataStatusRepository);
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

    @Test
    @DisplayName("rollback_비관적_잠금_획득이_라벨_DELETE_보다_먼저_그리고_saveAll_사용")
    void lockAcquiredBeforeLabelReplace_andSaveAll() {
        LsDataSrc src = src();
        LsDataRaw raw = raw();
        LsLabelVersion target = LsLabelVersion.create(
                RAW_SN, SRC_SN, "abc", SNAPSHOT, 1, LsLabelVersion.SAVE_REASON_ROLLBACK, "1");

        when(accessGuard.verifyAndGet(eq(SRC_SN), any())).thenReturn(src);
        when(labelVersionRepository.findByDataSrcSnAndVersionHash(eq(SRC_SN), eq("abc")))
                .thenReturn(java.util.Optional.of(target));
        when(videoRepository.findById(RAW_SN)).thenReturn(java.util.Optional.of(raw));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        // 잠금 조회는 빈 active (멱등 미해당) → 새 active INSERT 경로.
        when(labelVersionRepository.findActiveForUpdate(eq(RAW_SN), eq(SRC_SN), any()))
                .thenReturn(List.of());
        // 같은 해시 기존 버전 없음 → 신규 INSERT.
        when(labelVersionRepository.findByDataSrcSnAndVersionHash(eq(SRC_SN), org.mockito.ArgumentMatchers.argThat(h -> !"abc".equals(h))))
                .thenReturn(java.util.Optional.empty());
        // 기존 프레임 라벨 1건 → deleteAll 호출 유도.
        LsDataLbl existing = LsDataLbl.createManual(SRC_SN, "BBOX", null, "old", "[]", 1L);
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of(existing));
        when(labelVersionRepository.countByDataRawSnAndDataSrcSn(RAW_SN, SRC_SN)).thenReturn(0);
        when(labelVersionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rawDataStatusRepository.findByRawDataIdIn(anyList())).thenReturn(List.of());

        versionService.rollback("abc", SRC_SN, reviewer);

        // CWE-362 — 잠금(findActiveForUpdate) 이 라벨 교체(deleteAll/saveAll) 보다 먼저 호출되어야 한다.
        InOrder order = inOrder(labelVersionRepository, labelRepository);
        order.verify(labelVersionRepository).findActiveForUpdate(eq(RAW_SN), eq(SRC_SN), any());
        order.verify(labelRepository).deleteAll(anyList());
        order.verify(labelRepository).saveAll(anyList());

        // HIGH 2 — 낱건 save 가 아닌 saveAll 일괄 INSERT.
        verify(labelRepository).saveAll(anyList());
        verify(labelRepository, never()).save(any());
    }

    @Test
    @DisplayName("rollback_복원_라벨이_saveAll_단일호출로_2건_일괄_저장")
    void restoredLabelsSavedInSingleSaveAll() {
        LsDataSrc src = src();
        LsDataRaw raw = raw();
        LsLabelVersion target = LsLabelVersion.create(
                RAW_SN, SRC_SN, "abc", SNAPSHOT, 1, LsLabelVersion.SAVE_REASON_ROLLBACK, "1");

        when(accessGuard.verifyAndGet(eq(SRC_SN), any())).thenReturn(src);
        when(labelVersionRepository.findByDataSrcSnAndVersionHash(eq(SRC_SN), eq("abc")))
                .thenReturn(java.util.Optional.of(target));
        when(videoRepository.findById(RAW_SN)).thenReturn(java.util.Optional.of(raw));
        when(workLockService.isRawLocked(RAW_SN)).thenReturn(false);
        when(labelVersionRepository.findActiveForUpdate(eq(RAW_SN), eq(SRC_SN), any()))
                .thenReturn(List.of());
        when(labelVersionRepository.findByDataSrcSnAndVersionHash(eq(SRC_SN), org.mockito.ArgumentMatchers.argThat(h -> !"abc".equals(h))))
                .thenReturn(java.util.Optional.empty());
        when(labelRepository.findBySrcSn(SRC_SN)).thenReturn(List.of());
        when(labelVersionRepository.countByDataRawSnAndDataSrcSn(RAW_SN, SRC_SN)).thenReturn(0);
        when(labelVersionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(rawDataStatusRepository.findByRawDataIdIn(anyList())).thenReturn(List.of());

        versionService.rollback("abc", SRC_SN, reviewer);

        // 스냅샷 라벨 2건이 한 번의 saveAll 로 저장된다.
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<LsDataLbl>> captor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(labelRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }
}
