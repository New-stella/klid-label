package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.user.entity.LsAcntUser;
import kr.co.cudo.authoring.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 사번 → 사용자 표시명({@code LS_ACNT_USER.USER_NM}) 해석 <b>단일 헬퍼</b>.
 *
 * <p>왜 별도 컴포넌트인가: 같은 "사번 → 이름" 판정이 서비스마다 복붙되면 한쪽만 고쳐져 갈라진다.
 * 표시명이 필요한 모든 경로(이슈 스레드·검수 목록·작업목록·배정 이력·영상 목록·통계·공지·비식별 신고
 * 목록·관제 조회 API 검수자명·라벨 이력·버전 목록·롤백 응답)는 전부 이 헬퍼를 쓴다.
 *
 * <h3>두 개의 입력 축</h3>
 * <ul>
 *   <li><b>사번 문자열</b>({@link #resolveAll}/{@link #resolveOne}) — {@code REG_ID}·{@code AUTHOR_NO}
 *       처럼 VARCHAR 로 보관돼 <b>파싱이 필요한</b> 컬럼. 파싱 규칙은 이 클래스 밖으로 새지 않는다.</li>
 *   <li><b>{@code USER_NO}(Long)</b>({@link #resolveAllByNo}/{@link #resolveOneByNo}) — 이미 숫자
 *       FK 로 보관된 컬럼({@code USER_NO}·{@code ACTOR_USER_NO}·{@code RPRT_USER_NO} 등). 파싱이 없다.</li>
 * </ul>
 * 두 축은 <b>같은 조회·같은 폴백</b>을 공유하며 조회 결과 뷰({@link UserNames})가 둘 다 받는다.
 *
 * <p>고정하는 계약(모두 호출부가 의존한다):
 * <ul>
 *   <li><b>N+1 금지</b> — 페이지의 사번을 모아 {@link UserRepository#findByUserNoIn} <b>1회</b>로 조회한다.
 *       행마다 조회하지 않는다.</li>
 *   <li><b>비숫자 사번은 예외가 아니라 제외</b> — 사번 컬럼({@code REG_ID}/{@code AUTHOR_NO})은 VARCHAR 라
 *       숫자가 아닐 수 있다. 파싱 실패는 이름 {@code null} 로 처리하고 던지지 않는다.</li>
 *   <li><b>마스터에 없는 사번도 {@code null}</b> — 과거 작성자가 삭제·변경돼도 이력·버전 <b>조회 자체는
 *       200 으로 살아 있어야 한다</b>.</li>
 *   <li><b>쓸 수 있는 사번이 하나도 없으면 조회 자체를 하지 않는다</b>(불필요 쿼리 제거).</li>
 *   <li><b>표시명이 {@code null} 인 행도 그대로 담는다</b> — 맵 적재는 {@code HashMap.put} 이며
 *       {@code Collectors.toMap} 이 아니다(값 null 이면 NPE 로 목록 전체가 죽는다).</li>
 * </ul>
 *
 * <p>보안: 응답·로그로 나가는 것은 표시명({@code USER_NM})뿐이며 연락처·이메일 등 다른 개인정보는
 * 취급하지 않는다(CWE-359). 조회는 Spring Data 파생 쿼리 파라미터 바인딩이다(CWE-89).
 */
@Component
@RequiredArgsConstructor
public class UserNameResolver {

    private final UserRepository userRepository;

    /**
     * 사번 문자열 집합에 대한 표시명 조회 (배치 1회).
     *
     * @param rawUserNos 사번 문자열들 (null·공백·비숫자·중복 허용 — 내부에서 정규화)
     * @return 조회 결과를 감싼 {@link UserNames}. 사용 가능한 사번이 없으면 빈 결과(쿼리 미수행)
     */
    public UserNames resolveAll(Collection<String> rawUserNos) {
        if (rawUserNos == null || rawUserNos.isEmpty()) {
            return UserNames.EMPTY;
        }
        Set<Long> userNos = new LinkedHashSet<>();
        for (String raw : rawUserNos) {
            Long parsed = toUserNo(raw);
            if (parsed != null) {
                userNos.add(parsed);
            }
        }
        if (userNos.isEmpty()) {
            return UserNames.EMPTY;
        }
        return query(userNos);
    }

    /**
     * {@code USER_NO}(Long) 집합에 대한 표시명 조회 (배치 1회).
     *
     * <p>이미 숫자 FK 로 보관된 컬럼 전용이라 파싱이 없다. 그 외 계약({@code null} 제외·중복 제거·
     * 빈 입력이면 쿼리 미수행·미존재는 이름 {@code null})은 {@link #resolveAll} 과 동일하다.
     *
     * @param userNos 사용자 번호들 (null·중복 허용 — 내부에서 정규화)
     * @return 조회 결과를 감싼 {@link UserNames}. 사용 가능한 번호가 없으면 빈 결과(쿼리 미수행)
     */
    public UserNames resolveAllByNo(Collection<Long> userNos) {
        if (userNos == null || userNos.isEmpty()) {
            return UserNames.EMPTY;
        }
        Set<Long> distinct = new LinkedHashSet<>();
        for (Long userNo : userNos) {
            if (userNo != null) {
                distinct.add(userNo);
            }
        }
        if (distinct.isEmpty()) {
            return UserNames.EMPTY;
        }
        return query(distinct);
    }

    /**
     * 단건 조회 — 응답이 사용자 1명만 담는 경로(롤백 결과 등) 전용.
     * 목록 경로에서 행마다 호출하면 N+1 이므로 {@link #resolveAll} 을 쓸 것.
     */
    public String resolveOne(String rawUserNo) {
        return resolveOneByNo(toUserNo(rawUserNo));
    }

    /**
     * 단건 조회({@code USER_NO} 축) — 응답이 사용자 1명만 담는 경로 전용.
     * 목록 경로에서 행마다 호출하면 N+1 이므로 {@link #resolveAllByNo} 를 쓸 것.
     */
    public String resolveOneByNo(Long userNo) {
        if (userNo == null) {
            return null;
        }
        return userRepository.findByUserNo(userNo).map(LsAcntUser::getUserNm).orElse(null);
    }

    /** 배치 조회 실행 — 두 축의 공통 종단(IN 쿼리 1회). 호출 전에 비어있지 않음이 보장된다. */
    private UserNames query(Set<Long> userNos) {
        Map<Long, String> names = new HashMap<>(userNos.size() * 2);
        for (LsAcntUser u : userRepository.findByUserNoIn(userNos)) {
            names.put(u.getUserNo(), u.getUserNm());
        }
        return new UserNames(names);
    }

    /**
     * 사번 문자열 → {@code USER_NO}. null/공백/비숫자는 null (예외 금지).
     *
     * <p><b>이 파싱 규칙의 단일 원천</b>이다. 사번 컬럼({@code REG_ID}/{@code AUTHOR_NO}/
     * {@code RPRT_USER_NO})은 VARCHAR 라 숫자가 아닐 수 있고, 그때 던지면 목록 조회 전체가 죽는다.
     * 표시명이 아닌 다른 축(역할 코드 등)을 같은 사번으로 배치 조회하는 호출부도 이 메서드를 쓴다 —
     * 각자 {@code Long.parseLong} 을 다시 감싸면 한쪽만 고쳐져 갈라진다.
     */
    public static Long toUserNo(String rawUserNo) {
        if (rawUserNo == null || rawUserNo.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(rawUserNo.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 배치 조회 결과 — 사번으로 이름을 찾는 조회 전용 뷰.
     * 파싱 규칙을 호출부가 다시 구현하지 않도록 {@link #nameOf(String)}·{@link #nameOf(Long)} 만 노출한다.
     *
     * <p>⚠ 두 오버로드가 있으므로 {@code nameOf(null)} 은 컴파일되지 않는다(모호). 호출부는 타입이
     * 정해진 변수를 넘기며, 리터럴 null 이 필요하면 캐스팅한다.
     */
    public static final class UserNames {

        private static final UserNames EMPTY = new UserNames(Map.of());

        private final Map<Long, String> byUserNo;

        private UserNames(Map<Long, String> byUserNo) {
            this.byUserNo = byUserNo;
        }

        /** 조회를 수행하지 않은 경로(빈 페이지 등)가 쓰는 빈 결과 — 모든 조회가 {@code null} 이다. */
        public static UserNames empty() {
            return EMPTY;
        }

        /** 해석 실패(비숫자 사번·마스터 미존재·null 사번)는 모두 {@code null} — 화면이 사번으로 폴백한다. */
        public String nameOf(String rawUserNo) {
            Long parsed = toUserNo(rawUserNo);
            return parsed == null ? null : byUserNo.get(parsed);
        }

        /** {@code USER_NO} 축 조회 — 마스터 미존재·{@code null} 번호는 모두 {@code null}. */
        public String nameOf(Long userNo) {
            return userNo == null ? null : byUserNo.get(userNo);
        }
    }
}
