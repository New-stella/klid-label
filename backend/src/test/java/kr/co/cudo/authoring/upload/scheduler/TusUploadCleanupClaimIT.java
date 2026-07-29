package kr.co.cudo.authoring.upload.scheduler;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.repository.LsTusUploadRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9-B — TUS 만료 세션 정리의 <b>원자 클레임</b> 통합 테스트 (실 DB, PostgreSQL Testcontainer).
 *
 * <p>배경: 2노드 Active-Active 이고 Quartz 클러스터링이 기본 꺼져 있으므로 {@code @Scheduled} 정리 잡도
 * 양 노드에서 동시에 발화한다. "조회 후 delete(entity)" 는 같은 행을 두 노드가 각각 지우려 해
 * 중복 파일 삭제·낙관적 잠금 예외를 낳는다. 삭제 자체를 조건부 DELETE 로 만들어 <b>한쪽만 1행</b>을
 * 얻어야 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class TusUploadCleanupClaimIT {

    /** 정리 잡의 삭제 안전 가드(CWE-22)를 통과시키기 위한 storage root — 빈 생성 시점 해석. */
    private static final Path STORAGE_ROOT;

    static {
        try {
            STORAGE_ROOT = Files.createTempDirectory("tus-cleanup-root");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void storagePath(DynamicPropertyRegistry registry) {
        registry.add("authoring.storage.raw-path", STORAGE_ROOT::toString);
    }

    @Autowired
    private TusUploadCleanupJob job;

    @Autowired
    private LsTusUploadRepository uploadRepository;

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate txTemplate;

    private final List<UUID> createdIds = new ArrayList<>();

    TusUploadCleanupClaimIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @AfterEach
    void cleanup() {
        createdIds.forEach(id -> uploadRepository.findById(id).ifPresent(uploadRepository::delete));
        createdIds.clear();
    }

    /** 만료된 미완료 세션 1건 + 실제 임시 파일을 커밋 저장한다. */
    private LsTusUpload persistExpired() {
        UUID id = UUID.randomUUID();
        Path file = STORAGE_ROOT.resolve(id + ".part");
        try {
            Files.writeString(file, "CHUNK");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        txTemplate.executeWithoutResult(s -> {
            uploadRepository.saveAndFlush(LsTusUpload.create(
                    id, "user-1", 100L, file.toString(), "clip.mp4",
                    null, null, null, null, null, null));
            // 만료 상태 재현 — 엔티티 API 로는 과거 만료시각을 만들 수 없어 JPQL 로 직접 낮춘다
            //   (물리 컬럼명 결합 회피).
            em.createQuery("UPDATE LsTusUpload u SET u.expiresAt = :past WHERE u.uploadId = :id")
                    .setParameter("past", LocalDateTime.now().minusDays(2))
                    .setParameter("id", id)
                    .executeUpdate();
        });
        createdIds.add(id);
        return uploadRepository.findById(id).orElseThrow();
    }

    /** 공유 컨테이너 DB 의 잔여 만료 세션을 미리 정리해 후보를 이 테스트 행으로 한정한다. */
    private void drainPreexistingExpired() {
        job.cleanupExpired();
    }

    @Test
    @DisplayName("TUS_정리가_2노드_동시_발화에도_중복_삭제되지_않는다")
    void concurrentCleanupDeletesEachSessionExactlyOnce() throws Exception {
        // given — 만료 세션 1건
        drainPreexistingExpired();
        LsTusUpload expired = persistExpired();
        Path file = Path.of(expired.getFilePath());
        assertThat(Files.exists(file)).isTrue();

        // when — 두 노드(스레드)가 거의 동시에 정리
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        int total;
        try {
            Future<Integer> a = pool.submit(() -> {
                start.await();
                return job.cleanupExpired();
            });
            Future<Integer> b = pool.submit(() -> {
                start.await();
                return job.cleanupExpired();
            });
            start.countDown();
            // 예외 없이(낙관적 잠금 충돌 없이) 둘 다 끝나야 한다.
            total = a.get(60, TimeUnit.SECONDS) + b.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // then — 정리 건수 합이 정확히 1(한 노드만 클레임), 행/파일은 제거
        assertThat(total).as("2노드가 같은 세션을 각각 정리하면 중복 삭제다").isEqualTo(1);
        assertThat(uploadRepository.findById(expired.getUploadId())).isEmpty();
        assertThat(Files.exists(file)).isFalse();
    }

    @Test
    @DisplayName("단일노드에서도_기존_동작이_유지된다 — 만료세션은_정리되고_미만료는_보존된다")
    void singleNodeBehaviourPreserved() {
        // given — 만료 1건 + 미만료 1건
        drainPreexistingExpired();
        LsTusUpload expired = persistExpired();
        UUID aliveId = UUID.randomUUID();
        txTemplate.executeWithoutResult(s -> uploadRepository.saveAndFlush(LsTusUpload.create(
                aliveId, "user-1", 100L, STORAGE_ROOT.resolve(aliveId + ".part").toString(),
                "alive.mp4", null, null, null, null, null, null)));
        createdIds.add(aliveId);

        // when
        int cleaned = job.cleanupExpired();

        // then — 만료 세션만 정리
        assertThat(cleaned).isEqualTo(1);
        assertThat(uploadRepository.findById(expired.getUploadId())).isEmpty();
        assertThat(uploadRepository.findById(aliveId)).isPresent();
    }
}
