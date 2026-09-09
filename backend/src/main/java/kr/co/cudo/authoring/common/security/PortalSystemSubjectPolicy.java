package kr.co.cudo.authoring.common.security;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 포털 채널 토큰의 <b>주체 축</b>을 가르는 판정기 (@design INT-014 · API-244 · AC-1103).
 *
 * <h2>왜 필요한가</h2>
 * <p>포털 서버간 연동의 인증은 두 축이다 — <b>사용자 컨텍스트</b>(토큰 주체가 사용자)와
 * <b>시스템간</b>(토큰 주체가 시스템 계정). 둘은 같은 대칭 서명키를 공유하고 같은 전용 요청
 * 헤더로 실려 오므로, <b>주체 클레임 말고는 둘을 가를 수단이 없다.</b>
 *
 * <p>그런데 「무엇이 시스템 계정인가」는 토큰 안에 표식이 없다 — 별도 클레임이 계약에 없다.
 * 그래서 판정 기준을 <b>배포 설정으로 주어지는 시스템 계정 주체 식별자 목록</b>으로 둔다.
 * 이 규격의 정본은 연동점(INT-014)이며 이 클래스는 그 규격의 <b>단일 구현 지점</b>이다.
 *
 * <h2>★ 목록이 비면 아무도 시스템 주체가 아니다</h2>
 * <p>설정이 없거나 비면 {@link #isSystemSubject(String)} 가 <b>항상 거짓</b>이라 시스템 주체
 * 창구가 닫힌다. 값이 없을 때 <b>열리는 것이 아니라 닫히는</b> 방향이라 안전하다. 공유 서명키와
 * 같은 논리로 <b>설정값이라 구현을 막지 않는다</b> — 값이 없다는 것은 실동작이 성립하지 않는다는
 * 뜻이지 착수 불가가 아니다.
 *
 * <h2>★★ 인지하고 받아들인 잔여 위험 — 목록 등록은 운영 책임이다</h2>
 * <p>포털이 시스템 계정을 쓰기 시작했는데 그 식별자가 이 목록에 <b>없으면</b>, 그 토큰은
 * 시스템 주체로 판정되지 않아 <b>사용자 주체로 취급되고 포털 사용자 권한을 받는다.</b> 그러면
 * 이 판정이 막으려던 바로 그 상태(시스템 계정이 사용자 창구에 도달)가 된다.
 *
 * <p>이것은 구현 결함이 아니라 <b>구조적 한계</b>다 — 목록 없이 시스템 계정을 식별할 수단이
 * 토큰에 없다. 그래서 방어는 코드가 아니라 <b>운영 절차</b>에 있다: 포털이 시스템 계정 식별자를
 * 통보하면 배포 설정에 반드시 등록해야 한다. 이 사실을 운영 문서에서 지우지 말 것.
 *
 * <h2>판정 규칙</h2>
 * <ul>
 *   <li>비교는 <b>정확 일치</b>다. 접두·부분 일치를 쓰지 않는다 — 부분 일치는 사용자 식별자가
 *       시스템 계정 식별자를 접두로 갖기만 해도 권한을 얻는 통로가 된다.</li>
 *   <li>설정 항목은 앞뒤 공백을 떼고 빈 항목은 버린다. 그래야 {@code "a, ,b"} 같은 값이
 *       <b>빈 문자열을 시스템 주체로 등록</b>하는 사고를 내지 않는다.</li>
 *   <li>주체가 {@code null}·공백이면 거짓이다(fail-closed).</li>
 * </ul>
 *
 * <p>⚠ 이 판정을 <b>채널 판정과 섞지 말 것</b>. 채널({@link Channel})은 토큰이 어느 상위
 * 시스템에서 왔는지이고, 주체 축은 그 안에서 사람인지 시스템인지다. 두 축은 직교하며 인가는
 * 둘을 <b>함께</b> 요구한다.
 */
@Slf4j
@Component
public class PortalSystemSubjectPolicy {

    /**
     * 시스템 계정 주체 식별자 목록. 불변이며 <b>비어 있는 것이 기본값</b>이다.
     *
     * <p>{@link LinkedHashSet} 으로 담아 설정에 적힌 순서를 보존한다 — 진단 로그가 설정 파일과
     * 같은 순서로 보여야 운영자가 대조할 수 있다.
     */
    private final Set<String> systemSubjects;

    /**
     * @param rawSubjects 쉼표로 구분한 시스템 계정 주체 식별자. 미설정이면 빈 문자열이며
     *                    그 경우 아무도 시스템 주체가 아니다(fail-closed).
     */
    public PortalSystemSubjectPolicy(
            @Value("${authoring.portal.system-subjects:}") String rawSubjects) {
        this.systemSubjects = parse(rawSubjects);
        if (this.systemSubjects.isEmpty()) {
            // WARN 이지 기동 실패가 아니다 — 이 값이 없어도 저작도구의 나머지는 정상 동작하고,
            //   닫히는 것은 시스템 주체 창구뿐이다. 기동을 막으면 포털 연동을 쓰지 않는 배포본
            //   (관제 채널)까지 함께 죽는다.
            log.warn("[PortalSystemSubject] 시스템 계정 주체 식별자 목록이 비어 있습니다 — "
                    + "포털 시스템 주체 창구가 닫힙니다(fail-closed). "
                    + "포털이 시스템 계정을 쓴다면 authoring.portal.system-subjects 에 등록해야 합니다.");
        } else {
            // ★ 식별자 <값>을 로그에 남기지 않는다 — 인가 판정 키라 유출되면 위장 시도의 재료가 된다.
            //   운영자가 필요한 것은 "몇 건이 등록됐는가"이지 그 값이 아니다.
            log.info("[PortalSystemSubject] 시스템 계정 주체 식별자 {}건이 등록됐습니다.",
                    systemSubjects.size());
        }
    }

    /**
     * 이 주체가 시스템 계정인가.
     *
     * @param subject 토큰의 주체 클레임. null·공백이면 거짓(fail-closed)
     * @return 등록된 시스템 계정 주체 식별자와 정확히 일치하면 참
     */
    public boolean isSystemSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return false;
        }
        return systemSubjects.contains(subject.trim());
    }

    /** 등록 건수 — 진단·시험용. 식별자 값 자체는 노출하지 않는다. */
    public int registeredCount() {
        return systemSubjects.size();
    }

    private static Set<String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> parsed = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(parsed::add);
        return Collections.unmodifiableSet(parsed);
    }
}
