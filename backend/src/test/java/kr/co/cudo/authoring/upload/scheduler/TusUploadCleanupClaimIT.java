package kr.co.cudo.authoring.upload.scheduler;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.upload.entity.LsTusUpload;
import kr.co.cudo.authoring.upload.repository.LsTusUploadRepository;
import kr.co.cudo.authoring.video.dto.InternalUploadIngestCommand;
import kr.co.cudo.authoring.video.entity.LsDataIngest;
import kr.co.cudo.authoring.video.repository.InternalUploadIngestWriter;
import kr.co.cudo.authoring.video.repository.LsDataIngestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

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

    /** 만료 정리가 인입 행까지 종결하는지 확인하기 위한 인입 통로/조회. */
    @Autowired
    private InternalUploadIngestWriter ingestWriter;

    @Autowired
    private LsDataIngestRepository ingestRepository;

    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate txTemplate;

    private final List<UUID> createdIds = new ArrayList<>();
    private final List<String> createdClipIds = new ArrayList<>();

    TusUploadCleanupClaimIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @AfterEach
    void cleanup() {
        createdIds.forEach(id -> uploadRepository.findById(id).ifPresent(uploadRepository::delete));
        createdIds.clear();
        JdbcTemplate jdbc = new JdbcTemplate(controlDataSource);
        createdClipIds.forEach(clipId ->
                jdbc.update("DELETE FROM ls_data_ingest WHERE vms_clip_id = ?", clipId));
        createdClipIds.clear();
    }

    /**
     * 만료된 미완료 <b>관제</b> 세션 1건 + 실제 임시 파일을 커밋 저장한다.
     *
     * <p>★ 클립 식별자를 반드시 준다(ADR-058) — 흡수로 이 원장을 <b>두 채널이 함께 쓰게</b> 되면서
     * 이 잡의 후보 조회가 「클립 식별자를 가진 세션」으로 좁혀졌다. 그 값이 관제 세션의 구조적 표식이며
     * (요청 검증이 강제한다), 비운 세션은 포털 채널로 읽혀 이 잡이 집지 않는다.
     */
    private LsTusUpload persistExpired() {
        return persistExpired("CLEANUP-" + System.nanoTime());
    }

    /** 만료된 미완료 <b>포털</b> 세션 — 클립 식별자가 없는 것이 곧 채널 판별자다(ADR-058). */
    private LsTusUpload persistExpiredPortalSession() {
        return persistExpired(null);
    }

    /** 만료된 미완료 세션 1건 — {@code vmsClipId} 를 주면 그 인입 행과 연결된다. */
    private LsTusUpload persistExpired(String vmsClipId) {
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
                    vmsClipId, null, null, null));
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
    @DisplayName("M2_TTL_만료정리가_인입행도_종결한다 — 브라우저를_닫고_떠나는_것이_TUS의_주_시나리오다")
    void expiryCleanupTerminatesIngestRow() {
        // given — 세션 생성만 하고 떠난 업로드(파일 미도착). 구 구현은 cancel() 에만 종결을
        //   배선해, 만료 정리는 세션 행·임시 파일만 지우고 인입 행을 24시간 폴링 후보로 남겼다.
        drainPreexistingExpired();
        String clipId = "TUS-CLEANUP-IT-" + System.nanoTime();
        long rcptnSn = seedPendingIngestRow(clipId, STORAGE_ROOT.resolve(clipId + ".mp4").toString());
        persistExpired(clipId);

        // when
        assertThat(job.cleanupExpired()).isEqualTo(1);

        // then — 파일이 영영 오지 않을 행이므로 사유를 남기고 종결한다
        LsDataIngest row = ingestRepository.findById(rcptnSn).orElseThrow();
        assertThat(row.getPrcsSttsCd()).isEqualTo(LsDataIngest.PRCS_STTS_FAILED);
        assertThat(row.getErrMsg()).isNotBlank();
    }

    @Test
    @DisplayName("M2_파일이_이미_도착한_세션은_만료정리가_인입행을_종결하지_않는다 — H2b와_동일_원칙")
    void expiryCleanupKeepsIngestRowWhenFileArrived() throws IOException {
        // given — 완료 직후 세션만 만료된 형상. 행을 죽이면 <행은 죽고 파일은 남는> PII 고아다.
        drainPreexistingExpired();
        String clipId = "TUS-CLEANUP-IT-" + System.nanoTime();
        Path arrived = STORAGE_ROOT.resolve(clipId + ".mp4");
        Files.writeString(arrived, "arrived-video");
        long rcptnSn = seedPendingIngestRow(clipId, arrived.toString());
        persistExpired(clipId);

        // when
        assertThat(job.cleanupExpired()).isEqualTo(1);

        // then — 파일 실재가 세션 플래그보다 신뢰도 높은 진실원이다
        assertThat(ingestRepository.findById(rcptnSn).orElseThrow().getPrcsSttsCd())
                .isEqualTo(LsDataIngest.PRCS_STTS_PENDING);
    }

    /** 인입 행 1건(PENDING) — 내부 업로드 통로로 실제 INSERT 한다. */
    private long seedPendingIngestRow(String clipId, String rawFilePathNm) {
        createdClipIds.add(clipId);
        return ingestWriter.insertPending(InternalUploadIngestCommand.builder()
                .vmsClipId(clipId)
                .vmsCctvId("CCTV-CLEANUP-IT")
                .vdoFileNm(clipId + ".mp4")
                .rawFilePathNm(rawFilePathNm)
                .srcType(LsDataIngest.SRC_TYPE_USER_ULD)
                .lclgvCd("11680")
                .build());
    }

    @Test
    @DisplayName("★만료된_포털_세션은_이_잡이_집지_않는다 — 집으면_행만_지우고_파일이_고아로_남는다")
    void expiredPortalSessionIsLeftToPortalSweep() {
        // given — 클립 식별자가 없는 만료 세션(포털 채널)
        drainPreexistingExpired();
        LsTusUpload portalSession = persistExpiredPortalSession();

        // when
        int cleaned = job.cleanupExpired();

        // then — 이 잡은 포털 세션을 처리할 수단이 없다: 종결할 인입 행이 없고(클립 식별자 부재)
        //   임시 파일도 관제 저장 루트 밖이라 경로 가드에 막힌다. 집으면 <행만> 사라진다.
        assertThat(cleaned).isZero();
        assertThat(uploadRepository.findById(portalSession.getUploadId())).isPresent();
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
                "alive.mp4", "CLEANUP-ALIVE-" + System.nanoTime(), null, null, null)));
        createdIds.add(aliveId);

        // when
        int cleaned = job.cleanupExpired();

        // then — 만료 세션만 정리
        assertThat(cleaned).isEqualTo(1);
        assertThat(uploadRepository.findById(expired.getUploadId())).isEmpty();
        assertThat(uploadRepository.findById(aliveId)).isPresent();
    }
}
