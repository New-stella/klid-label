package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.common.security.Role;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 개발용 로그인이 발급하는 <b>표준 계정 번호</b>의 단일 진실원 + 자동 등록 제외 판정기.
 *
 * <h3>왜 이 값이 한 곳에 있어야 하나</h3>
 * <p>이 번호는 두 곳이 함께 알아야 한다 — 토큰을 <b>발급</b>하는 쪽(개발용 토큰 서비스)과 진입 시
 * 작업자 자동 등록에서 <b>제외</b>하는 쪽({@link AutoWorkerRegistrar}). 두 곳에 복제하면 dev 계정이
 * 늘어날 때 한쪽만 갱신돼 <b>새 계정만 제외에서 빠지고</b>, 아래에 적은 사고가 그 계정에서 그대로
 * 재현된다. 그래서 값은 여기에만 두고 양쪽이 이 클래스를 참조한다.
 *
 * <h3>왜 자동 등록에서 빼는가 (2026-09-01 실사고)</h3>
 * <p>이 계정들은 <b>역할이 시드로 주어지는 것이 전제</b>다. 시드가 도달하지 않는 환경(시드는
 * local 전용인데 개발용 로그인 창구는 dev 에서도 열린다)에서 역할 없이 진입하면 자동 등록이 그
 * 빈자리를 작업자로 메운다. 역할 삽입은 {@code DO NOTHING} 이라 그 뒤로 <b>영구히 작업자</b>가
 * 되고, 프론트는 토큰 클레임(고른 역할)으로 메뉴를 그리는데 서버는 저장된 역할(작업자)로 인가해
 * <b>메뉴는 전부 보이는데 그 창구가 모두 거부되는</b> 상태가 된다.
 *
 * <p>제외하면 그 계정은 <b>역할 없음</b>으로 남아 최초 관리자 등록 창구로 유도된다 — 배포 직후
 * 관리자가 0명이라는 설계 의도와 같은 상태다.
 *
 * <h3>★제외는 개발용 로그인이 켜진 환경에서만 적용한다</h3>
 * <p>운영에는 이 번호를 쓰는 <b>실제 사용자</b>가 있을 수 있다. 토글과 무관하게 제외하면 그
 * 사용자가 자동 등록에서 조용히 빠지는데, 오류가 나지 않아 <b>시끄럽지 않은 실패</b>가 된다.
 * 토글이 꺼져 있으면 이 판정기는 <b>아무것도 하지 않는다</b>(종전 동작과 100% 동일).
 *
 * <p>⚠ 여기 값은 {@code db/seed/dev-seed.sql} 의 행과 1:1 로 맞아야 한다 — 한쪽만 바꾸면 그 역할의
 * 토큰 주체가 <b>다른 사람의 행</b>을 가리킨다(과거 1002/2001 오매핑 사고). 그 정합은 별도
 * 회귀 가드가 고정한다.
 *
 * @design AC-1016
 * @design UC-041
 */
@Component
public class DevStandardAccounts {

    // 기본 USER_NO 는 dev-seed.sql 과 1:1 매칭되어야 한다.
    //   1001=REVIEWER(김검수) · 2001=WORKER(최라벨) · 3001=PORTAL_USER(홍길동) · 9001=ADMIN(박관리)
    // 과거 1002(WORKER)·2001(PORTAL) 매핑은 시드와 어긋나 WORKER 토큰의 sub 가
    // 실제 REVIEWER 사용자를 가리켜 LABELER 배정 0건이 반환되는 버그가 있었다.
    public static final String DEFAULT_USER_NO_REVIEWER = "1001";
    public static final String DEFAULT_USER_NO_WORKER = "2001";
    public static final String DEFAULT_USER_NO_PORTAL = "3001";
    /**
     * 관리자 기본 사용자번호.
     *
     * <p>★구 서술 폐기(2026-08-28) — <i>"관리자에게는 기본 USER_NO 가 없다. 관리자 토큰은
     * userNo 를 명시해 발급한다"</i>. 그 제약의 근거는 <b>dev 시드에 관리자 행이 없다</b>는
     * 것이었는데 이제 있다({@code 9001}).
     */
    public static final String DEFAULT_USER_NO_ADMIN = "9001";

    /** 역할 → 기본 사용자번호. 모든 역할이 기본값을 갖는다(누락 시 {@link #userNoOf} 가 즉시 실패). */
    private static final Map<Role, String> USER_NO_BY_ROLE = new EnumMap<>(Map.of(
            Role.REVIEWER, DEFAULT_USER_NO_REVIEWER,
            Role.WORKER, DEFAULT_USER_NO_WORKER,
            Role.PORTAL_USER, DEFAULT_USER_NO_PORTAL,
            Role.ADMIN, DEFAULT_USER_NO_ADMIN));

    /**
     * 판정용 숫자 집합 — 위 문자열에서 <b>파생</b>한다. 별도로 적으면 그것이 두 번째 진실원이 된다.
     * 값이 숫자가 아니면 클래스 로딩 시점에 즉시 터진다(조용히 제외에서 빠지는 것보다 낫다).
     */
    private static final Set<Long> USER_NOS = USER_NO_BY_ROLE.values().stream()
            .map(Long::parseLong)
            .collect(Collectors.toUnmodifiableSet());

    /** {@code authoring.dev.login.enabled} — 개발용 로그인 창구가 열린 형상에서만 제외가 동작한다. */
    private final boolean devLoginEnabled;

    public DevStandardAccounts(
            @Value("${authoring.dev.login.enabled:false}") boolean devLoginEnabled) {
        this.devLoginEnabled = devLoginEnabled;
    }

    /**
     * 그 역할로 개발용 토큰을 발급할 때 쓰는 기본 사용자번호(JWT {@code sub} 형태의 문자열).
     *
     * @throws IllegalStateException 새 역할이 추가됐는데 여기 매핑을 안 넣은 경우 — 조용히 null 을
     *                               돌려주면 그 역할의 토큰 주체가 비어 원인을 찾기 어려워진다.
     */
    public static String userNoOf(Role role) {
        String userNo = USER_NO_BY_ROLE.get(role);
        if (userNo == null) {
            throw new IllegalStateException(
                    "개발용 로그인 기본 사용자번호가 정의되지 않은 역할입니다: " + role);
        }
        return userNo;
    }

    /** 개발용 로그인 표준 계정 번호인가. 토글과 무관한 <b>사실</b> 판정이다. */
    public static boolean isDevStandardUserNo(Long userNo) {
        return userNo != null && USER_NOS.contains(userNo);
    }

    /**
     * 진입 시 작업자 자동 등록에서 <b>제외</b>할 대상인가.
     *
     * <p>개발용 로그인이 꺼진 형상에서는 항상 false — 운영에서 같은 번호를 쓰는 실제 사용자를
     * 자동 등록에서 빼지 않기 위해서다(클래스 주석 참조).
     */
    public boolean isAutoRegisterExcluded(Long userNo) {
        return devLoginEnabled && isDevStandardUserNo(userNo);
    }
}
