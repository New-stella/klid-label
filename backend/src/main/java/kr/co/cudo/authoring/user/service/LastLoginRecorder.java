package kr.co.cudo.authoring.user.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 최종로그인일시({@code LS_ACNT_USER.LAST_LGN_DT}) 기록기 — <b>throttle 창 계산 + fail-open</b>.
 *
 * <h3>기록 지점이 왜 필터인가</h3>
 * <p>저작도구에는 독립 로그인 UI 가 없다. 관제/포털이 발급한 JWT 를 인계받아 검증하므로 "로그인"
 * 이라는 단일 이벤트가 존재하지 않고, 관측할 수 있는 가장 가까운 사실은 <b>검증을 통과한 요청</b>
 * 이다. 그래서 {@code JwtAuthenticationFilter} 가 이 기록기를 호출한다.
 *
 * <h3>★ 매 요청 UPDATE 를 하지 않는다</h3>
 * <p>필터는 모든 요청에서 돈다. 그대로 쓰면 요청 수만큼 DB 쓰기가 발생해 행 잠금·WAL 이 쌓인다.
 * 그래서 {@code now - throttle} 을 창으로 넘겨 <b>조건부 UPDATE 의 WHERE 절</b>이 판정하게 한다.
 * 로컬 캐시로 앞단을 막지 않는 이유는 2노드 Active-Active 에서 노드별 캐시가 갈려 창이 지켜지지
 * 않기 때문이다 — 정확성의 근거는 DB 조건절 하나다({@code UserRepository.touchLastLogin}).
 *
 * <h3>★ 기록 실패가 요청을 죽이지 않는다 (fail-open)</h3>
 * <p>이것은 <b>보안 게이트가 아니라 부가 기록</b>이다. DB 장애로 기록이 실패했다고 인증·인가를
 * 거부하면 장애 범위가 서비스 전체로 번진다. 따라서 예외를 잡아 WARN 으로만 남기고 요청을 그대로
 * 통과시킨다 — 단 <b>조용히 삼키지 않는다</b>(무음 catch 금지).
 *
 * <p>try/catch 가 {@link LastLoginTouchTxService} <b>바깥</b>에 있는 것이 핵심이다. 트랜잭션 안에서
 * 삼키면 rollback-only 표시 때문에 커밋 시점에 다시 터진다(그 클래스 javadoc 참조).
 *
 * <p>보안: 로그에는 {@code userNo}(Long)만 남긴다. 이메일·이름·토큰 등 PII 는 남기지 않는다
 * (CWE-359/117 — 사용자 입력을 로그에 흘리지 않으므로 개행 주입 표면도 없다).
 *
 * @design SCREEN-024
 */
@Slf4j
@Component
public class LastLoginRecorder {

    /** throttle 기본값(분). 설정이 없거나 0 이하일 때 쓰는 안전값. */
    public static final int DEFAULT_THROTTLE_MINUTES = 5;

    private final LastLoginTouchTxService touchTxService;

    /** 기록 최소 간격(분). 항상 1 이상이다(0 이하 설정은 기본값으로 폴백). */
    private final int throttleMinutes;

    public LastLoginRecorder(
            LastLoginTouchTxService touchTxService,
            @Value("${authoring.user.last-login.throttle-minutes:" + DEFAULT_THROTTLE_MINUTES + "}")
            int throttleMinutes) {
        this.touchTxService = touchTxService;
        // 0·음수는 throttle 무력화(=매 요청 UPDATE)와 같으므로 기동을 실패시키지 않고 기본값으로
        // 폴백한다 — 이 기능은 부가 기록이라 잘못된 설정 하나로 앱이 뜨지 않는 편이 더 해롭다.
        if (throttleMinutes <= 0) {
            log.warn("[User] last-login throttle 설정이 유효하지 않다(configured={}) → 기본값 {}분 사용",
                    throttleMinutes, DEFAULT_THROTTLE_MINUTES);
            this.throttleMinutes = DEFAULT_THROTTLE_MINUTES;
        } else {
            this.throttleMinutes = throttleMinutes;
        }
    }

    /**
     * 최종로그인일시를 기록한다. throttle 창 안이면 아무 일도 하지 않는다.
     *
     * <p>절대 예외를 던지지 않는다 — 호출자(인증 필터)는 이 결과에 의존하지 않는다.
     *
     * @param userNo JWT subject 에서 파싱한 사용자번호. null 이면(비숫자/누락 sub) 아무것도 하지 않는다.
     */
    public void record(Long userNo) {
        if (userNo == null) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            touchTxService.touch(userNo, now, now.minusMinutes(throttleMinutes));
        } catch (RuntimeException e) {
            // fail-open — 부가 기록 실패가 요청을 죽이지 않는다. 다만 무음으로 넘기지 않는다.
            log.warn("[User] last-login record failed userNo={} (fail-open) message={}",
                    userNo, e.getMessage());
        }
    }
}
