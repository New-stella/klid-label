package kr.co.cudo.authoring.version.fallback;

import kr.co.cudo.authoring.common.client.GiteaClient;
import kr.co.cudo.authoring.common.client.dto.CommitResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 — GiteaFallbackRetryJob 단위 테스트.
 */
class GiteaFallbackRetryJobTest {

    private LsGiteaFallbackQueueRepository repository;
    private GiteaFallbackQueueService queueService;
    private GiteaClient giteaClient;
    private GiteaFallbackRetryJob job;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(LsGiteaFallbackQueueRepository.class);
        queueService = mock(GiteaFallbackQueueService.class);
        giteaClient = mock(GiteaClient.class);
        job = new GiteaFallbackRetryJob(repository, queueService, giteaClient);
        Field repoField = GiteaFallbackRetryJob.class.getDeclaredField("repo");
        repoField.setAccessible(true);
        repoField.set(job, "labels");
    }

    private LsGiteaFallbackQueue makePending(Long sn, String key) {
        LsGiteaFallbackQueue q = LsGiteaFallbackQueue.putPending(key, "10/1/" + sn + ".json",
                "main", "msg", "u", "Y29udA==");
        setField(q, "queueSn", sn);
        return q;
    }

    private static void setField(Object t, String n, Object v) {
        try {
            Field f = findField(t.getClass(), n);
            f.setAccessible(true);
            f.set(t, v);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static Field findField(Class<?> c, String n) throws NoSuchFieldException {
        Class<?> cur = c;
        while (cur != null) {
            try { return cur.getDeclaredField(n); }
            catch (NoSuchFieldException ignored) { cur = cur.getSuperclass(); }
        }
        throw new NoSuchFieldException(n);
    }

    @Test
    @DisplayName("Phase3_재시도_성공_시_markSucceeded_호출")
    void retrySuccessMarksSucceeded() {
        LsGiteaFallbackQueue q1 = makePending(1L, "key-1");
        when(repository.findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                eq(LsGiteaFallbackQueue.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1));
        when(queueService.claimForRetry(1L)).thenReturn(Optional.of(q1));
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new CommitResponse("sha-1", "msg", "u", Instant.now())));

        int processed = job.runOnce();

        assertThat(processed).isEqualTo(1);
        verify(queueService).markSucceeded(1L);
    }

    @Test
    @DisplayName("Phase3_재시도_실패_시_markFailedAndSchedule_호출")
    void retryFailureSchedules() {
        LsGiteaFallbackQueue q1 = makePending(2L, "key-2");
        when(repository.findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                eq(LsGiteaFallbackQueue.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1));
        when(queueService.claimForRetry(2L)).thenReturn(Optional.of(q1));
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("502 Bad Gateway")));

        int processed = job.runOnce();

        assertThat(processed).isZero();
        verify(queueService, times(1)).markFailedAndSchedule(eq(2L), anyString());
    }

    @Test
    @DisplayName("Phase3_PENDING_항목_없으면_no_op")
    void emptyDueDoesNothing() {
        when(repository.findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                anyString(), any(), any(PageRequest.class)))
                .thenReturn(List.of());
        int processed = job.runOnce();
        assertThat(processed).isZero();
    }

    @Test
    @DisplayName("Phase3_동시_실행_시_claimForRetry_Optional_empty_면_중복_처리_차단_S_5")
    void concurrentRunSkipsAlreadyClaimedItem() {
        // S-5: 다른 인스턴스가 이미 RETRYING 으로 마킹 + 처리 중 → claimForRetry 가 Optional.empty 반환.
        // 본 워커는 처리 시도하지 말아야 한다.
        LsGiteaFallbackQueue q1 = makePending(10L, "key-10");
        when(repository.findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                eq(LsGiteaFallbackQueue.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1));
        // 다른 인스턴스가 먼저 가져감 — empty 반환
        when(queueService.claimForRetry(10L)).thenReturn(Optional.empty());

        int processed = job.runOnce();

        assertThat(processed).isZero();
        // Gitea 호출 안 함 — 중복 처리 차단됨
        org.mockito.Mockito.verify(giteaClient, org.mockito.Mockito.never())
                .createOrUpdateFile(anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyString());
        org.mockito.Mockito.verify(queueService, org.mockito.Mockito.never())
                .markSucceeded(any());
        org.mockito.Mockito.verify(queueService, org.mockito.Mockito.never())
                .markFailedAndSchedule(any(), anyString());
    }

    @Test
    @DisplayName("Phase3_여러_due_항목_중_일부만_claim_성공_시_그것만_처리")
    void multipleDuePartialClaim() {
        // 3개 due — 1번은 claim 성공, 2번은 다른 워커가 가져감(empty), 3번은 claim 성공.
        LsGiteaFallbackQueue q1 = makePending(11L, "key-11");
        LsGiteaFallbackQueue q2 = makePending(12L, "key-12");
        LsGiteaFallbackQueue q3 = makePending(13L, "key-13");
        when(repository.findByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
                eq(LsGiteaFallbackQueue.STATUS_PENDING), any(LocalDateTime.class), any(PageRequest.class)))
                .thenReturn(List.of(q1, q2, q3));
        when(queueService.claimForRetry(11L)).thenReturn(Optional.of(q1));
        when(queueService.claimForRetry(12L)).thenReturn(Optional.empty());
        when(queueService.claimForRetry(13L)).thenReturn(Optional.of(q3));
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new CommitResponse("sha-x", "msg", "u", Instant.now())));

        int processed = job.runOnce();

        // 2개만 처리됨 (1, 3)
        assertThat(processed).isEqualTo(2);
        verify(queueService).markSucceeded(11L);
        verify(queueService).markSucceeded(13L);
        verify(queueService, org.mockito.Mockito.never()).markSucceeded(12L);
    }
}
