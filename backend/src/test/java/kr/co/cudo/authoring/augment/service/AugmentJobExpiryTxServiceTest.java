package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.observability.metrics.AugmentMetrics;
import kr.co.cudo.authoring.webhook.service.AugmentApplyResult;
import kr.co.cudo.authoring.webhook.service.AugmentJobRollup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AugmentJobExpiryTxService} — 만료 회수 <b>집계 시점</b> 단위 테스트 (Phase 8 DEV_FIX LOW-1).
 *
 * <p>구 구현은 클레임 직후(=커밋 전) {@code metrics.jobExpired()} 를 올렸다. 그런데 뒤이은 롤업이
 * 던지면 스윕이 예외를 잡고 트랜잭션은 롤백되는데 <b>카운터는 이미 올라가 있어</b>, 회수되지 않은
 * 건이 회수로 집계됐다(운영이 만료 추이를 오판하는 지표 오염).
 */
@MockitoSettings(strictness = Strictness.LENIENT)
@ExtendWith(MockitoExtension.class)
class AugmentJobExpiryTxServiceTest {

    private static final long AUG_JOB_SN = 11L;
    private static final long DATA_AUG_SN = 22L;

    @Mock private LsDataAugRepository augRepository;
    @Mock private LsDataAugJobRepository jobRepository;
    @Mock private kr.co.cudo.authoring.batch.repository.LsDataSrcRepository srcRepository;
    @Mock private AugmentJobRollup rollup;
    @Mock private kr.co.cudo.authoring.webhook.service.AugmentResultService augmentResultService;
    @Mock private kr.co.cudo.authoring.video.service.DeidentReportGate deidentReportGate;
    @Mock private AugmentMetrics metrics;

    private AugmentJobExpiryTxService service;

    @BeforeEach
    void setUp() {
        service = new AugmentJobExpiryTxService(augRepository, jobRepository, srcRepository,
                rollup, augmentResultService, deidentReportGate, metrics);
        given(augRepository.findByDataAugSnForUpdate(DATA_AUG_SN)).willReturn(Optional.of(
                LsDataAug.createRequested(1L, LsDataAug.AUG_WINTER, "1", "K-EXPIRY", null)));
        given(jobRepository.claimExpired(eq(AUG_JOB_SN), any(), anyString(), anyString(), any()))
                .willReturn(1);
        given(jobRepository.findByDataAugSnOrderByJobSeqAsc(anyLong())).willReturn(List.of());
        // 트랜잭션 동기화 활성 = 실제 @Transactional 실행 문맥 재현(afterCommit 등록이 가능해진다).
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("롤업_실패로_롤백되면_만료_카운터가_증가하지_않는다")
    void rolledBackExpiryDoesNotCountAsReclaimed() {
        // given: 클레임은 성공했지만 뒤이은 롤업이 터진다(스윕이 잡고 트랜잭션은 롤백된다).
        willThrow(new IllegalStateException("rollup failed"))
                .given(rollup).rollUpIfAllTerminal(anyLong(), any(), any());

        // when
        assertThatThrownBy(() -> service.expire(AUG_JOB_SN, DATA_AUG_SN, LocalDateTime.now()))
                .isInstanceOf(IllegalStateException.class);

        // then: 커밋되지 않았으므로 집계도 없다.
        verify(metrics, never()).jobExpired();

        // 롤백 완료 콜백까지 태워도 카운터는 그대로다(afterCommit 은 호출되지 않는다).
        TransactionSynchronizationUtils.triggerAfterCompletion(
                TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(metrics, never()).jobExpired();
    }

    @Test
    @DisplayName("만료_회수는_커밋_이후에만_집계된다")
    void expiryIsCountedOnlyAfterCommit() {
        // given
        given(rollup.rollUpIfAllTerminal(anyLong(), any(), any()))
                .willReturn(AugmentApplyResult.APPLIED);

        // when
        boolean claimed = service.expire(AUG_JOB_SN, DATA_AUG_SN, LocalDateTime.now());

        // then: 메서드 반환 시점(=커밋 전)에는 아직 집계되지 않는다.
        assertThat(claimed).isTrue();
        verify(metrics, never()).jobExpired();

        // 커밋 콜백이 태워질 때 비로소 1 증가한다.
        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(metrics).jobExpired();
    }

    @Test
    @DisplayName("클레임에_실패하면_만료_카운터가_증가하지_않는다")
    void failedClaimIsNotCounted() {
        // given: 다른 노드가 선점했거나 그 사이 웹훅이 정상 종결시켰다.
        given(jobRepository.claimExpired(eq(AUG_JOB_SN), any(), anyString(), anyString(), any()))
                .willReturn(0);

        // when
        boolean claimed = service.expire(AUG_JOB_SN, DATA_AUG_SN, LocalDateTime.now());

        // then
        assertThat(claimed).isFalse();
        TransactionSynchronizationUtils.triggerAfterCommit();
        verify(metrics, never()).jobExpired();
    }

    @Test
    @DisplayName("트랜잭션_밖_직접_호출이면_즉시_집계된다")
    void countsImmediatelyWithoutTransactionSynchronization() {
        // given: 동기화 미활성(트랜잭션 밖 직접 호출) — 등록할 커밋 콜백이 없다.
        TransactionSynchronizationManager.clearSynchronization();
        given(rollup.rollUpIfAllTerminal(anyLong(), any(), any()))
                .willReturn(AugmentApplyResult.APPLIED);

        // when
        service.expire(AUG_JOB_SN, DATA_AUG_SN, LocalDateTime.now());

        // then: 지연시킬 커밋 시점이 없으므로 집계를 유실하지 않는다.
        verify(metrics).jobExpired();
    }

    /** 만료 사유 문자열은 내부 경로·식별정보를 담지 않는다(CWE-209/359 회귀 가드). */
    @Test
    @DisplayName("만료_사유에_내부_경로나_식별정보가_들어가지_않는다")
    void expiryReasonHasNoInternalDetails() {
        given(rollup.rollUpIfAllTerminal(anyLong(), any(), any()))
                .willReturn(AugmentApplyResult.APPLIED);

        service.expire(AUG_JOB_SN, DATA_AUG_SN, LocalDateTime.now());

        org.mockito.ArgumentCaptor<String> reason = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jobRepository).claimExpired(eq(AUG_JOB_SN), any(), eq(LsDataAugJob.ERR_EXPIRED),
                reason.capture(), any());
        assertThat(reason.getValue())
                .doesNotContain("/")
                .doesNotContain(String.valueOf(DATA_AUG_SN));
    }
}
