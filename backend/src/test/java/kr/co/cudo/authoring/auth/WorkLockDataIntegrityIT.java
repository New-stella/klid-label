package kr.co.cudo.authoring.auth;

import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.repository.LsAuthWorkLockRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.GlobalExceptionHandler;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
 * R2/R4 회귀 — 작업락 partial unique index(V69) 위반이 실 DB 에서 409 로 매핑되는지 <b>경험적으로</b> 검증한다.
 *
 * <p>합성 예외가 아닌 <b>실제 PostgreSQL 제약 위반</b>을 발생시켜 Hibernate/Spring 이 채운 제약명이
 * {@link GlobalExceptionHandler} 에서 CONFLICT(409)로 분기되는지 확인한다(대소문자 실측 반영). 또한 동시
 * 락 경합에서 패자의 예외가 409 로 귀결되고 활성 락이 정확히 1건인지(회귀 가드) 검증한다.
 *
 * <p>non-transactional — {@code lockRawExclusiveInNewTx} 의 REQUIRES_NEW 커밋을 실제 관측해야 하며,
 * 데이터는 {@code @AfterEach} 에서 명시 정리한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class WorkLockDataIntegrityIT {

    @Autowired private WorkLockService workLockService;
    @Autowired private LsAuthWorkLockRepository lockRepository;
    @Autowired private VideoRepository rawRepository;

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private Long rawSn;

    @BeforeEach
    void setup() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-DIV-" + System.nanoTime(), "CCTV-DIV", "EVT-D", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/div.mp4", LocalDateTime.now(), 30);
        rawSn = rawRepository.save(raw).getRawSn();
    }

    @AfterEach
    void cleanup() {
        lockRepository.findAll().stream()
                .filter(l -> rawSn.equals(l.getDataRawSn()))
                .forEach(lockRepository::delete);
        rawRepository.deleteById(rawSn);
    }

    private int activeLockCount() {
        return lockRepository.findAllByLockTargetCdAndDataRawSnAndLockSttsCd(
                LsAuthWorkLock.TARGET_RAW, rawSn, LsAuthWorkLock.STATUS_LOCKED).size();
    }

    private int statusOf(Throwable t) {
        if (t instanceof CustomException ce) {
            return ce.getErrorCode().status().value();
        }
        if (t instanceof DataIntegrityViolationException dive) {
            return handler.handleDataIntegrityViolation(dive).getStatusCode().value();
        }
        return -1;
    }

    @Test
    @DisplayName("동일rawSn_두번째_LOCKED_insert_실DB_unique위반은_핸들러에서_409")
    void 실DB_unique위반은_핸들러에서_409() {
        lockRepository.saveAndFlush(LsAuthWorkLock.lockRaw(rawSn, "1", LsAuthWorkLock.REASON_MERGE));

        DataIntegrityViolationException ex = catchThrowableOfType(
                () -> lockRepository.saveAndFlush(
                        LsAuthWorkLock.lockRaw(rawSn, "2", LsAuthWorkLock.REASON_MERGE)),
                DataIntegrityViolationException.class);

        assertThat(ex).as("동일 rawSn 두 번째 활성 락은 V69 partial unique index 로 거부되어야 한다").isNotNull();
        // 경험적 검증: 실제 PG 제약명이 핸들러에서 409 로 분기된다.
        assertThat(handler.handleDataIntegrityViolation(ex).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    @DisplayName("동시_같은rawSn_락경합_패자는_409_CONFLICT")
    void 동시_같은rawSn_락경합_패자는_409_CONFLICT() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger success = new AtomicInteger();

        Callable<Void> task = () -> {
            ready.countDown();
            start.await();
            try {
                workLockService.lockRawExclusiveInNewTx(rawSn, "1");
                success.incrementAndGet();
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

        assertThat(success.get()).as("정확히 한 요청만 락 선점").isEqualTo(1);
        assertThat(errors).as("패자는 정확히 1건").hasSize(1);
        assertThat(statusOf(errors.get(0)))
                .as("패자 예외는 409 로 귀결(CustomException CONFLICT 또는 unique 위반→핸들러 409)")
                .isEqualTo(409);
        assertThat(activeLockCount()).as("활성 LOCKED 락은 정확히 1건").isEqualTo(1);
    }
}
