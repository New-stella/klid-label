package kr.co.cudo.authoring.marking;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.marking.dto.MarkItem;
import kr.co.cudo.authoring.marking.dto.MarkingRequest;
import kr.co.cudo.authoring.marking.entity.LsMarking;
import kr.co.cudo.authoring.marking.repository.LsMarkingRepository;
import kr.co.cudo.authoring.marking.service.MarkingService;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-ISSUE-22 — 동일 {@code rawSn} 활성 마킹 1건 제약의 <b>동시성</b> 통합 테스트
 * (실 DB, PostgreSQL Testcontainer).
 *
 * <p>서비스 사전 조회(select-then-insert)만으로는 동시 3요청이 전부 통과한다 — 세 트랜잭션 모두
 * 커밋 전이라 서로의 행을 보지 못하기 때문이다. 실제 방어는 {@code LS_MARKING} 의 <b>부분 유니크
 * 인덱스</b>(V142, 활성 상태에 한정)이며, 위반은 flush 시점에 {@code DataIntegrityViolationException}
 * 으로 표면화되어 409 로 변환된다.
 *
 * <p>따라서 본 테스트는 "동시 3요청 → 정확히 1건만 성공 + 활성 마킹 행 1건" 을 실 트랜잭션으로 고정한다.
 * 인덱스를 제거하면 3건이 저장돼 즉시 실패한다(회귀 가드).
 */
@SpringBootTest
@ActiveProfiles("local")
class MarkingActiveUniqueConcurrencyIT {

    @Autowired private MarkingService markingService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsMarkingRepository markingRepository;
    @Autowired @Qualifier("controlDataSource") private DataSource controlDataSource;

    private static TokenClaims reviewer() {
        return new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL, Instant.now().plusSeconds(300));
    }

    /** 비식별 완료 + MARKING_READY 영상 시드 — 마킹 프리컨디션 통과 대상. */
    private Long seedVideo() {
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "MKUQ-" + UUID.randomUUID(), "CCTV-MKUQ", "EVT-A", "11680",
                LsDataRaw.PRVC_TYPE_ANONY, "/storage/raw/mkuq.mp4",
                LocalDateTime.now(), 60));
        raw.markDeidentified("Y");
        raw.markMarkingReady();
        return videoRepository.save(raw).getRawSn();
    }

    @Test
    @DisplayName("마킹_동시_3요청에도_활성_마킹은_1건만_생성된다")
    void concurrentCreates_onlyOneActiveMarking() throws Exception {
        Long rawSn = seedVideo();
        // MANUAL 모드 — 대화형 경로와 동일하게 ffprobe 서브프로세스를 태우지 않는다.
        MarkingRequest req = new MarkingRequest("MANUAL", null, List.of(new MarkItem(10, "00:05")));

        int threads = 3;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        try {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++) {
                futures[i] = pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        markingService.create(rawSn, req, reviewer());
                        created.incrementAndGet();
                    } catch (CustomException e) {
                        if (e.getErrorCode() == ErrorCode.CONFLICT) {
                            conflicted.incrementAndGet();
                        } else {
                            other.incrementAndGet();
                        }
                    } catch (Exception e) {
                        other.incrementAndGet();
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

        assertThat(created.get()).as("동시 요청 중 정확히 1건만 마킹 생성에 성공해야 한다").isEqualTo(1);
        assertThat(conflicted.get() + other.get()).as("나머지는 모두 거부돼야 한다").isEqualTo(threads - 1);
        assertThat(other.get()).as("거부는 409(CONFLICT) 로 표면화돼야 한다(500 누수 금지)").isZero();

        List<LsMarking> rows = markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(rawSn);
        assertThat(rows).as("활성 마킹 행은 1건이어야 한다(고아 PENDING 누적 금지)").hasSize(1);
        assertThat(rows.get(0).getSttsCd()).isEqualTo(LsMarking.STATUS_PENDING);
    }

    /**
     * DEV_FIX MEDIUM — 배치 소비 정렬 ↔ V142 백필 정렬 일치 회귀 가드.
     *
     * <p>V142 백필은 남길 1건을 {@code ORDER BY REG_DT DESC, MARKING_SN DESC} 로 결정론적으로 고른다.
     * 배치 소비({@code MarkingLoadStep} → {@code markings.get(0)})가 {@code REG_DT DESC} 단일 정렬이면
     * 동률(동시 요청으로 같은 밀리초 생성 — 본 결함의 발생 원인 그 자체)에서 순서가 DB 물리 저장 순서에
     * 의존해 갈리고, "배치가 실제로 위탁하는 그 1건을 남긴다"는 마이그레이션 전제가 깨진다.
     *
     * <p>검증 형태: 활성 중복 행은 이제 부분 유니크 인덱스가 원천 차단하므로 <b>활성 3행을 실제로
     * 만들 수는 없다</b>. 그래서 두 정렬의 차이가 드러나는 지점 = <b>ORDER BY 절 자체</b>를 비교한다 —
     * 같은 REG_DT 를 가진 행 집합에 대해 V142 의 ORDER BY 를 그대로 실행한 결과와 리포지토리 조회
     * 결과가 동일 순서여야 한다(상태 술어는 정렬과 직교하며 위 동시성 테스트가 이미 고정한다).
     */
    @Test
    @DisplayName("동일_REG_DT_일_때_배치가_고르는_마킹과_V142_가_남기는_마킹이_같다")
    void batchConsumptionOrderMatchesV142BackfillOrder() {
        Long rawSn = seedVideo();
        // 밀리초까지 동일한 REG_DT — 동시 요청으로 같은 순간에 생성된 상황을 그대로 재현한다.
        LocalDateTime sameMoment = LocalDateTime.now().withNano(123_000_000);
        Long snTerminalA = saveMarkingAt(rawSn, sameMoment, LsMarking.STATUS_VLM_FAILED);
        Long snTerminalB = saveMarkingAt(rawSn, sameMoment, LsMarking.STATUS_VLM_FAILED);
        Long snActive = saveMarkingAt(rawSn, sameMoment, LsMarking.STATUS_PENDING);

        // 배치 소비 정렬 — MarkingLoadStep 이 markings.get(0) 으로 위탁 대상을 고르는 그 순서.
        List<Long> consumed = markingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc(rawSn).stream()
                .map(LsMarking::getMarkingSn)
                .toList();

        // V142 백필 정렬 — 마이그레이션의 ORDER BY 절을 문자 그대로 실행한다.
        List<Long> backfillRanked = new JdbcTemplate(controlDataSource).queryForList(
                "SELECT MARKING_SN FROM LS_MARKING WHERE RAW_SN = ?"
                        + " ORDER BY REG_DT DESC, MARKING_SN DESC",
                Long.class, rawSn);

        assertThat(consumed)
                .as("REG_DT 동률에서도 배치 소비 정렬과 V142 백필 정렬이 같아야 한다")
                .containsExactlyElementsOf(backfillRanked);
        assertThat(consumed.get(0))
                .as("동률이면 MARKING_SN 최대값(= V142 가 남기는 rn=1 행)이 위탁 대상이어야 한다")
                .isEqualTo(snActive);
        assertThat(consumed).containsExactly(snActive, snTerminalB, snTerminalA);
    }

    /**
     * REG_DT 를 지정해 마킹 1건을 적재한다 — 동률 상황을 만들기 위한 픽스처.
     * {@code @PrePersist} 는 null 일 때만 채우므로 미리 설정한 값이 그대로 영속된다.
     */
    private Long saveMarkingAt(Long rawSn, LocalDateTime regDt, String sttsCd) {
        LsMarking marking = LsMarking.createManual(
                rawSn,
                "[{\"frameIndex\":10,\"timestamp\":\"00:05\"}]", "1", 30.0);
        if (LsMarking.STATUS_VLM_FAILED.equals(sttsCd)) {
            marking.markVlmFailed();
        }
        ReflectionTestUtils.setField(marking, "regDt", regDt);
        ReflectionTestUtils.setField(marking, "mdfcnDt", regDt);
        return markingRepository.save(marking).getMarkingSn();
    }
}
