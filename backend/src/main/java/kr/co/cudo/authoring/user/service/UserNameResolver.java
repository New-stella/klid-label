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
 * 사번(문자열) → 사용자 표시명({@code LS_ACNT_USER.USER_NM}) 해석 <b>단일 헬퍼</b>.
 *
 * <p>왜 별도 컴포넌트인가: 같은 "사번 → 이름" 판정이 서비스마다 복붙되면(이미 이슈 스레드·검수 목록에
 * 두 벌이 있다) 한쪽만 고쳐져 갈라진다. 신규 경로(라벨 이력·버전 목록·롤백 응답)는 전부 이 헬퍼를 쓴다.
 *
 * <p>고정하는 계약(모두 호출부가 의존한다):
 * <ul>
 *   <li><b>N+1 금지</b> — 페이지의 사번을 모아 {@link UserRepository#findByUserNoIn} <b>1회</b>로 조회한다.
 *       행마다 조회하지 않는다.</li>
 *   <li><b>비숫자 사번은 예외가 아니라 제외</b> — 사번 컬럼({@code REG_ID}/{@code AUTHOR_NO})은 VARCHAR 라
 *       숫자가 아닐 수 있다. 파싱 실패는 이름 {@code null} 로 처리하고 던지지 않는다.</li>
 *   <li><b>마스터에 없는 사번도 {@code null}</b> — 과거 작성자가 삭제·변경돼도 이력·버전 <b>조회 자체는
 *       200 으로 살아 있어야 한다</b>.</li>
 *   <li><b>파싱 가능한 사번이 하나도 없으면 조회 자체를 하지 않는다</b>(불필요 쿼리 제거).</li>
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
        Map<Long, String> names = new HashMap<>(userNos.size() * 2);
        for (LsAcntUser u : userRepository.findByUserNoIn(userNos)) {
            names.put(u.getUserNo(), u.getUserNm());
        }
        return new UserNames(names);
    }

    /**
     * 단건 조회 — 응답이 사용자 1명만 담는 경로(롤백 결과 등) 전용.
     * 목록 경로에서 행마다 호출하면 N+1 이므로 {@link #resolveAll} 을 쓸 것.
     */
    public String resolveOne(String rawUserNo) {
        Long parsed = toUserNo(rawUserNo);
        if (parsed == null) {
            return null;
        }
        return userRepository.findByUserNo(parsed).map(LsAcntUser::getUserNm).orElse(null);
    }

    /** 사번 문자열 → USER_NO. null/공백/비숫자는 null (예외 금지). */
    private static Long toUserNo(String rawUserNo) {
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
     * 배치 조회 결과 — 사번 문자열로 이름을 찾는 조회 전용 뷰.
     * 파싱 규칙을 호출부가 다시 구현하지 않도록 {@link #nameOf(String)} 만 노출한다.
     */
    public static final class UserNames {

        private static final UserNames EMPTY = new UserNames(Map.of());

        private final Map<Long, String> byUserNo;

        private UserNames(Map<Long, String> byUserNo) {
            this.byUserNo = byUserNo;
        }

        /** 해석 실패(비숫자 사번·마스터 미존재·null 사번)는 모두 {@code null} — 화면이 사번으로 폴백한다. */
        public String nameOf(String rawUserNo) {
            Long parsed = toUserNo(rawUserNo);
            return parsed == null ? null : byUserNo.get(parsed);
        }
    }
}
