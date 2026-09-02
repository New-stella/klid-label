package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.entity.LsDataAugJobFile;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobFileRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.GenAiCallbackRequest;
import kr.co.cudo.authoring.webhook.service.GenAiCallbackService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성형 AI 웹훅 <b>롤업 원자성</b> 통합 테스트 (실 DB, PostgreSQL Testcontainer) — Phase 7-A2.
 *
 * <p>증강 1건이 job 2개로 분할 위탁된 상태에서 <b>마지막 job 2건의 콜백이 동시에</b> 도착하는
 * 상황을 실제 트랜잭션으로 재현한다. 지켜야 할 두 성질:
 * <ol>
 *   <li><b>중복 확정 금지</b> — 롤업이 2번 돌아 증강 영상이 2건 생기면 안 된다.</li>
 *   <li><b>유실 금지</b> — 두 트랜잭션이 서로의 미커밋 갱신을 못 봐서 <b>양쪽 다</b> "아직 남은
 *       job 있음" 으로 판정하고 롤업이 통째로 사라지면 안 된다. 이 유실은 job 행을 먼저 갱신하고
 *       나중에 집계하면 반드시 발생하며, 증강행 잠금 선점으로만 막힌다.</li>
 * </ol>
 * 즉 결과는 정확히 <b>영상 1건 + 증강행 종결(non-PENDING)</b> 이어야 한다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 콜백 산출물 경로(/storage/genai/**)가 허용 루트 검증을 통과하도록 마운트 루트를 명시한다.
        "authoring.storage.raw-mount-roots=/storage"
})
class GenAiCallbackRollupConcurrencyIT {

    @Autowired private GenAiCallbackService service;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugJobRepository augJobRepository;
    @Autowired private LsDataAugJobFileRepository augJobFileRepository;
    @Autowired private kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository
            deidentProcLogRepositoryForFixture;

    private record Seed(Long parentRawSn, Long dataAugSn, String key1, String key2) { }

    /** 부모(비식별 완료) + 프레임 2건 + PENDING 증강 1건 + 위탁 job 2건(RECEIVED) 시드. */
    private Seed seed(String suffix) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "GENRU-" + suffix + "-" + UUID.randomUUID(), "CCTV-GENRU", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/GENRU-" + suffix + ".mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified("Y");
        parent = videoRepository.save(parent);
        seedParentDeidVideo(parent.getRawSn(), parent.getRawFilePathNm());
        LsDataSrc frame0 = srcRepository.save(
                LsDataSrc.create(parent.getRawSn(), 0, parent.getRawSn() + "/f0.jpg", null));
        LsDataSrc frame1 = srcRepository.save(
                LsDataSrc.create(parent.getRawSn(), 1, parent.getRawSn() + "/f1.jpg", null));

        String baseKey = "GENRU-K-" + suffix + "-" + UUID.randomUUID();
        LsDataAug aug = augRepository.save(LsDataAug.createRequested(
                frame0.getSrcSn(), LsDataAug.AUG_WINTER, "1", baseKey, null));

        String key1 = baseKey + "-1";
        String key2 = baseKey + "-2";
        // Phase 7-D — job 당 위탁 1건씩(프레임 2건을 2 job 으로 분할). 콜백의 results 도 1건씩이라
        //   건수가 맞아야 SUCCEEDED 로 접수된다(불일치는 fail-closed 로 FAILED).
        LsDataAugJob job1 = augJobRepository.save(acceptedJob(aug.getDataAugSn(), 1, key1));
        LsDataAugJob job2 = augJobRepository.save(acceptedJob(aug.getDataAugSn(), 2, key2));
        augJobFileRepository.save(LsDataAugJobFile.issued(job1.getAugJobSn(), 1, frame0.getSrcSn()));
        augJobFileRepository.save(LsDataAugJobFile.issued(job2.getAugJobSn(), 2, frame1.getSrcSn()));
        return new Seed(parent.getRawSn(), aug.getDataAugSn(), key1, key2);
    }

    /**
     * 부모 <b>비식별 영상 산출물</b> 처리 이력 — 파생 복사 원본 경로의 진실원(ADR-058).
     *
     * <p>부모 게이트가 플래그가 아니라 <b>경로를 직접 조달</b>하도록 바뀌어(「비식별이 끝났나」는 조건이
     * 아니라 결과였다) 플래그만 세운 부모로는 통과하지 않는다. 운영에서는 {@code DE_IDENT_YN='Y'} 전이와
     * 성공 처리 이력 적재가 <b>같은 트랜잭션</b>이라 이 조합이 실재하지 않는다 — 구 픽스처가 실제보다
     * 느슨했던 것이다.
     */
    private void seedParentDeidVideo(Long parentRawSn, String orgnlFilePath) {
        var procLog = kr.co.cudo.authoring.batch.entity.LsDeidentProcLog.request(
                parentRawSn, null, orgnlFilePath, "test");
        procLog.succeed("videos/" + parentRawSn + "/deidentified.mp4");
        deidentProcLogRepositoryForFixture.saveAndFlush(procLog);
    }

    private static LsDataAugJob acceptedJob(Long dataAugSn, int seq, String requestId) {
        LsDataAugJob job = LsDataAugJob.createIssued(dataAugSn, seq, requestId, 1);
        job.markAccepted(jobIdOf(requestId));
        return job;
    }

    private static String jobIdOf(String requestId) {
        return "J-" + requestId;
    }

    private static GenAiCallbackRequest succeeded(String requestId) {
        return new GenAiCallbackRequest(requestId, jobIdOf(requestId), "SUCCEEDED", 100,
                "COMPLETED", "2026-07-27T10:00:00Z",
                List.of(new GenAiCallbackRequest.ResultItem(
                        "gen-" + requestId, "IMAGE",
                        "/storage/genai/" + jobIdOf(requestId) + "/001_gen.jpg", null)),
                null, null);
    }

    private long countChildren(Long parentRawSn) {
        return videoRepository.findAll().stream()
                .filter(r -> parentRawSn.equals(r.getOrgnlRawSn()))
                .count();
    }

    @Test
    @DisplayName("마지막_job_콜백이_동시에_2건_도착해도_롤업이_1회만_실행된다")
    void concurrentFinalCallbacks_rollUpExactlyOnce() throws Exception {
        Seed s = seed("RU");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        try {
            Future<?> a = pool.submit(() -> run(s.key1(), ready, start, err));
            Future<?> b = pool.submit(() -> run(s.key2(), ready, start, err));
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            a.get(60, TimeUnit.SECONDS);
            b.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(err.get()).as("동시 콜백은 예외 없이 직렬화돼야 한다").isNull();
        assertThat(augJobRepository.findByDataAugSnOrderByJobSeqAsc(s.dataAugSn()))
                .as("두 job 모두 SUCCEEDED 로 종결")
                .allMatch(j -> LsDataAugJob.STTS_SUCCEEDED.equals(j.getJobSttsCd()));
        assertThat(augRepository.findById(s.dataAugSn()).orElseThrow().getAugProcSttsCd())
                .as("전 job 종결 → 증강 1건이 반드시 확정돼야 한다(롤업 유실 금지)")
                .isEqualTo(LsDataAug.STTS_ACCEPTED);
        assertThat(countChildren(s.parentRawSn()))
                .as("롤업이 2회 실행되면 파생 영상이 2건이 된다(중복 확정 금지)")
                .isEqualTo(1L);
    }

    private Void run(String requestId, CountDownLatch ready, CountDownLatch start,
                     AtomicReference<Throwable> err) {
        try {
            ready.countDown();
            start.await();
            service.handle(succeeded(requestId));
        } catch (Throwable t) {
            err.set(t);
        }
        return null;
    }
}
