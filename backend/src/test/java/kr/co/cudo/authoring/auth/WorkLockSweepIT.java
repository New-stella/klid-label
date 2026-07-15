package kr.co.cudo.authoring.auth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import kr.co.cudo.authoring.auth.entity.LsAuthWorkLock;
import kr.co.cudo.authoring.auth.repository.LsAuthWorkLockRepository;
import kr.co.cudo.authoring.auth.service.WorkLockService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.Field;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R4 — 만료 작업락 sweeper 통합 테스트 (실 DB, PostgreSQL Testcontainer, non-transactional).
 *
 * <p>{@link WorkLockService#sweepExpiredLocks()} 를 직접 호출해(스케줄러 발화 비의존) 다음을 실증한다:
 * <ul>
 *   <li>만료된 LOCKED 락은 release(EXPIRED_SWEEP) 되고 감사 WARN 로그가 남는다(HIGH 시나리오).</li>
 *   <li>만료 전(진행 중) 락은 미회수.</li>
 *   <li>expireDt=null LOCKED 락은 영구 미회수(null-safe).</li>
 *   <li>이미 RELEASED 된 만료 락은 skip(멱등).</li>
 * </ul>
 * 데이터는 고유 rawSn 파생이며 {@code @AfterEach} 에서 명시 정리한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class WorkLockSweepIT {

    @Autowired private WorkLockService workLockService;
    @Autowired private LsAuthWorkLockRepository lockRepository;
    @Autowired private VideoRepository rawRepository;

    private Long rawSn;

    @BeforeEach
    void setup() {
        LsDataRaw raw = LsDataRaw.createFromIngest(
                "CLIP-SWEEP-" + System.nanoTime(), "CCTV-SWEEP", "EVT-S", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/sweep.mp4", LocalDateTime.now(), 30);
        rawSn = rawRepository.save(raw).getRawSn();
    }

    @AfterEach
    void cleanup() {
        lockRepository.findAll().stream()
                .filter(l -> rawSn.equals(l.getDataRawSn()))
                .forEach(lockRepository::delete);
        rawRepository.deleteById(rawSn);
    }

    /** LOCKED 락을 만들고 expireDt 를 지정 값으로 override 하여 저장(팩토리는 항상 미래 만료라 backdate 필요). */
    private LsAuthWorkLock persistLockedWithExpiry(LocalDateTime expireDt) {
        LsAuthWorkLock lock = LsAuthWorkLock.lockRaw(rawSn, "owner-9", LsAuthWorkLock.REASON_MERGE);
        setField(lock, "expireDt", expireDt);
        return lockRepository.saveAndFlush(lock);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = LsAuthWorkLock.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("만료된_LOCKED_락은_sweep에서_release되고_감사로그")
    void 만료된_LOCKED_락은_sweep에서_release되고_감사로그() {
        LsAuthWorkLock lock = persistLockedWithExpiry(LocalDateTime.now().minusHours(2));

        Logger svcLogger = (Logger) LoggerFactory.getLogger(WorkLockService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        svcLogger.addAppender(appender);
        try {
            int reclaimed = workLockService.sweepExpiredLocks();

            assertThat(reclaimed).isGreaterThanOrEqualTo(1);
            LsAuthWorkLock reloaded = lockRepository.findById(lock.getWorkLockSn()).orElseThrow();
            assertThat(reloaded.getLockSttsCd()).isEqualTo(LsAuthWorkLock.STATUS_RELEASED);
            assertThat(reloaded.getReleaseRsn()).isEqualTo(WorkLockService.REASON_EXPIRED_SWEEP);
            assertThat(reloaded.getReleaseDt()).isNotNull();

            assertThat(appender.list)
                    .as("만료 회수 감사 WARN 로그(rawSn 포함)가 남아야 한다")
                    .anyMatch(e -> e.getLevel() == Level.WARN
                            && e.getFormattedMessage().contains("expired lock reclaimed")
                            && e.getFormattedMessage().contains("rawSn=" + rawSn));
        } finally {
            svcLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("만료전_진행중_락은_sweep에서_미회수")
    void 만료전_진행중_락은_sweep에서_미회수() {
        LsAuthWorkLock lock = persistLockedWithExpiry(LocalDateTime.now().plusHours(1));

        workLockService.sweepExpiredLocks();

        LsAuthWorkLock reloaded = lockRepository.findById(lock.getWorkLockSn()).orElseThrow();
        assertThat(reloaded.getLockSttsCd()).isEqualTo(LsAuthWorkLock.STATUS_LOCKED);
    }

    @Test
    @DisplayName("expireDt가_null인_LOCKED락은_sweep에서_영구_미회수")
    void expireDt가_null인_LOCKED락은_영구_미회수() {
        LsAuthWorkLock lock = persistLockedWithExpiry(null);

        workLockService.sweepExpiredLocks();

        LsAuthWorkLock reloaded = lockRepository.findById(lock.getWorkLockSn()).orElseThrow();
        assertThat(reloaded.getLockSttsCd()).isEqualTo(LsAuthWorkLock.STATUS_LOCKED);
    }

    @Test
    @DisplayName("만료락_회수직전_해제후_재획득된_신규_비만료락은_오회수되지_않는다")
    void 만료락_회수직전_해제후_재획득된_신규_비만료락은_오회수되지_않는다() {
        // given: 만료 락 A 를 sweep 이 스냅샷한 직후, 홀더가 A 를 정상 해제하고 동일 rawSn 에
        // 신규 비만료 락 B 를 재획득한 상황(V69 partial unique 는 활성 1건만 허용하므로 A 해제 후 B 생성이 정상).
        LsAuthWorkLock expiredA = persistLockedWithExpiry(LocalDateTime.now().minusHours(2));
        expiredA.release("holder", "MANUAL");
        lockRepository.saveAndFlush(expiredA);

        LsAuthWorkLock freshB = LsAuthWorkLock.lockRaw(rawSn, "owner-B", LsAuthWorkLock.REASON_MERGE);
        LsAuthWorkLock savedB = lockRepository.saveAndFlush(freshB);

        // when: sweep 이 스냅샷한 만료 락 A 의 정체성(PK)으로 회수를 시도한다.
        // rawSn 스코프 회수(구버전)였다면 현재 LOCKED 인 B 를 오회수한다.
        int reclaimed = workLockService.reclaimExpiredLockInNewTx(
                expiredA.getWorkLockSn(), "SYSTEM_SWEEP", WorkLockService.REASON_EXPIRED_SWEEP);

        // then: A 는 이미 RELEASED 라 회수 0 건, 신규 비만료 락 B 는 그대로 LOCKED 유지(배타성 보존, CWE-362).
        assertThat(reclaimed).isZero();
        LsAuthWorkLock reloadedB = lockRepository.findById(savedB.getWorkLockSn()).orElseThrow();
        assertThat(reloadedB.getLockSttsCd()).isEqualTo(LsAuthWorkLock.STATUS_LOCKED);
    }

    @Test
    @DisplayName("이미_RELEASED된_만료락은_sweep이_건너뛴다_멱등")
    void 이미_RELEASED된_만료락은_sweep이_건너뛴다() {
        LsAuthWorkLock lock = LsAuthWorkLock.lockRaw(rawSn, "owner-9", LsAuthWorkLock.REASON_MERGE);
        setField(lock, "expireDt", LocalDateTime.now().minusHours(2));
        lock.release("someone", "MANUAL");
        LsAuthWorkLock saved = lockRepository.saveAndFlush(lock);

        int reclaimed = workLockService.sweepExpiredLocks();

        assertThat(reclaimed).isZero();
        LsAuthWorkLock reloaded = lockRepository.findById(saved.getWorkLockSn()).orElseThrow();
        // sweep 이 건드리지 않아 최초 release 사유가 유지된다.
        assertThat(reloaded.getReleaseRsn()).isEqualTo("MANUAL");
    }
}
