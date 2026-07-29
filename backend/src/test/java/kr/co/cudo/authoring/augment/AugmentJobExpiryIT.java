package kr.co.cudo.authoring.augment;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugJob;
import kr.co.cudo.authoring.augment.entity.LsDataAugJobFile;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobFileRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugJobRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.service.AugmentJobExpirySweeper;
import kr.co.cudo.authoring.augment.service.AugmentJobExpiryTxService;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.webhook.dto.GenAiCallbackRequest;
import kr.co.cudo.authoring.webhook.service.GenAiCallbackService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 증강 위탁 job <b>만료 스윕</b> 통합 테스트 (실 DB, PostgreSQL Testcontainer) — Phase 8-A.
 *
 * <p>막으려는 실패 모드: {@code LS_DATA_AUG_JOB} 이 비종결(RECEIVED/RUNNING)로 영원히 남으면
 * 롤업이 무기한 보류돼 {@code LS_DATA_AUG} 가 <b>PENDING 고착</b>된다. 비종결로 남는 경로는
 * ①CANCELED 웹훅 미수신(계약상 취소는 웹훅 이벤트가 아니다) ②콜백 검증 실패(400)로 상태를
 * 바꾸지 않은 채 외부가 재시도를 포기 ③외부 무응답이다.
 *
 * <p>시간 경과는 <b>cutoff 주입</b>으로 재현한다(행 시각 조작·클럭 목킹 없음) — 운영 코드가
 * {@code now - idle-timeout} 으로 만드는 값과 동일한 파라미터다.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        // 콜백 산출물 경로(/storage/genai/**)가 허용 루트 검증을 통과하도록 마운트 루트를 명시한다.
        "authoring.storage.raw-mount-roots=/storage"
})
class AugmentJobExpiryIT {

    @Autowired private AugmentJobExpirySweeper sweeper;
    @Autowired private AugmentJobExpiryTxService expiryTxService;
    @Autowired private GenAiCallbackService callbackService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataAugRepository augRepository;
    @Autowired private LsDataAugJobRepository augJobRepository;
    @Autowired private LsDataAugJobFileRepository augJobFileRepository;

    private record Seed(Long parentRawSn, Long dataAugSn, Long augJobSn, String requestId) { }

    /** 부모(비식별 완료) + 프레임 1건 + PENDING 증강 1건 + 위탁 job 1건(비종결) 시드. */
    private Seed seed(String suffix, String state) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "AJEX-" + suffix + "-" + UUID.randomUUID(), "CCTV-AJEX", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/AJEX-" + suffix + ".mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified("Y");
        parent = videoRepository.save(parent);
        LsDataSrc frame = srcRepository.save(
                LsDataSrc.create(parent.getRawSn(), 0, parent.getRawSn() + "/f0.jpg", null));

        String requestId = "AJEX-K-" + suffix + "-" + UUID.randomUUID();
        LsDataAug aug = augRepository.save(LsDataAug.createRequested(
                frame.getSrcSn(), LsDataAug.AUG_WINTER, "1", requestId, null));

        LsDataAugJob job = LsDataAugJob.createIssued(aug.getDataAugSn(), 1, requestId, 1);
        job.markAccepted("J-" + requestId);
        if (LsDataAugJob.STTS_RUNNING.equals(state)) {
            job.markRunning("J-" + requestId);
        }
        job = augJobRepository.save(job);
        augJobFileRepository.save(LsDataAugJobFile.issued(job.getAugJobSn(), 1, frame.getSrcSn()));
        return new Seed(parent.getRawSn(), aug.getDataAugSn(), job.getAugJobSn(), requestId);
    }

    /** 임계 경과를 재현하는 cutoff(= now + 여유). 이 시각 이전부터 갱신이 멈춘 job 이 대상이다. */
    private static LocalDateTime elapsedCutoff() {
        return LocalDateTime.now().plusMinutes(5);
    }

    private long countChildren(Long parentRawSn) {
        return videoRepository.findAll().stream()
                .filter(r -> parentRawSn.equals(r.getOrgnlRawSn()))
                .count();
    }

    private String stateOf(Long augJobSn) {
        return augJobRepository.findById(augJobSn).orElseThrow().getJobSttsCd();
    }

    // ─── ① 만료 종결 ──────────────────────────────────────────

    @Test
    @DisplayName("임계시간_초과한_비종결_job_이_만료_종결된다")
    void idleNonTerminalJobIsExpired() {
        // given: RUNNING 인 채 갱신이 멈춘 job
        Seed s = seed("IDLE", LsDataAugJob.STTS_RUNNING);
        assertThat(stateOf(s.augJobSn())).isEqualTo(LsDataAugJob.STTS_RUNNING);

        // when
        int expired = sweeper.sweep(elapsedCutoff());

        // then: 사유를 남기고 종결된다(조용한 유실 금지)
        assertThat(expired).isPositive();
        LsDataAugJob job = augJobRepository.findById(s.augJobSn()).orElseThrow();
        assertThat(job.isTerminal()).isTrue();
        assertThat(job.getJobSttsCd()).isEqualTo(LsDataAugJob.STTS_FAILED);
        assertThat(job.getErrorCode()).isEqualTo(LsDataAugJob.ERR_EXPIRED);
        assertThat(job.getErrorMessage()).isNotBlank();
    }

    @Test
    @DisplayName("만료된_job_이_있으면_증강이_성공으로_확정되지_않는다")
    void expiredJobNeverConfirmsAugmentAsSuccess() {
        // given
        Seed s = seed("NOSUCCESS", LsDataAugJob.STTS_RECEIVED);

        // when
        sweeper.sweep(elapsedCutoff());

        // then: 만료는 성공이 아니다 — 증강본(파생 영상)이 만들어지면 안 된다
        assertThat(augRepository.findById(s.dataAugSn()).orElseThrow().getAugProcSttsCd())
                .isNotEqualTo(LsDataAug.STTS_ACCEPTED);
        assertThat(countChildren(s.parentRawSn()))
                .as("만료 종결이 성공으로 둔갑하면 불완전 프레임셋으로 파생 영상이 생긴다")
                .isZero();
    }

    @Test
    @DisplayName("만료_종결_후_롤업이_진행되어_PENDING_고착이_해소된다")
    void rollUpProceedsAfterExpiry() {
        // given: 비종결 job 때문에 증강이 PENDING 에 머물러 있다
        Seed s = seed("ROLLUP", LsDataAugJob.STTS_RECEIVED);
        assertThat(augRepository.findById(s.dataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_PENDING);

        // when
        sweeper.sweep(elapsedCutoff());

        // then: 전 job 종결 → 롤업이 진행돼 증강 1건이 실패로 확정된다(무기한 보류 해소)
        assertThat(augRepository.findById(s.dataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_REJECTED);
    }

    // ─── ② CANCELED (웹훅 미수신 계약) ────────────────────────

    @Test
    @DisplayName("취소된_job_이_영원히_비종결로_남지_않는다")
    void canceledJobDoesNotStayNonTerminalForever() {
        // given: 외부에서 취소된 job. 계약상 CANCELED 는 진행·결과 웹훅 이벤트가 아니라
        //        (취소 응답으로만 통보) 우리 쪽 행은 RECEIVED 인 채 갱신이 멈춘다.
        Seed s = seed("CANCEL", LsDataAugJob.STTS_RECEIVED);

        // when
        sweeper.sweep(elapsedCutoff());

        // then: 만료 회수가 종결시켜 증강 1건도 확정된다
        assertThat(augJobRepository.findById(s.augJobSn()).orElseThrow().isTerminal()).isTrue();
        assertThat(augRepository.findById(s.dataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_REJECTED);
    }

    // ─── ③ 콜백 400 고착 ─────────────────────────────────────

    @Test
    @DisplayName("콜백_400_으로_비종결인_job_도_결국_회수된다")
    void rejectedCallbackJobIsEventuallyRecovered() {
        // given: 허용 루트 밖 산출 경로 → 400 으로 거부하되 상태를 바꾸지 않는다(재전송 여지 보존)
        Seed s = seed("REJECT", LsDataAugJob.STTS_RECEIVED);
        GenAiCallbackRequest bad = new GenAiCallbackRequest(
                s.requestId(), "J-" + s.requestId(), "SUCCEEDED", 100, "COMPLETED",
                "2026-07-27T10:00:00Z",
                List.of(new GenAiCallbackRequest.ResultItem(
                        "gen-1", "IMAGE", "/etc/passwd", null)),
                null, null);

        assertThatThrownBy(() -> callbackService.handle(bad))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .as("발급 게이트/오배송이 아니라 산출 경로 거부(400)로 막혀야 한다")
                .isEqualTo(ErrorCode.INVALID_INPUT);
        assertThat(augJobRepository.findById(s.augJobSn()).orElseThrow().isTerminal())
                .as("400 거부는 상태를 바꾸지 않는다(외부 재전송 대기)")
                .isFalse();

        // when: 외부가 재시도를 포기해 무갱신 경과 임계를 넘겼다
        sweeper.sweep(elapsedCutoff());

        // then: 무한 대기가 아니라 만료로 회수된다
        assertThat(augJobRepository.findById(s.augJobSn()).orElseThrow().isTerminal()).isTrue();
        assertThat(augRepository.findById(s.dataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_REJECTED);
    }

    // ─── ④ 2노드 동시 스윕 ────────────────────────────────────

    @Test
    @DisplayName("두_노드가_동시에_스윕해도_같은_job_을_중복_회수하지_않는다")
    void concurrentSweepClaimsJobExactlyOnce() throws Exception {
        // given
        Seed s = seed("RACE", LsDataAugJob.STTS_RECEIVED);
        LocalDateTime cutoff = elapsedCutoff();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        AtomicReference<Throwable> err = new AtomicReference<>();
        try {
            Future<?> a = pool.submit(() -> claim(s, cutoff, ready, start, claimed, err));
            Future<?> b = pool.submit(() -> claim(s, cutoff, ready, start, claimed, err));
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            a.get(60, TimeUnit.SECONDS);
            b.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // then: 조건부 UPDATE 클레임이 한쪽만 통과한다(2노드 Active-Active 중복 회수 방지)
        assertThat(err.get()).as("동시 스윕은 예외 없이 직렬화돼야 한다").isNull();
        assertThat(claimed.get()).isEqualTo(1);
        assertThat(augJobRepository.findById(s.augJobSn()).orElseThrow().getJobSttsCd())
                .isEqualTo(LsDataAugJob.STTS_FAILED);
        assertThat(countChildren(s.parentRawSn())).isZero();
    }

    private Void claim(Seed s, LocalDateTime cutoff, CountDownLatch ready, CountDownLatch start,
                       AtomicInteger claimed, AtomicReference<Throwable> err) {
        try {
            ready.countDown();
            start.await();
            if (expiryTxService.expire(s.augJobSn(), s.dataAugSn(), cutoff)) {
                claimed.incrementAndGet();
            }
        } catch (Throwable t) {
            err.set(t);
        }
        return null;
    }

    // ─── ⑤ 보류 ↔ 만료 스윕 상호작용 (Phase 8-B) ──────────────

    /**
     * 정책 보류(PII)는 <b>실패가 아니라 정상 대기</b>다. 만료 스윕의 회수 대상은 비종결 job
     * (RECEIVED/RUNNING)뿐이므로, 보류된 증강(전 job SUCCEEDED / 또는 job 0건)은 임계를 아무리
     * 넘겨도 후보가 되지 않아야 한다. 회수되면 dead-letter 가 찍혀 신고 해소 시 재개가 불가능해진다.
     */
    @Test
    @DisplayName("보류_상태는_만료_스윕이_회수하지_않는다")
    void withheldAugmentIsNotReclaimedBySweep() {
        // given ① 결과 인계 보류 — 위탁 job 은 SUCCEEDED 로 종결, 증강만 PENDING
        Seed done = seed("WITHHELD", LsDataAugJob.STTS_RECEIVED);
        LsDataAugJob job = augJobRepository.findById(done.augJobSn()).orElseThrow();
        job.markSucceeded("J-" + done.requestId());
        augJobRepository.save(job);

        // given ② 위탁 전 보류 — job 행 자체가 없는 PENDING 증강
        LsDataAug submitWithheld = augRepository.save(LsDataAug.createRequested(
                srcRepository.save(LsDataSrc.create(done.parentRawSn(), 1,
                        done.parentRawSn() + "/f1.jpg", null)).getSrcSn(),
                LsDataAug.AUG_NIGHT, "1", "AJEX-K-WITHHELD-" + UUID.randomUUID(), null));

        // when: 임계를 한참 넘긴 cutoff 로 스윕
        sweeper.sweep(elapsedCutoff());

        // then: 둘 다 그대로 PENDING 이고 실패로 못박히지 않는다
        assertThat(augJobRepository.findById(done.augJobSn()).orElseThrow().getJobSttsCd())
                .as("종결된 job 을 만료가 덮어쓰면 늦게 온 성공이 사라진다")
                .isEqualTo(LsDataAugJob.STTS_SUCCEEDED);
        LsDataAug resultWithheld = augRepository.findById(done.dataAugSn()).orElseThrow();
        assertThat(resultWithheld.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);
        assertThat(resultWithheld.isProcessingFailed())
                .as("보류를 실패로 회수하면 재개 트리거가 깨운 뒤에도 되살릴 수 없다")
                .isFalse();
        LsDataAug reloadedSubmitWithheld =
                augRepository.findById(submitWithheld.getDataAugSn()).orElseThrow();
        assertThat(reloadedSubmitWithheld.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);
        assertThat(reloadedSubmitWithheld.isProcessingFailed()).isFalse();
    }

    /** 만료 종결은 <b>실패로 보여야</b> 한다 — dead-letter 가 없으면 집계가 COMPLETED 로 오분류한다. */
    @Test
    @DisplayName("만료_종결된_증강이_실패로_집계된다")
    void expiredAugmentIsMarkedAsProcessingFailure() {
        Seed s = seed("DEADLETTER", LsDataAugJob.STTS_RUNNING);

        sweeper.sweep(elapsedCutoff());

        LsDataAug aug = augRepository.findById(s.dataAugSn()).orElseThrow();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
        assertThat(aug.isProcessingFailed())
                .as("만료 종결이 검수 반려와 구분되지 않으면 실패한 증강이 '완료' 로 보인다")
                .isTrue();
        assertThat(aug.getRetryCount()).isPositive();
    }

    // ─── ⑦ 회수 축 ② — job 행 0건 장기 PENDING 증강 (적대검증 2차 MEDIUM-2) ───

    /** 부모(비식별 완료 'Y') + 프레임 1건 + <b>job 행 없는</b> PENDING 외부 증강. */
    private Seed seedWithoutJob(String suffix, String deIdentYn) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "AJEX-" + suffix + "-" + UUID.randomUUID(), "CCTV-AJEX", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/AJEX-" + suffix + ".mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified(deIdentYn);
        parent = videoRepository.save(parent);
        LsDataSrc frame = srcRepository.save(
                LsDataSrc.create(parent.getRawSn(), 0, parent.getRawSn() + "/f0.jpg", null));
        String requestId = "AJEX-K-" + suffix + "-" + UUID.randomUUID();
        LsDataAug aug = augRepository.save(LsDataAug.createRequested(
                frame.getSrcSn(), LsDataAug.AUG_WINTER, "1", requestId, null));
        return new Seed(parent.getRawSn(), aug.getDataAugSn(), null, requestId);
    }

    /**
     * 위탁 전 실패 롤업이 예외로 끝나면 <b>PENDING + job 0건</b>이 남는다. job 축 스윕은 이를 보지 못하고,
     * 유일한 재개 트리거(비식별 신고 해제)는 신고가 없었던 영상에서 <b>영원히 발생하지 않는다</b>.
     */
    @Test
    @DisplayName("job_행_0건_장기_PENDING_증강이_회수된다")
    void orphanPendingAugmentIsReclaimed() {
        // given: 신고 없는(=재개 트리거 없는) 영상의 고아 PENDING 증강
        Seed s = seedWithoutJob("ORPHAN", "Y");
        assertThat(augJobRepository.findByDataAugSnOrderByJobSeqAsc(s.dataAugSn())).isEmpty();

        // when: 임계를 넘긴 cutoff 로 고아 축 스윕
        int reclaimed = sweeper.sweepOrphanPendingAugments(elapsedCutoff());

        // then: 실패로 확정돼 집계에 드러난다(무기한 PENDING 고착 해소)
        assertThat(reclaimed).isPositive();
        LsDataAug aug = augRepository.findById(s.dataAugSn()).orElseThrow();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_REJECTED);
        assertThat(aug.isProcessingFailed())
                .as("dead-letter 가 없으면 집계가 이 실패를 '완료' 로 오분류한다")
                .isTrue();
        assertThat(countChildren(s.parentRawSn()))
                .as("회수는 실패 확정이다 — 파생 영상이 생기면 안 된다")
                .isZero();
    }

    /**
     * 정책 보류(비식별 누락 신고 구간)는 <b>정상 대기</b>다. 회수하면 dead-letter 가 찍혀 신고 해제
     * 재개가 불가능해지므로, job 0건이어도 회수 대상이 아니다.
     */
    @Test
    @DisplayName("정상_보류는_job_0건이어도_회수되지_않는다")
    void withheldSubmitIsNotReclaimedByOrphanSweep() {
        // given: 부모가 신고 구간('F') — 위탁이 정책 보류된 상태
        Seed s = seedWithoutJob("WITHHELD-ORPHAN", "F");

        // when
        sweeper.sweepOrphanPendingAugments(elapsedCutoff());

        // then ①: 후보 조회 단계에서 이미 제외된다(신고 구간 필터)
        assertThat(orphanCandidates()).doesNotContain(s.dataAugSn());
        // then ②: 회수 트랜잭션을 직접 불러도 잠금 하 재판정이 거부한다(후보~회수 사이 신고 커밋 대비)
        assertThat(expiryTxService.expireOrphanPending(s.dataAugSn()))
                .as("보류를 실패로 회수하면 신고 해제가 깨운 뒤에도 되살릴 수 없다")
                .isFalse();
        LsDataAug aug = augRepository.findById(s.dataAugSn()).orElseThrow();
        assertThat(aug.getAugProcSttsCd()).isEqualTo(LsDataAug.STTS_PENDING);
        assertThat(aug.isProcessingFailed()).isFalse();
    }

    /** 위탁 직후(임계 이전)의 짧은 창은 회수하지 않는다 — 아직 job 선기록 중일 수 있다. */
    @Test
    @DisplayName("임계_이전_증강은_job_0건이어도_회수되지_않는다")
    void freshPendingAugmentIsNotReclaimed() {
        Seed s = seedWithoutJob("FRESH", "Y");

        // when: cutoff 를 과거로 두면 방금 요청된 증강은 후보가 아니다
        sweeper.sweepOrphanPendingAugments(LocalDateTime.now().minusMinutes(5));

        assertThat(augRepository.findOrphanPendingAugSns(
                LsDataAug.STTS_PENDING, AugmentPrompts.EXTERNAL_AUG_TYPES,
                DeidentReportGate.DEIDENT_FAILED, LocalDateTime.now().minusMinutes(5), 50))
                .doesNotContain(s.dataAugSn());
        assertThat(augRepository.findById(s.dataAugSn()).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_PENDING);
    }

    /** 임계 경과 후보 목록(다른 테스트 행이 섞여도 무해하도록 <b>포함 여부</b>만 본다). */
    private List<Long> orphanCandidates() {
        return augRepository.findOrphanPendingAugSns(
                LsDataAug.STTS_PENDING, AugmentPrompts.EXTERNAL_AUG_TYPES,
                DeidentReportGate.DEIDENT_FAILED, elapsedCutoff(), 500);
    }

    /** 해상도 파생({@code RESL_*})은 외부 위탁·콜백 대상이 아니므로 회수 축에 들어오면 안 된다. */
    @Test
    @DisplayName("해상도_파생은_고아_회수_대상이_아니다")
    void resolutionDerivativeIsNotReclaimed() {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "AJEX-RESL-" + UUID.randomUUID(), "CCTV-AJEX", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/AJEX-RESL.mp4", LocalDateTime.now(), 30));
        parent.markDeidentified("Y");
        parent = videoRepository.save(parent);
        LsDataSrc frame = srcRepository.save(
                LsDataSrc.create(parent.getRawSn(), 0, parent.getRawSn() + "/f0.jpg", null));
        LsDataAug resl = augRepository.save(
                LsDataAug.createResolutionPending(frame.getSrcSn(), LsDataAug.AUG_RESL_720P, "1"));

        sweeper.sweepOrphanPendingAugments(elapsedCutoff());

        assertThat(augRepository.findById(resl.getDataAugSn()).orElseThrow().getAugProcSttsCd())
                .as("내부 해상도 파생을 회수하면 생성 중 파생이 실패로 못박힌다")
                .isEqualTo(LsDataAug.STTS_PENDING);
    }

    // ─── ⑥ 활성원 배선 ────────────────────────────────────────

    @Test
    @DisplayName("스윕이_남의_스케줄러_활성원에_의존하지_않는다")
    void sweeperRunsOnItsOwnDaemonScheduler() {
        // given / then: 스프링 컨텍스트 경유로 기동이 고정된다(@PostConstruct 를 떼면 깨진다)
        assertThat(sweeper.isScheduled())
                .as("전용 데몬 executor 가 컨텍스트 기동 시 떠 있어야 한다")
                .isTrue();
        assertThat(Arrays.stream(AugmentJobExpirySweeper.class.getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(Scheduled.class)))
                .as("@Scheduled 를 쓰면 공유 @EnableScheduling 활성원(남의 잡까지 깨움)에 종속된다")
                .isFalse();
    }
}
