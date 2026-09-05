package kr.co.cudo.authoring.marking.service;

import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.dto.MarkingRequest;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-052 · AC-1032/AC-1033(마킹 축) — 예약 마킹의 활성화·마감 통합 검증 (실 DB, PostgreSQL Testcontainer).
 *
 * <p>단위 테스트가 못 보는 세 가지를 실 DB 로 고정한다.
 * <ol>
 *   <li><b>예약은 활성 부분 유니크({@code UK_LS_MARKING_RAW_ACTVTN})를 점유하지 않는다</b> — 예약이 있는
 *       영상을 사람이 그대로 마킹할 수 있다. 인덱스 술어에 {@code RESERVED} 가 끼면 즉시 409 로 실패한다.</li>
 *   <li><b>동시에 두 번 활성화를 시도해도 1건만 전이한다</b> — 조회 후 변경 방식으로 되돌리면 둘 다
 *       통과해 잔여 배치가 두 번 기동한다(CWE-362).</li>
 *   <li><b>마감된 예약은 재마킹을 막지 않고 적재도 되돌리지 않는다</b>.</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
class MarkingReservationActivationIT {

    @Autowired private MarkingActivationTxService activationService;
    @Autowired private MarkingService markingService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsMarkingRepository markingRepository;

    private static TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(300));
    }

    /** 비식별 완료 + MARKING_READY 영상 시드 — 마킹 프리컨디션 통과 대상. */
    private Long seedVideo() {
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "MKRS-" + UUID.randomUUID(), "CCTV-MKRS", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/mkrs.mp4",
                LocalDateTime.now(), 60));
        raw.markDeidentified("Y");
        raw.markMarkingReady();
        return videoRepository.save(raw).getRawSn();
    }

    private Long seedReservation(Long rawSn) {
        return markingRepository.save(LsMarking.createReserved(
                rawSn, "[{\"frameIndex\":120,\"timestamp\":\"00:04\"}]", "admin-1", 30.0)).getMarkingSn();
    }

    @Test
    @DisplayName("★예약이_있어도_사람이_그_영상을_마킹할_수_있다 — 예약은_활성_유니크를_점유하지_않는다")
    void reservationDoesNotOccupyActiveUnique() {
        Long rawSn = seedVideo();
        Long reservedSn = seedReservation(rawSn);

        // when — 사람이 그 영상을 직접 마킹한다(비식별이 끝내 실패해 예약이 남아 있는 상황).
        markingService.create(rawSn, new MarkingRequest("MANUAL", null, List.of(new MarkItem(10, "00:05"))),
                reviewer());

        // then — 409 없이 성공하고 예약은 그대로 남는다.
        List<LsMarking> rows = markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(rawSn);
        assertThat(rows).as("예약 1건 + 사람이 찍은 마킹 1건").hasSize(2);
        assertThat(markingRepository.findById(reservedSn)).get()
                .extracting(LsMarking::getSttsCd).isEqualTo(LsMarking.STATUS_RESERVED);
    }

    @Test
    @DisplayName("★동시에_두_번_활성화해도_1건만_전이한다 — 이후_처리_중복_기동_금지")
    void concurrentActivationClaimsOnce() throws Exception {
        Long rawSn = seedVideo();
        Long reservedSn = seedReservation(rawSn);

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    if (activationService.activateReserved(rawSn).isPresent()) {
                        claimed.incrementAndGet();
                    }
                    return null;
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(claimed.get()).as("두 노드 가운데 한쪽만 집어 간다").isEqualTo(1);
        assertThat(markingRepository.findByRawSnAndSttsCd(rawSn, LsMarking.STATUS_RESERVED))
                .as("예약은 남아 있지 않다").isEmpty();
        // 활성화 이후 잔여 배치가 비동기로 상태를 더 올릴 수 있으므로 "예약이 아님"까지만 고정한다.
        assertThat(markingRepository.findById(reservedSn)).get()
                .extracting(LsMarking::getSttsCd).isNotEqualTo(LsMarking.STATUS_RESERVED);
    }

    @Test
    @DisplayName("★비식별_실패로_예약을_마감해도_재마킹이_가능하고_적재는_되돌아가지_않는다")
    void closedReservationKeepsVideoAndAllowsRemarking() {
        Long rawSn = seedVideo();
        Long reservedSn = seedReservation(rawSn);

        // when — 비식별이 끝내 실패해 예약을 적용하지 못한 채 마감한다.
        int closed = activationService.closeReservations(rawSn, "deidentify failed");

        // then — 마킹만 마감되고 영상 적재는 그대로다.
        assertThat(closed).isEqualTo(1);
        assertThat(markingRepository.findById(reservedSn)).get()
                .extracting(LsMarking::getSttsCd).isEqualTo(LsMarking.STATUS_SKIPPED);
        Optional<LsDataRaw> raw = videoRepository.findById(rawSn);
        assertThat(raw).as("적재 자체는 되돌리지 않는다").isPresent();
        assertThat(raw.get().getRawFilePathNm()).isEqualTo("/storage/raw/mkrs.mp4");

        // 그리고 사람이 다시 마킹할 수 있다(마감된 마킹은 활성 집합 밖이라 409 가 걸리지 않는다).
        markingService.create(rawSn, new MarkingRequest("MANUAL", null, List.of(new MarkItem(10, "00:05"))),
                reviewer());
        assertThat(markingRepository.findByRawSnAndSttsCdIn(rawSn, LsMarking.ACTIVE_STATUSES))
                .as("재마킹으로 생긴 활성 마킹 1건").hasSize(1);
    }
}
