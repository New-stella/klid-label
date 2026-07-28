package kr.co.cudo.authoring.webhook;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.service.DeidentReportService;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.AugmentResultRequest;
import kr.co.cudo.authoring.webhook.service.AugmentResultService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * Phase 5 증강 콜백 동시성 방어 통합 테스트 (실 DB, PostgreSQL Testcontainer).
 *
 * <p>비관적 락(PESSIMISTIC_WRITE) 두 방어선을 프로덕션 경로로 실증한다:
 * <ul>
 *   <li><b>MED #2 — 중복 콜백 레이스</b>: 같은 {@code dataAugSn} 에 서로 다른 {@code otsd_job_id} 를 가진
 *       동시 콜백 2건 → {@code findByDataAugSnForUpdate} 행 잠금이 직렬화해 <b>증강 영상은 정확히 1건</b>만
 *       생성된다(2차 UNIQUE 앵커는 job_id 가 달라 미발동 — 락이 유일 방어).</li>
 *   <li><b>HIGH #1 — PII TOCTOU</b>: 동시 비식별 신고가 부모 RAW 행 잠금을 선점해 {@code DE_IDNTF_YN='F'}
 *       를 먼저 커밋하면, 증강 콜백은 부모 {@code findByRawSnForUpdate} 에서 대기 후 {@code 'F'} 를 관측해
 *       증강본 생성을 보류한다(PII 파생본 스트리밍 차단).</li>
 * </ul>
 *
 * <p><b>회귀 가드 설계 (HIGH #1)</b>: 신고 홀더는 <b>실제 {@link DeidentReportService#report}</b> 를 호출한다
 * (과거 테스트처럼 손수 만든 {@code findByRawSnForUpdate}+flush 미러가 아님 — 그 미러는 프로덕션의 read 방법
 * 선택과 무관하게 항상 락을 잡아 결함을 은폐했다). {@link WorkLockService#lockRawForRedeident} 를
 * {@link SpyBean} 으로 가로채 <b>부모 read 직후·{@code markDeidentified('F')} 쓰기 이전</b> 의 결정 창에서
 * report tx 를 멈춘다. 이 창 안에서 증강 콜백을 돌리면:
 * <ul>
 *   <li>report 가 부모를 {@code findByRawSnForUpdate}(수정본)로 잠갔으면 → 증강은 부모 FOR UPDATE 에서
 *       대기하다 report 커밋('F') 후 'F' 를 관측 → 자식 생성 보류(child=0, GREEN).</li>
 *   <li>report 가 {@code findById}(결함 미수정)로 <b>비잠금</b> read 했으면 → 'F' UPDATE 가 아직 flush 되지
 *       않아(PostgreSQL 은 UPDATE 시점에야 row write-lock 획득) 증강의 FOR UPDATE 는 대기 없이 커밋된 'Y' 를
 *       읽어 PII 파생 증강본을 만든다(child=1, RED).</li>
 * </ul>
 * 즉 결함 #1 수정을 되돌리면 본 테스트는 실패한다.
 *
 * <p>공유 Testcontainers PG 를 사용하므로 시드 영상은 {@code AUGCC-} 고유 clipId 로 만들고, 단언은 시드한
 * parentRawSn 파생 영상으로만 좁혀 다른 통합테스트 데이터 오염을 일으키지 않는다.
 */
@SpringBootTest
@ActiveProfiles("local")
// B-2 — 증강 콜백의 raw_file_path_nm 은 적재 시점에 고정 allowlist(마운트 루트) 하위인지 검증된다.
//   이 IT 의 픽스처 경로(/storage/augment/*.mp4)가 통과하도록 마운트 루트를 명시한다.
@TestPropertySource(properties = {
        "authoring.storage.raw-mount-roots=/storage"
})
class AugmentDeidentConcurrencyIT {

    @Autowired private AugmentResultService service;
    @Autowired private DeidentReportService deidentReportService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsDataAugRepository augRepository;

    /**
     * HIGH #1 회귀 가드용 pause hook. {@code report} 가 부모 read 를 마치고 {@code markDeidentified('F')}
     * 이전 단계(작업락 획득)에 진입할 때 tx 를 멈춰 결정 창을 연다. AugmentResultService 는
     * {@code lockRawForRedeident} 를 호출하지 않으므로 MED #2 테스트에는 영향이 없다.
     */
    @SpyBean private WorkLockService workLockService;

    private record Seed(LsDataRaw parent, LsDataSrc frame0, LsDataAug aug) {
    }

    /** 부모(비식별 완료 'Y') + 프레임 2건 + 라벨 1건 + LS_DATA_AUG(PENDING) 시드. */
    private Seed seed(String suffix, String augType, String externalJobIdPlaceholder) {
        LsDataRaw parent = videoRepository.save(LsDataRaw.createFromIngest(
                "AUGCC-" + suffix, "CCTV-AUGCC", "EVT", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/AUGCC-" + suffix + ".mp4",
                LocalDateTime.now(), 30));
        parent.markDeidentified("Y");
        parent = videoRepository.save(parent);
        LsDataSrc frame0 = srcRepository.save(
                LsDataSrc.create(parent.getRawSn(), 0, parent.getRawSn() + "/f0.jpg", null));
        srcRepository.save(
                LsDataSrc.create(parent.getRawSn(), 1, parent.getRawSn() + "/f1.jpg", null));
        lblRepository.save(LsDataLbl.createAutoBbox(
                frame0.getSrcSn(), null, "person", "[10,20,30,40]",
                BigDecimal.valueOf(0.9), null));
        LsDataAug aug = augRepository.save(LsDataAug.createRequested(
                frame0.getSrcSn(), augType, "1", "AUGCC-K-" + suffix, externalJobIdPlaceholder));
        return new Seed(parent, frame0, aug);
    }

    private long countChildren(Long parentRawSn) {
        return videoRepository.findAll().stream()
                .filter(r -> parentRawSn.equals(r.getOrgnlRawSn()))
                .count();
    }

    @Test
    @DisplayName("같은_dataAugSn_서로다른_otsdJobId_동시콜백_시_증강영상은_정확히_1건만_생성된다")
    void concurrentCallbacksSameDataAugSn_createExactlyOneVideo() throws Exception {
        Seed s = seed("DUP2", "WINTER", "AUGCC-J-DUP2-INIT");
        Long augSn = s.aug().getDataAugSn();
        Long parentSn = s.parent().getRawSn();

        // 서로 다른 otsd_job_id → 2차 UNIQUE 앵커는 미발동. 오직 dataAugSn 행 잠금이 이중 영상을 막아야 한다.
        AugmentResultRequest reqA = new AugmentResultRequest(
                augSn, "AUGCC-J-DUP2-A", "WINTER", "SUCCESS", "/storage/augment/AUGCC-DUP2-A.mp4");
        AugmentResultRequest reqB = new AugmentResultRequest(
                augSn, "AUGCC-J-DUP2-B", "WINTER", "SUCCESS", "/storage/augment/AUGCC-DUP2-B.mp4");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Boolean> rA = new AtomicReference<>();
        AtomicReference<Boolean> rB = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        try {
            Future<?> a = pool.submit(() -> runHandle(reqA, ready, start, rA, err));
            Future<?> b = pool.submit(() -> runHandle(reqB, ready, start, rB, err));
            ready.await(3, TimeUnit.SECONDS);
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 예외 없이 직렬화. 정확히 한 콜백만 신규 영상 생성(true), 나머지는 멱등 skip(false).
        assertThat(err.get()).isNull();
        assertThat(rA.get() ^ rB.get())
                .as("정확히 한 콜백만 신규 영상 생성(applied=true) (a=%s, b=%s)", rA.get(), rB.get())
                .isTrue();
        assertThat(countChildren(parentSn))
                .as("같은 dataAugSn 동시 콜백에도 증강 영상은 정확히 1건")
                .isEqualTo(1L);
        assertThat(augRepository.findById(augSn).orElseThrow().getAugProcSttsCd())
                .isEqualTo(LsDataAug.STTS_ACCEPTED);
    }

    private Void runHandle(AugmentResultRequest req, CountDownLatch ready, CountDownLatch start,
                           AtomicReference<Boolean> result, AtomicReference<Throwable> err) {
        try {
            ready.countDown();
            start.await();
            result.set(service.handle(req));
        } catch (Throwable t) {
            err.set(t);
        }
        return null;
    }

    @Test
    @DisplayName("실제_신고가_부모락_선점하면_동시_증강콜백은_보류되어_증강영상_미생성_회귀가드")
    void realDeidentReportWinsLock_augmentCallbackBlocksThenSkips() throws Exception {
        Seed s = seed("PII", "NIGHT", "AUGCC-J-PII-INIT");
        Long parentSn = s.parent().getRawSn();
        Long reportSrcSn = s.frame0().getSrcSn(); // 신고 대상 프레임(부모 소속)
        AugmentResultRequest req = new AugmentResultRequest(
                s.aug().getDataAugSn(), "AUGCC-J-PII", "NIGHT", "SUCCESS",
                "/storage/augment/AUGCC-PII.mp4");

        // REVIEWER 는 LabelAccessGuard 를 전체 통과하므로 신고 진입 셋업이 단순하다.
        TokenClaims reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(60));

        // 실제 report(...) 를 부모 read 직후(작업락 획득 단계, 'F' 쓰기 이전)에 멈춰 결정 창을 연다.
        //   D-25 정책 반전으로 구 훅(VersionService.snapshotDeidentReport)이 제거돼, 같은 결정 창
        //   (부모 FOR UPDATE 이후 ~ markDeidentified('F') 이전)에 있는 작업락 획득으로 재배치했다.
        CountDownLatch reportInWindow = new CountDownLatch(1);
        CountDownLatch releaseReport = new CountDownLatch(1);
        doAnswer(inv -> {
            reportInWindow.countDown();          // 부모 read 완료 + 결정 창 진입 신호
            releaseReport.await(15, TimeUnit.SECONDS); // 증강 콜백이 창 안에서 부모 read 시도할 때까지 홀드
            return inv.callRealMethod();
        }).when(workLockService).lockRawForRedeident(any(), any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<Throwable> reportErr = new AtomicReference<>();
        AtomicReference<Throwable> augErr = new AtomicReference<>();
        AtomicReference<Boolean> applied = new AtomicReference<>();
        try {
            // 홀더 스레드 — 실제 신고 서비스 호출(자체 @Transactional). 부모 read 방법은 프로덕션 코드가 결정한다.
            Future<?> reportThread = pool.submit(() -> {
                try {
                    deidentReportService.report(reportSrcSn, "얼굴 미블러 노출", reviewer);
                } catch (Throwable t) {
                    reportErr.set(t);
                    reportInWindow.countDown(); // 조기 실패 시 메인 대기 해제
                }
                return null;
            });

            // report 가 부모 read 를 마치고 결정 창(작업락 단계)에 진입할 때까지 대기.
            assertThat(reportInWindow.await(15, TimeUnit.SECONDS))
                    .as("report 가 결정 창에 진입해야 함").isTrue();

            // 결정 창 안에서 증강 콜백 시작.
            //  · 수정본(findByRawSnForUpdate): 부모 FOR UPDATE 에서 대기 → 이후 'F' 관측 → 자식 보류.
            //  · 미수정(findById): 대기 없이 커밋된 'Y' 읽어 이 창 안에서 자식 생성·커밋 → 회귀 검출.
            Future<?> augThread = pool.submit(() -> {
                try {
                    applied.set(service.handle(req));
                } catch (Throwable t) {
                    augErr.set(t);
                }
                return null;
            });

            // 미수정 경로에서 증강 콜백이 창 안에서 자식 생성을 끝내도록(수정 경로면 계속 대기) 충분히 대기.
            Thread.sleep(1500);

            // 결정 창 해제 → report 가 'F' 커밋 + 부모 락 해제.
            releaseReport.countDown();

            reportThread.get(30, TimeUnit.SECONDS);
            augThread.get(30, TimeUnit.SECONDS);
        } finally {
            releaseReport.countDown();
            pool.shutdownNow();
        }

        assertThat(reportErr.get()).as("신고(report) 예외 없음").isNull();
        assertThat(augErr.get()).as("증강 콜백 예외 없음").isNull();
        // 콜백 자체는 상태 전이(ACCEPTED)까지 정상 처리되지만, 부모 'F' 관측으로 증강 영상은 생성되지 않아야 한다.
        assertThat(applied.get()).as("증강 콜백 자체는 처리(applied=true)").isTrue();
        assertThat(countChildren(parentSn))
                .as("실제 신고가 부모락 선점→'F' 커밋 후, 증강 콜백은 'F' 관측으로 파생본 생성 보류(PII 노출 차단)")
                .isZero();
    }
}
