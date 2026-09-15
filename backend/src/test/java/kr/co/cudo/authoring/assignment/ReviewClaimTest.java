package kr.co.cudo.authoring.assignment;

import kr.co.cudo.authoring.assignment.domain.ReviewClaim;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.common.security.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검수 점유 <b>판정 규칙</b> 회귀 가드 (ADR-067).
 *
 * <p>★ 이 시험의 핵심은 <b>만료 경계를 실제로 지나가는 것</b>이다. 판정을 고정값으로 흉내 내면
 * 유예 구간을 한 번도 밟지 않은 채 초록이 된다. 그래서 같은 이벤트 한 건에 대해 <b>만료 전·경계·
 * 만료 후</b> 세 시각으로 각각 판정한다.
 */
class ReviewClaimTest {

    private static final Duration GRACE = Duration.ofMinutes(30);
    private static final LocalDateTime STARTED = LocalDateTime.of(2026, 9, 15, 10, 0, 0);
    private static final long OWNER = 7L;

    /** 발생일시는 팩토리가 {@code now()} 로 박으므로 시험이 원하는 시각으로 되돌린다. */
    private static LsTaskEventLog eventAt(String type, long actorUserNo, LocalDateTime occurredAt) {
        LsTaskEventLog e = switch (type) {
            case LsTaskEventLog.EVENT_START_REVIEW -> LsTaskEventLog.startReview(500L, actorUserNo, Role.REVIEWER);
            case LsTaskEventLog.EVENT_APPROVE -> LsTaskEventLog.approve(500L, actorUserNo, Role.REVIEWER);
            case LsTaskEventLog.EVENT_REJECT -> LsTaskEventLog.reject(500L, actorUserNo, "사유", Role.REVIEWER);
            default -> throw new IllegalArgumentException(type);
        };
        ReflectionTestUtils.setField(e, "ocrnDt", occurredAt);
        return e;
    }

    @Test
    @DisplayName("유예_안이면_점유가_있고_유예를_지나면_사라진다")
    void claimExpiresAfterGrace() {
        LsTaskEventLog started = eventAt(LsTaskEventLog.EVENT_START_REVIEW, OWNER, STARTED);

        // 시작 직후 — 점유 있음
        assertThat(ReviewClaim.of(started, GRACE, STARTED)).isPresent();
        // 만료 1분 전 — 아직 점유 있음
        assertThat(ReviewClaim.of(started, GRACE, STARTED.plusMinutes(29))).isPresent();
        // ★ 경계(정확히 유예가 지난 시각) — 이 시각부터는 풀린다
        assertThat(ReviewClaim.of(started, GRACE, STARTED.plusMinutes(30))).isEmpty();
        // 만료 후 — 점유 없음
        assertThat(ReviewClaim.of(started, GRACE, STARTED.plusMinutes(31))).isEmpty();
    }

    @Test
    @DisplayName("점유의_주인과_만료시각이_그대로_실린다")
    void claimCarriesOwnerAndExpiry() {
        Optional<ReviewClaim> claim = ReviewClaim.of(
                eventAt(LsTaskEventLog.EVENT_START_REVIEW, OWNER, STARTED), GRACE, STARTED.plusMinutes(1));

        assertThat(claim).hasValueSatisfying(c -> {
            assertThat(c.rawDataId()).isEqualTo(500L);
            assertThat(c.ownerUserNo()).isEqualTo(OWNER);
            assertThat(c.startedAt()).isEqualTo(STARTED);
            assertThat(c.expiresAt()).isEqualTo(STARTED.plusMinutes(30));
            assertThat(c.ownedBy(OWNER)).isTrue();
            assertThat(c.ownedBy(OWNER + 1)).isFalse();
            assertThat(c.ownedBy(null)).isFalse();
        });
    }

    @Test
    @DisplayName("마지막이_승인이나_반려면_유예가_남아도_점유가_아니다")
    void terminalEventClearsClaim() {
        // 조회가 "마지막 1건"을 주므로, 승인·반려가 마지막이라는 것은 검수 시작 뒤에 종결이 있었다는 뜻이다.
        LocalDateTime inGrace = STARTED.plusMinutes(1);

        assertThat(ReviewClaim.of(eventAt(LsTaskEventLog.EVENT_APPROVE, OWNER, STARTED), GRACE, inGrace))
                .isEmpty();
        assertThat(ReviewClaim.of(eventAt(LsTaskEventLog.EVENT_REJECT, OWNER, STARTED), GRACE, inGrace))
                .isEmpty();
    }

    @Test
    @DisplayName("이벤트가_없으면_점유가_없다")
    void noEventNoClaim() {
        assertThat(ReviewClaim.of(null, GRACE, STARTED)).isEmpty();
    }

    @Test
    @DisplayName("판정이_읽는_이벤트_종류에_시작과_두_종결이_모두_들어_있다")
    void eventTypesCoverStartAndBothTerminals() {
        // 종결을 빼면 승인 끝난 영상이 영원히 점유 중으로 보인다 — 축소하지 못하게 고정한다.
        assertThat(ReviewClaim.EVENT_TYPES).containsExactlyInAnyOrder(
                LsTaskEventLog.EVENT_START_REVIEW,
                LsTaskEventLog.EVENT_APPROVE,
                LsTaskEventLog.EVENT_REJECT);
    }
}
