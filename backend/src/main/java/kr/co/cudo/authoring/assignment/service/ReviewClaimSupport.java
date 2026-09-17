package kr.co.cudo.authoring.assignment.service;

import kr.co.cudo.authoring.assignment.domain.ReviewClaim;
import kr.co.cudo.authoring.assignment.entity.LsTaskEventLog;
import kr.co.cudo.authoring.assignment.repository.LsTaskEventLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 검수 점유 조회 <b>단일 창구</b> — 「지금 누가 이 영상을 보고 있는가」를 답한다.
 *
 * <p>판정 규칙 자체는 {@link ReviewClaim} 이 소유하고, 이 컴포넌트는 ①조달(이력 원장 조회)
 * ②유예 설정값 보유 ③기준 시각 제공을 맡는다. 검수 도메인이 이 창구를 통해 점유를 읽으므로
 * <b>판정을 복제하지 말 것</b> — 복제하면 목록 표시와 검수 시작 거절이 서로 다른 답을 낸다.
 *
 * <h3>N+1 금지</h3>
 * {@link #claimsOf(Collection)} 는 영상이 몇 건이든 <b>조회 한 번</b>으로 끝난다. 목록 화면이
 * 행마다 {@link #claimOf(Long)} 를 부르면 그 보장이 깨진다 — 페이지의 식별자를 모아 한 번에 넘긴다.
 *
 * @design ADR-067
 * @design ERD-014
 */
@Component
public class ReviewClaimSupport {

    /**
     * 유예 설정 키 — 이 값이 지나면 점유가 저절로 풀린다. 하드코딩하지 않는다.
     *
     * <p>너무 짧으면 검수 도중 남이 가져가고, 너무 길면 자리를 뜬 사람이 영상을 오래 묶는다.
     */
    public static final String GRACE_PROPERTY = "authoring.review.claim.grace-minutes";

    private final LsTaskEventLogRepository taskEventLogRepository;
    private final Duration grace;
    private final Clock clock;

    @Autowired
    public ReviewClaimSupport(LsTaskEventLogRepository taskEventLogRepository,
                              @Value("${" + GRACE_PROPERTY + ":30}") long graceMinutes) {
        this(taskEventLogRepository, graceMinutes, Clock.systemDefaultZone());
    }

    /**
     * 시험 전용 진입점 — <b>시계를 주입</b>해 만료 전·후를 실제로 지나가게 한다.
     *
     * <p>점유를 고정값으로 흉내 내면 만료 구간을 한 번도 밟지 않은 채 시험이 초록이 된다.
     */
    ReviewClaimSupport(LsTaskEventLogRepository taskEventLogRepository, long graceMinutes, Clock clock) {
        if (graceMinutes <= 0) {
            // fail-fast — 0·음수면 점유가 세워지는 즉시 만료되어 기능이 조용히 사라진다.
            throw new IllegalArgumentException(GRACE_PROPERTY + " 는 1 이상이어야 합니다: " + graceMinutes);
        }
        this.taskEventLogRepository = taskEventLogRepository;
        this.grace = Duration.ofMinutes(graceMinutes);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 영상 여러 건의 점유를 <b>조회 한 번</b>으로 판정한다.
     *
     * @return 점유가 <b>유효한</b> 영상만 담긴 매핑. 점유가 없거나 만료된 영상은 키 자체가 없다
     */
    public Map<Long, ReviewClaim> claimsOf(Collection<Long> rawDataIds) {
        if (rawDataIds == null || rawDataIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> distinct = rawDataIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Collections.emptyMap();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, ReviewClaim> claims = new HashMap<>();
        for (LsTaskEventLog latest :
                taskEventLogRepository.findLatestOfTypesByRawDataIds(distinct, ReviewClaim.EVENT_TYPES)) {
            ReviewClaim.of(latest, grace, now)
                    .ifPresent(claim -> claims.put(claim.rawDataId(), claim));
        }
        return claims;
    }

    /** 영상 한 건의 점유. 목록에서 쓰지 말 것({@link #claimsOf(Collection)} 을 쓴다). */
    public Optional<ReviewClaim> claimOf(Long rawDataId) {
        if (rawDataId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(claimsOf(List.of(rawDataId)).get(rawDataId));
    }

    /** 이 배포가 쓰는 유예 — 화면 안내 문구 등에 쓴다. */
    public Duration grace() {
        return grace;
    }
}
