package kr.co.cudo.authoring.augment;

import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.service.AugmentJobExpirySweeper;
import kr.co.cudo.authoring.augment.service.AugmentJobExpiryTxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 증강 위탁 job <b>만료 스윕</b> 단위 테스트 (Phase 8-A).
 *
 * <p>검증 축: ①경과 임계로 후보를 고르고 만료 종결을 트리거한다 ②클레임 실패(다른 노드 선점)는
 * 중복 회수하지 않는다 ③예외를 삼켜 스케줄러 스레드가 죽지 않는다 ④토글이 꺼지면 스케줄러를
 * 기동하지 않는다 ⑤남의 스케줄러 활성원({@code @Scheduled})에 의존하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AugmentJobExpirySweeperTest {

    @Mock private LsDataAugJobRepository jobRepository;
    @Mock private LsDataAugRepository augRepository;
    @Mock private AugmentJobExpiryTxService txService;

    private AugmentJobExpirySweeper sweeper(boolean enabled) {
        return new AugmentJobExpirySweeper(jobRepository, augRepository, txService,
                enabled, 900_000L, 300_000L, 360, 50);
    }

    private static Object[] anchor(long augJobSn, long dataAugSn) {
        return new Object[]{augJobSn, dataAugSn};
    }

    @Test
    @DisplayName("임계시간_초과한_비종결_job_이_만료_종결된다")
    void expiresIdleNonTerminalJobs() {
        // given: 무갱신 경과 임계(360분)를 넘긴 비종결 job 2건
        when(jobRepository.findExpirableAnchors(any(), anyInt()))
                .thenReturn(List.<Object[]>of(anchor(11L, 1L), anchor(12L, 2L)));
        when(txService.expire(anyLong(), anyLong(), any())).thenReturn(true);

        // when
        int expired = sweeper(true).run();

        // then: 두 건 모두 만료 종결로 회수된다(후보 = (augJobSn, dataAugSn) 앵커)
        assertThat(expired).isEqualTo(2);
        verify(txService).expire(eq(11L), eq(1L), any());
        verify(txService).expire(eq(12L), eq(2L), any());
    }

    @Test
    @DisplayName("만료_임계는_설정된_무갱신_경과시간으로_계산된다")
    void cutoffIsDerivedFromIdleTimeout() {
        // given
        when(jobRepository.findExpirableAnchors(any(), anyInt())).thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now().minusMinutes(360);

        // when
        sweeper(true).run();

        // then: cutoff = now - idleTimeoutMinutes (그 이전부터 갱신이 멈춘 job 만 회수 대상)
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(jobRepository).findExpirableAnchors(cutoff.capture(), anyInt());
        assertThat(cutoff.getValue())
                .isAfterOrEqualTo(before)
                .isBefore(LocalDateTime.now().minusMinutes(359));
    }

    @Test
    @DisplayName("클레임에_실패한_job_은_중복_회수되지_않는다")
    void alreadyClaimedJobsAreNotCountedTwice() {
        // given: 다른 노드가 먼저 종결시켜 조건부 UPDATE 가 0행인 상황
        when(jobRepository.findExpirableAnchors(any(), anyInt())).thenReturn(List.<Object[]>of(anchor(11L, 1L)));
        when(txService.expire(anyLong(), anyLong(), any())).thenReturn(false);

        // when
        int expired = sweeper(true).run();

        // then
        assertThat(expired).isZero();
    }

    @Test
    @DisplayName("스윕_예외는_삼켜져_스케줄러_스레드가_죽지_않는다")
    void sweepSwallowsFailures() {
        // given
        when(jobRepository.findExpirableAnchors(any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        // when / then: 예외가 전파되면 scheduleWithFixedDelay 가 영구 정지한다
        assertThat(sweeper(true).run()).isZero();
    }

    // ─── 회수 축 ② — job 행 0건 장기 PENDING 증강 (적대검증 2차 MEDIUM-2) ───

    /**
     * job 축만 훑으면 <b>job 행 0건 PENDING</b> 증강은 어떤 회수기도 집지 못한다(깨울 주체가 없다 —
     * 보류 재개 리스너는 폐기됐다). 두 축이 같은 tick 에서 함께 돌아야 한다.
     */
    @Test
    @DisplayName("job_행_0건_장기_PENDING_증강이_회수된다")
    void reclaimsOrphanPendingAugments() {
        // given: job 축 후보는 없고, 고아 증강만 2건
        when(jobRepository.findExpirableAnchors(any(), anyInt())).thenReturn(List.of());
        when(augRepository.findOrphanPendingAugSns(any(), any(), any(), anyInt()))
                .thenReturn(List.of(51L, 52L));
        when(txService.expireOrphanPending(anyLong())).thenReturn(true);

        // when
        int reclaimed = sweeper(true).run();

        // then
        assertThat(reclaimed).isEqualTo(2);
        verify(txService).expireOrphanPending(51L);
        verify(txService).expireOrphanPending(52L);
    }

    /**
     * 후보 조건은 <b>외부 위탁 3종 · PENDING</b> 으로 고정된다.
     *
     * <p>★ 비식별 신고 구간 제외 술어는 2026-07-29 로 <b>제거</b>됐다 — 신고를 "정책 보류"로 보고
     * 건너뛰던 전제가 폐기됐고 재개 리스너도 삭제돼, 제외하면 깨울 주체 없는 PENDING 고착만 남는다.
     */
    @Test
    @DisplayName("고아_증강_후보는_외부증강_PENDING_조건으로만_조회된다(신고구간_제외_없음)")
    void orphanCandidateQueryUsesExternalPendingOnly() {
        when(jobRepository.findExpirableAnchors(any(), anyInt())).thenReturn(List.of());
        when(augRepository.findOrphanPendingAugSns(any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        sweeper(true).run();

        verify(augRepository).findOrphanPendingAugSns(
                eq(kr.co.cudo.authoring.augment.entity.LsDataAug.STTS_PENDING),
                eq(kr.co.cudo.authoring.augment.integration.AugmentPrompts.EXTERNAL_AUG_TYPES),
                any(), eq(50));
    }

    /** 한 축이 터져도 다른 축은 돌아야 한다 — 두 축을 한 try 로 묶으면 job 예외가 고아 회수를 통째로 막는다. */
    @Test
    @DisplayName("job_축_실패가_고아_증강_회수를_막지_않는다")
    void orphanAxisRunsEvenWhenJobAxisFails() {
        when(jobRepository.findExpirableAnchors(any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));
        when(augRepository.findOrphanPendingAugSns(any(), any(), any(), anyInt()))
                .thenReturn(List.of(51L));
        when(txService.expireOrphanPending(anyLong())).thenReturn(true);

        assertThat(sweeper(true).run()).isEqualTo(1);
    }

    @Test
    @DisplayName("스윕_토글이_꺼져있으면_스케줄러를_기동하지_않는다")
    void disabledToggleDoesNotStartScheduler() throws Exception {
        // given
        AugmentJobExpirySweeper disabled = sweeper(false);
        AugmentJobExpirySweeper enabled = sweeper(true);

        // when
        invokeStart(disabled);
        invokeStart(enabled);

        // then
        assertThat(disabled.isScheduled()).isFalse();
        assertThat(enabled.isScheduled()).isTrue();
        invokeStop(enabled);
        verify(txService, never()).expire(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("스윕이_남의_스케줄러_활성원에_의존하지_않는다")
    void sweeperDoesNotUseSharedSchedulingInfrastructure() {
        // given / when: 클래스가 선언한 스케줄 관련 애노테이션
        List<String> scheduled = java.util.Arrays.stream(AugmentJobExpirySweeper.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class))
                .map(Method::getName)
                .toList();

        // then: @Scheduled 를 쓰면 @EnableScheduling 활성원(남의 잡까지 깨우는 스위치)에 종속된다
        assertThat(scheduled)
                .as("만료 스윕은 전용 데몬 executor 로만 기동해야 한다(공유 @Scheduled 활성원 금지)")
                .isEmpty();
        assertThat(AugmentJobExpirySweeper.class
                .isAnnotationPresent(org.springframework.scheduling.annotation.EnableScheduling.class))
                .as("무조건적 @EnableScheduling 은 게이팅 없는 남의 @Scheduled 잡까지 전 환경에서 깨운다")
                .isFalse();
    }

    private void invokeStart(AugmentJobExpirySweeper target) throws Exception {
        Method start = AugmentJobExpirySweeper.class.getDeclaredMethod("start");
        start.setAccessible(true);
        start.invoke(target);
    }

    private void invokeStop(AugmentJobExpirySweeper target) throws Exception {
        Method stop = AugmentJobExpirySweeper.class.getDeclaredMethod("stop");
        stop.setAccessible(true);
        stop.invoke(target);
    }
}
