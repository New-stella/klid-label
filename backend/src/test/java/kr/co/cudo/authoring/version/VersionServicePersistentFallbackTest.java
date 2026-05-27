package kr.co.cudo.authoring.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.client.GiteaClient;
import kr.co.cudo.authoring.common.client.dto.CommitResponse;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.async.GiteaCommitFallbackQueue;
import kr.co.cudo.authoring.version.entity.LsLabelVersion;
import kr.co.cudo.authoring.version.fallback.GiteaFallbackQueueService;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.version.service.GiteaPathPolicy;
import kr.co.cudo.authoring.version.service.VersionService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 — VersionService 의 영속 fallback 큐 통합 (ccarch if-gitea-contents).
 *
 * <p>핵심 검증:
 * <ul>
 *   <li>Test #5: 정상 동기 호출 시 영속 큐 적재 안 됨 (회귀 가드)</li>
 *   <li>Test #6: Gitea 실패 + gitea.fallback.enabled=true 시 영속 큐 적재 + in-memory 큐도 적재 (병행)</li>
 *   <li>Test #7: Gitea 실패 + gitea.fallback.enabled=false 시 영속 큐 적재 안 함 (기존 동작 보존)</li>
 * </ul>
 */
class VersionServicePersistentFallbackTest {

    private LsLabelVersionRepository labelVersionRepository;
    private GiteaClient giteaClient;
    private GiteaPathPolicy pathPolicy;
    private GiteaCommitFallbackQueue inMemoryQueue;
    private LabelAccessGuard accessGuard;
    private VideoRepository videoRepository;
    private WorkLockService workLockService;
    private ObjectMapper objectMapper;
    private GiteaFallbackQueueService persistentQueue;

    private VersionService versionService;

    private final TokenClaims worker = new TokenClaims("100", Role.WORKER, Channel.INTERNAL,
            Instant.now().plusSeconds(60));

    @BeforeEach
    void setUp() throws Exception {
        labelVersionRepository = mock(LsLabelVersionRepository.class);
        giteaClient = mock(GiteaClient.class);
        pathPolicy = mock(GiteaPathPolicy.class);
        inMemoryQueue = mock(GiteaCommitFallbackQueue.class);
        accessGuard = mock(LabelAccessGuard.class);
        videoRepository = mock(VideoRepository.class);
        workLockService = mock(WorkLockService.class);
        objectMapper = new ObjectMapper();
        persistentQueue = mock(GiteaFallbackQueueService.class);

        versionService = new VersionService(labelVersionRepository, giteaClient, pathPolicy,
                inMemoryQueue, accessGuard, videoRepository, workLockService, objectMapper,
                persistentQueue);
        setRepoField();

        // 공통 mock 설정
        LsDataSrc src = mock(LsDataSrc.class);
        when(src.getRawSn()).thenReturn(9001L);
        when(src.getFrameNo()).thenReturn(0);
        when(src.getSrcSn()).thenReturn(123L);
        when(accessGuard.verifyAndGet(eq(123L), any(TokenClaims.class))).thenReturn(src);

        LsDataRaw raw = LsDataRaw.createFromIngest("CLIP-X", "CCTV-1", "EVT", "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/c.mp4", null, 30);
        Field rawSnField = LsDataRaw.class.getDeclaredField("rawSn");
        rawSnField.setAccessible(true);
        rawSnField.set(raw, 9001L);
        when(videoRepository.findById(9001L)).thenReturn(Optional.of(raw));

        when(workLockService.isRawLocked(9001L)).thenReturn(false);
        when(pathPolicy.path(123L)).thenReturn("10/9001/123.json");
    }

    private void setRepoField() throws Exception {
        Field f = VersionService.class.getDeclaredField("repo");
        f.setAccessible(true);
        f.set(versionService, "labels");
    }

    // ---------- Test #5 ----------

    @Test
    @DisplayName("Phase3_정상_동기_호출_시_영속_큐_적재_안_됨")
    void successPathDoesNotEnqueuePersistent() {
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(new CommitResponse(
                        "abc1234567890abcdef1234567890abcdef12345", "msg", "100", Instant.now())));

        String sha = versionService.commit(123L, "{\"items\":[]}", worker);

        assertThat(sha).isEqualTo("abc1234567890abcdef1234567890abcdef12345");
        verify(persistentQueue, never()).enqueuePut(any(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(inMemoryQueue, never()).enqueue(any(), any(), any());
    }

    // ---------- Test #6 ----------

    @Test
    @DisplayName("Phase3_CircuitBreaker_open_fallback_enabled_true_시_영속_큐_적재")
    void giteaFailureWithFallbackEnabledEnqueuesPersistent() {
        when(persistentQueue.isEnabled()).thenReturn(true);
        when(persistentQueue.enqueuePut(any(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(Optional.of("key-1"));
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("CircuitBreaker open")));

        String sha = versionService.commit(123L, "{\"items\":[]}", worker);

        // 실패 시 null 반환
        assertThat(sha).isNull();
        // MEDIUM-2 (CWE-694): enabled=true → 영속 큐만 사용, in-memory 큐는 호출 안 함 (중복 commit 방지)
        verify(inMemoryQueue, never()).enqueue(any(), any(), any());
        // 영속 큐에만 적재됨 — NEW-2 결정적 idempotencyKey (32자 hex prefix) 사용
        verify(persistentQueue, times(1))
                .enqueuePut(anyString() /* deterministic key */,
                        eq("10/9001/123.json"), eq("main"),
                        anyString(), eq("100"), anyString());
    }

    // ---------- Test #7 ----------

    @Test
    @DisplayName("Phase3_fallback_enabled_false_시_in_memory_큐만_사용_persistent_호출_안_함")
    void giteaFailureWithFallbackDisabledUsesInMemoryOnly() {
        // MEDIUM-2 (CWE-694): enabled=false → in-memory 큐만 호출, persistent 호출 안 함.
        when(persistentQueue.isEnabled()).thenReturn(false);
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("gitea down")));

        String sha = versionService.commit(123L, "{\"items\":[]}", worker);

        // 기존 동작 보존: in-memory 큐 적재, null 반환, 예외 전파 없음
        assertThat(sha).isNull();
        verify(inMemoryQueue, times(1)).enqueue(eq(123L), anyString(), eq("100"));
        // 영속 큐는 호출되지 않음 (단일 큐 분기)
        verify(persistentQueue, never()).enqueuePut(any(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Phase3_영속_큐_적재_중_예외_발생_시_in_memory_안전망으로_적재")
    void persistentEnqueueExceptionFallsBackToInMemory() {
        // MEDIUM-2 분기로 enabled=true 시 persistent 큐를 시도하다가 예외 발생하면
        // 안전망으로 in-memory 큐에 적재 — 데이터 손실 방지.
        when(persistentQueue.isEnabled()).thenReturn(true);
        when(persistentQueue.enqueuePut(any(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("DB down"));
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("gitea down")));

        String sha = versionService.commit(123L, "{\"items\":[]}", worker);

        // 영속 큐 예외는 흡수되어 in-memory 큐로 폴백
        assertThat(sha).isNull();
        verify(inMemoryQueue, times(1)).enqueue(eq(123L), anyString(), eq("100"));
    }

    // ---------- DEV_FIX MEDIUM-2 + NEW-2 ----------

    @Test
    @DisplayName("DEV_FIX_MEDIUM2_persistent_fallback_enabled_시_in_memory_큐_적재_안_함")
    void medium2_enabledTrue_skipsInMemory() {
        when(persistentQueue.isEnabled()).thenReturn(true);
        when(persistentQueue.enqueuePut(any(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(Optional.of("key"));
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("gitea down")));

        versionService.commit(123L, "{\"items\":[]}", worker);

        // enabled=true → 영속 큐만, in-memory 호출 0회 (중복 commit 차단)
        verify(persistentQueue, times(1)).enqueuePut(any(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verify(inMemoryQueue, never()).enqueue(any(), any(), any());
    }

    @Test
    @DisplayName("DEV_FIX_NEW2_같은_srcSn_path_content_재호출_시_같은_idempotencyKey_사용")
    void new2_deterministicIdempotencyKey() {
        when(persistentQueue.isEnabled()).thenReturn(true);
        when(persistentQueue.enqueuePut(any(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(Optional.of("k"));
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("gitea down")));

        // 같은 (srcSn, labelsJson) 두 번 호출 — path/content 동일하므로 같은 key 생성되어야 함
        versionService.commit(123L, "{\"items\":[]}", worker);
        versionService.commit(123L, "{\"items\":[]}", worker);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(persistentQueue, times(2)).enqueuePut(keyCaptor.capture(),
                anyString(), anyString(), anyString(), anyString(), anyString());
        assertThat(keyCaptor.getAllValues()).hasSize(2);
        // 결정적 — 두 호출 모두 같은 key
        assertThat(keyCaptor.getAllValues().get(0)).isEqualTo(keyCaptor.getAllValues().get(1));
        // 32자 hex prefix (SHA-256)
        assertThat(keyCaptor.getAllValues().get(0)).hasSize(32).matches("^[0-9a-f]{32}$");
    }

    @Test
    @DisplayName("DEV_FIX_NEW2_다른_content_재호출_시_다른_idempotencyKey")
    void new2_differentContentDifferentKey() {
        when(persistentQueue.isEnabled()).thenReturn(true);
        when(persistentQueue.enqueuePut(any(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(Optional.of("k"));
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("gitea down")));

        versionService.commit(123L, "{\"items\":[{\"id\":1}]}", worker);
        versionService.commit(123L, "{\"items\":[{\"id\":2}]}", worker);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(persistentQueue, times(2)).enqueuePut(keyCaptor.capture(),
                anyString(), anyString(), anyString(), anyString(), anyString());
        // 다른 content → 다른 key
        assertThat(keyCaptor.getAllValues().get(0))
                .isNotEqualTo(keyCaptor.getAllValues().get(1));
    }

    // ---------- DEV_FIX H-2 (rollback persistent queue) ----------

    @Test
    @DisplayName("DEV_FIX_H2_rollback_Gitea_실패_enabled_true_시_영속_큐_적재")
    void h2_rollbackFailure_enabledTrue_enqueuesPersistent() {
        // given: rollback 대상 커밋 + Gitea getContent 성공 + createOrUpdateFile 실패
        String commitHash = "abc1234567890abcdef1234567890abcdef12345";
        LsLabelVersion target = mock(LsLabelVersion.class);
        when(target.getDataSrcSn()).thenReturn(123L);
        when(labelVersionRepository.findByGiteaCmtHash(commitHash))
                .thenReturn(Optional.of(target));
        when(labelVersionRepository.findByDataRawSnAndDataSrcSnAndActiveYn(
                any(), any(), any())).thenReturn(java.util.List.of());
        when(labelVersionRepository.countByDataRawSnAndDataSrcSn(any(), any()))
                .thenReturn(0);
        when(labelVersionRepository.save(any(LsLabelVersion.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(giteaClient.getContent(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just("snapshot-content"));
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("gitea down")));

        when(persistentQueue.isEnabled()).thenReturn(true);
        when(persistentQueue.enqueuePut(any(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(Optional.of("key"));

        // when
        versionService.rollback(commitHash, 123L, worker);

        // then: enabled=true → 영속 큐만 적재 (in-memory 큐는 호출 안 함)
        verify(persistentQueue, times(1)).enqueuePut(anyString(),
                eq("10/9001/123.json"), eq("main"),
                anyString(), eq("100"), anyString());
        verify(inMemoryQueue, never()).enqueue(any(), any(), any());
    }

    @Test
    @DisplayName("DEV_FIX_H2_rollback_Gitea_실패_enabled_false_시_in_memory_큐_적재")
    void h2_rollbackFailure_enabledFalse_enqueuesInMemory() {
        String commitHash = "abc1234567890abcdef1234567890abcdef12345";
        LsLabelVersion target = mock(LsLabelVersion.class);
        when(target.getDataSrcSn()).thenReturn(123L);
        when(labelVersionRepository.findByGiteaCmtHash(commitHash))
                .thenReturn(Optional.of(target));
        when(labelVersionRepository.findByDataRawSnAndDataSrcSnAndActiveYn(
                any(), any(), any())).thenReturn(java.util.List.of());
        when(labelVersionRepository.countByDataRawSnAndDataSrcSn(any(), any()))
                .thenReturn(0);
        when(labelVersionRepository.save(any(LsLabelVersion.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        when(giteaClient.getContent(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just("snapshot-content"));
        when(giteaClient.createOrUpdateFile(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new RuntimeException("gitea down")));

        when(persistentQueue.isEnabled()).thenReturn(false);

        versionService.rollback(commitHash, 123L, worker);

        // enabled=false → in-memory 만 적재 (기존 동작 회귀 보존)
        verify(inMemoryQueue, times(1)).enqueue(eq(123L), anyString(), eq("100"));
        verify(persistentQueue, never()).enqueuePut(any(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }
}
