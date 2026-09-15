package kr.co.cudo.authoring.assignment.service;

import kr.co.cudo.authoring.assignment.domain.ReviewClaim;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.support.RawVideoFixture;
import kr.co.cudo.authoring.support.ThreadScopedQueryProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 검수 점유 조회 창구의 <b>실 DB</b> 회귀 가드 (ADR-067 · ERD-014).
 *
 * <p>고정하는 것 넷:
 * <ol>
 *   <li>영상이 몇 건이든 <b>조회 한 번</b>으로 끝난다(N+1 금지 — 목록 화면이 이 창구를 쓴다).</li>
 *   <li>검수 시작 뒤에 승인·반려가 오면 <b>같은 조회 결과로</b> 점유가 풀린다.</li>
 *   <li>유예가 지나면 점유가 저절로 풀린다 — <b>시계를 옮겨</b> 만료 전·후를 실제로 지나간다.</li>
 *   <li>행위 시점 역할이 실제로 적재되고, 관리자가 한 행위는 관리자로 남는다.</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("local")
class ReviewClaimSupportIT {

    private static final long GRACE_MINUTES = 30;
    private static final long OWNER = 7L;

    @Autowired private LsTaskEventLogRepository taskEventLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;
    private final List<Long> seededRawSns = new ArrayList<>();

    ReviewClaimSupportIT(@Qualifier("controlTransactionManager") PlatformTransactionManager txManager) {
        this.txTemplate = new TransactionTemplate(txManager);
    }

    @AfterEach
    void cleanSeededVideos() {
        seededRawSns.forEach(sn -> RawVideoFixture.deleteRaws(jdbcTemplate, sn));
        seededRawSns.clear();
    }

    // ------------------------------------------------------------------ 시나리오

    @Test
    @DisplayName("영상이_몇_건이든_점유_조회는_쿼리_한_번이다")
    void claimsOfIsSingleQueryRegardlessOfSize() {
        List<Long> one = seedVideosWithStartReview(1);
        List<Long> many = seedVideosWithStartReview(10);
        ReviewClaimSupport support = supportAt(LocalDateTime.now());

        try (ThreadScopedQueryProbe probe = ThreadScopedQueryProbe.attach()) {
            // 워밍업 — 두 측정의 부수 조회 조건을 맞춘다.
            support.claimsOf(one);
            support.claimsOf(many);

            probe.reset();
            support.claimsOf(one);
            long oneQuery = probe.countOnThisThread();
            String oneForeign = probe.foreignSummary();

            probe.reset();
            support.claimsOf(many);
            long manyQueries = probe.countOnThisThread();
            String manyForeign = probe.foreignSummary();

            assertThat(oneQuery)
                    .as("점유 조회는 영상 수와 무관하게 1회여야 한다. 배경 스레드 개입: [%s]", oneForeign)
                    .isEqualTo(1);
            assertThat(manyQueries)
                    .as("영상 1건 → %d 쿼리, 10건 → %d 쿼리 (행마다 되짚으면 여기서 깨진다). "
                            + "배경 스레드 개입: [%s]", oneQuery, manyQueries, manyForeign)
                    .isEqualTo(oneQuery);
        }
    }

    @Test
    @DisplayName("승인_반려가_뒤에_오면_점유가_풀리고_유예가_남아도_마찬가지다")
    void terminalEventReleasesClaim() {
        long stillOpen = newVideo();
        long approved = newVideo();
        long rejected = newVideo();
        LocalDateTime now = LocalDateTime.now();

        save(LsTaskEventLog.startReview(stillOpen, OWNER, Role.REVIEWER));
        save(LsTaskEventLog.startReview(approved, OWNER, Role.REVIEWER));
        save(LsTaskEventLog.approve(approved, OWNER, Role.REVIEWER));
        save(LsTaskEventLog.startReview(rejected, OWNER, Role.REVIEWER));
        save(LsTaskEventLog.reject(rejected, OWNER, "사유", Role.REVIEWER));

        Map<Long, ReviewClaim> claims =
                supportAt(now).claimsOf(List.of(stillOpen, approved, rejected));

        assertThat(claims).containsOnlyKeys(stillOpen);
        assertThat(claims.get(stillOpen).ownedBy(OWNER)).isTrue();
    }

    @Test
    @DisplayName("★유예_전에는_점유가_있고_유예를_지나면_같은_데이터로도_사라진다")
    void claimExpiresWhenClockMovesPastGrace() {
        long rawSn = newVideo();
        save(LsTaskEventLog.startReview(rawSn, OWNER, Role.REVIEWER));
        LocalDateTime startedAt = latestOcrnDt(rawSn);

        // 데이터는 그대로 두고 <시계만> 옮긴다 — 만료 구간을 실제로 지나가는 것이 이 시험의 목적이다.
        assertThat(supportAt(startedAt.plusMinutes(GRACE_MINUTES - 1)).claimsOf(List.of(rawSn)))
                .as("유예 안에서는 점유가 유지된다")
                .containsKey(rawSn);
        assertThat(supportAt(startedAt.plusMinutes(GRACE_MINUTES + 1)).claimsOf(List.of(rawSn)))
                .as("유예를 지나면 점유를 푸는 동작 없이 저절로 풀린다")
                .isEmpty();
    }

    @Test
    @DisplayName("같은_사람이_다시_열면_점유_시각이_갱신되고_최초_시작_시각은_이력에_남는다")
    void reEntryRefreshesClaimAndKeepsFirstStart() {
        long rawSn = newVideo();
        save(LsTaskEventLog.startReview(rawSn, OWNER, Role.REVIEWER));
        LocalDateTime firstStart = latestOcrnDt(rawSn);

        // 유예가 거의 끝난 시점에 재진입 — 새 행이 쌓여 시각이 갱신된다.
        LsTaskEventLog reEntry = LsTaskEventLog.startReview(rawSn, OWNER, Role.REVIEWER);
        ReflectionTestUtils.setField(reEntry, "ocrnDt", firstStart.plusMinutes(GRACE_MINUTES - 1));
        save(reEntry);

        // 최초 시작만 보면 이미 만료됐을 시각인데, 갱신된 점유라 아직 살아 있다.
        assertThat(supportAt(firstStart.plusMinutes(GRACE_MINUTES + 1)).claimsOf(List.of(rawSn)))
                .as("재진입이 점유 시각을 갱신하지 못하면 보던 중에 남에게 넘어간다")
                .containsKey(rawSn);
        // 최초 시작 시각은 이력에 그대로 남는다(언제 처음 열었는지를 잃지 않는다).
        assertThat(taskEventLogRepository.findByRawDataIdOrderByOcrnDtAsc(rawSn))
                .extracting(LsTaskEventLog::getOcrnDt)
                .first()
                .isEqualTo(firstStart);
    }

    @Test
    @DisplayName("행위_시점_역할이_적재되고_관리자가_한_행위는_ADMIN으로_남는다")
    void actorRoleIsPersistedAsActualRole() {
        long rawSn = newVideo();
        save(LsTaskEventLog.startReview(rawSn, OWNER, Role.ADMIN));
        save(LsTaskEventLog.approve(rawSn, OWNER, Role.ADMIN));

        List<String> roles = jdbcTemplate.queryForList(
                "SELECT ACTOR_ROLE_CD FROM LS_TASK_EVNT_LOG WHERE RAW_DATA_ID = ? ORDER BY EVNT_ID",
                String.class, rawSn);

        // 계층으로 승격된 REVIEWER 가 아니라 실제 역할이 남아야 한다 — 그래야 관리자 승인 건을 가려낸다.
        assertThat(roles).containsExactly(Role.ADMIN.name(), Role.ADMIN.name());
    }

    @Test
    @DisplayName("역할을_남기지_않는_옛_형태의_이벤트는_역할이_NULL이며_조회가_깨지지_않는다")
    void legacyEventsKeepNullRole() {
        long rawSn = newVideo();
        // 배정은 이번 범위가 아니라 역할을 싣지 않는다 — 그 행이 NULL 로 남는 것이 정상이다.
        save(LsTaskEventLog.assign(rawSn, 1L, 100L));
        save(LsTaskEventLog.startReview(rawSn, OWNER, Role.REVIEWER));

        assertThat(taskEventLogRepository.findByRawDataIdOrderByOcrnDtAsc(rawSn))
                .extracting(LsTaskEventLog::getActorRoleCd)
                .containsExactly(null, Role.REVIEWER.name());
        assertThat(supportAt(LocalDateTime.now()).claimsOf(List.of(rawSn))).containsKey(rawSn);
    }

    @Test
    @DisplayName("유예가_0이하면_기동하지_못한다")
    void nonPositiveGraceIsRejected() {
        // fail-fast — 0·음수면 점유가 세워지는 즉시 만료되어 기능이 조용히 사라진다.
        assertThatThrownBy(() -> new ReviewClaimSupport(taskEventLogRepository, 0, Clock.systemDefaultZone()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ReviewClaimSupport.GRACE_PROPERTY);
    }

    // ------------------------------------------------------------------ fixtures

    /** 주어진 시각을 "지금"으로 보는 창구. 시계를 옮겨 만료 전·후를 실제로 지나간다. */
    private ReviewClaimSupport supportAt(LocalDateTime now) {
        ZoneId zone = ZoneId.systemDefault();
        Instant fixed = now.atZone(zone).toInstant();
        return new ReviewClaimSupport(taskEventLogRepository, GRACE_MINUTES, Clock.fixed(fixed, zone));
    }

    private List<Long> seedVideosWithStartReview(int count) {
        List<Long> rawSns = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long rawSn = newVideo();
            save(LsTaskEventLog.startReview(rawSn, OWNER, Role.REVIEWER));
            rawSns.add(rawSn);
        }
        return rawSns;
    }

    /** 이벤트 로그가 참조할 <b>실재하는</b> 부모 영상 1건(V146 FK). */
    private long newVideo() {
        long rawSn = RawVideoFixture.newRaw(jdbcTemplate);
        seededRawSns.add(rawSn);
        return rawSn;
    }

    private void save(LsTaskEventLog event) {
        txTemplate.executeWithoutResult(s -> taskEventLogRepository.saveAndFlush(event));
    }

    private LocalDateTime latestOcrnDt(long rawSn) {
        return taskEventLogRepository.findByRawDataIdOrderByOcrnDtAsc(rawSn)
                .stream()
                .reduce((a, b) -> b)
                .orElseThrow()
                .getOcrnDt();
    }
}
