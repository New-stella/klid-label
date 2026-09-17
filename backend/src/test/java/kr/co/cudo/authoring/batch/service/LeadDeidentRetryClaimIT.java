package kr.co.cudo.authoring.batch.service;

import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.repository.LsAuthWorkLockRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 선두 비식별 실패 영상의 재시작 판정·잠금 선점을 실제 PostgreSQL 에서 본다.
 *
 * <p>단위 시험이 흉내 낸 두 가지 — 영상 단위 활성 잠금 유일 인덱스로 동시 요청 중 1건만 수락되는 것, 진행 중
 * 위탁 조회가 재폴링 대상 두 값만 보는 것 — 를 실 DB 로 확인한다. 실행기는 부르지 않는다(판정 창구만 본다).
 *
 * <p>컨텍스트 키는 {@code WorkLockDataIntegrityIT} 와 같다(애노테이션 동일 · 목 없음).
 *
 * @design AC-1134
 * @design AC-1135
 */
@SpringBootTest
@ActiveProfiles("local")
class LeadDeidentRetryClaimIT {

    @Autowired private LeadDeidentRetryService leadDeidentRetryService;
    @Autowired private WorkLockService workLockService;
    @Autowired private LsAuthWorkLockRepository lockRepository;
    @Autowired private LsDeidentProcLogRepository procLogRepository;
    @Autowired private VideoRepository rawRepository;

    private Long rawSn;

    @BeforeEach
    void setup() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-LDR-" + System.nanoTime(), "CCTV-LDR", "EVT-L", "11680",
                LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/ldr.mp4", LocalDateTime.now(), 30);
        raw.markDeidentified("F");
        rawSn = rawRepository.save(raw).getRawSn();
    }

    @AfterEach
    void cleanup() {
        lockRepository.findAll().stream()
                .filter(l -> rawSn.equals(l.getDataRawSn()))
                .forEach(lockRepository::delete);
        procLogRepository.findAllByDataRawSnOrderByReqDtDesc(rawSn).forEach(procLogRepository::delete);
        rawRepository.deleteById(rawSn);
    }

    private List<LsAuthWorkLock> active() {
        return lockRepository.findAllByLockTargetCdAndDataRawSnAndLockSttsCd(
                LsAuthWorkLock.TARGET_RAW, rawSn, LsAuthWorkLock.STATUS_LOCKED);
    }

    @Test
    @DisplayName("★같은_영상_동시_재시작_두건_중_정확히_한건만_수락되고_나머지는_409")
    void 동시요청_한건만() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger accepted = new AtomicInteger();
        Callable<Void> task = () -> {
            ready.countDown();
            start.await();
            try {
                if (leadDeidentRetryService.tryClaim(rawSn)) {
                    accepted.incrementAndGet();
                }
            } catch (Throwable t) {
                errors.add(t);
            }
            return null;
        };
        try {
            Future<?> a = pool.submit(task);
            Future<?> b = pool.submit(task);
            ready.await(3, TimeUnit.SECONDS);
            start.countDown();
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0)).isInstanceOf(CustomException.class);
        CustomException loser = (CustomException) errors.get(0);
        assertThat(loser.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(loser.getMessage()).isEqualTo(LeadDeidentRetryService.LOCKED_REASON);
        assertThat(active()).hasSize(1);
        assertThat(active().get(0).isDeidentRetryLock()).isTrue();
    }

    @Test
    @DisplayName("진행중_위탁_원장이_있으면_409이고_잠금이_남지_않는다_종결원장은_막지_않는다")
    void 진행중위탁() {
        LsDeidentProcLog failed = LsDeidentProcLog.request(rawSn, null, "/var/raw/ldr.mp4", "batch");
        failed.markKpstSubmitPending();
        failed.fail("KPST_SUBMIT_FAILED", "x");
        procLogRepository.saveAndFlush(failed);
        LsDeidentProcLog waiting = LsDeidentProcLog.request(rawSn, null, "/var/raw/ldr.mp4", "batch");
        waiting.markKpstSubmitPending();
        procLogRepository.saveAndFlush(waiting);

        CustomException e = catchThrowableOfType(() -> leadDeidentRetryService.tryClaim(rawSn),
                CustomException.class);

        assertThat(e.getMessage()).isEqualTo(LeadDeidentRetryService.IN_FLIGHT_REASON);
        assertThat(active()).isEmpty();

        // 진행 중 원장이 종결되면(실패 원장만 남으면) 다시 수락된다.
        waiting.fail("KPST_SUBMIT_FAILED", "y");
        procLogRepository.saveAndFlush(waiting);
        assertThat(leadDeidentRetryService.tryClaim(rawSn)).isTrue();
    }

    @Test
    @DisplayName("★재시작_잠금을_풀면_재요청이_수락되고_다른_기능의_잠금은_풀지_않는다")
    void 해제후_재수락_타기능잠금_유지() {
        assertThat(leadDeidentRetryService.tryClaim(rawSn)).isTrue();
        assertThat(catchThrowableOfType(() -> leadDeidentRetryService.tryClaim(rawSn), CustomException.class))
                .isNotNull();

        assertThat(workLockService.releaseDeidentRetryLockInNewTx(rawSn, "batch", "T")).isEqualTo(1);
        assertThat(active()).isEmpty();

        // 다른 기능(트랙 병합)이 잡으면 재시작은 409, 재시작 해제는 그 잠금을 건드리지 않는다.
        workLockService.lockRawExclusiveInNewTx(rawSn, "1");
        CustomException e = catchThrowableOfType(() -> leadDeidentRetryService.tryClaim(rawSn),
                CustomException.class);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(workLockService.releaseDeidentRetryLockInNewTx(rawSn, "batch", "T")).isZero();
        assertThat(active()).hasSize(1);
        assertThat(active().get(0).isDeidentRetryLock()).isFalse();

        workLockService.releaseRawInNewTx(rawSn, "1", "T");
        assertThat(leadDeidentRetryService.tryClaim(rawSn)).isTrue();
    }
}
