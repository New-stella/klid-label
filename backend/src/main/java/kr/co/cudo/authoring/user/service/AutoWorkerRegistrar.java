package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.UserRoleResolver;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 진입 시 작업자 자동 등록기 — <b>역할이 없을 때만</b> 도는 조건부 등록 + fail-open.
 *
 * <h3>왜 진입 시점인가</h3>
 * <p>역할 자가부여 창구가 관리자 부트스트랩 전용으로 좁혀지면서, 그 창구는 더 이상 일반 사용자가
 * 등록되는 통로가 아니다. 등록 경로가 없으면 아무도 배정 대상이 되지 못하므로 <b>진입 자체가
 * 등록 시점</b>이 된다. 부수 효과로 배정 제약이 풀린다 — 종전에는 대상자가 먼저 자가부여를 해야
 * 작업자 목록에 떴다.
 *
 * <h3>★ 매 요청 쓰기가 아니다 (게이트가 <b>둘</b>이고 둘 다 필요하다)</h3>
 * <p>1차는 호출 조건이다 — "역할 해석 결과가 없을 때". 등록에 성공하면 다음 요청부터는 역할이
 * 해석되어 이 등록기가 <b>아예 호출되지 않는다</b>.
 *
 * <p>★2차는 여기 안에 있다. 역할 <b>행</b>은 있는데 그 코드가 {@link Role} 밖이라 해석이 <b>항상</b>
 * 비는 사용자가 존재한다(관제 고유 역할 등). 그 사용자는 1차 게이트를 매 요청 통과하므로, 존재
 * 확인 없이 바로 쓰기로 가면 <b>인증 경로에서 요청마다 트랜잭션 1 + 문장 2</b> 가 돈다 — 둘 다
 * no-op 이라 행은 안 바뀌고, 그래서 <b>DB 를 관측하는 시험으로는 잡히지 않는다</b>. 회귀 가드가
 * 호출 횟수를 세는 이유다.
 *
 * <h3>★ 이미 부여된 역할을 덮지 않는다</h3>
 * <p>쓰기는 {@code DO NOTHING} 이라 기존 역할을 건드리지 않는다. 덮어쓰면 <b>관리자가 지정한
 * 역할이 그 사람의 다음 요청에 되돌려진다</b>. 그래서 "역할이 이미 있어서 삽입되지 않은" 경우는
 * 성공이 아니라 <b>무권한(null)</b> 으로 돌려준다 — 그 역할이 무엇인지 추측해 부여하지 않는다
 * (fail-closed). 실제로 이 경로에 오는 것은 역할 코드가 {@link Role} 밖인 사용자다.
 *
 * <h3>★ 등록 실패가 요청을 죽이지 않는다 (fail-open)</h3>
 * <p>이것은 인가 게이트가 아니라 부가 등록이다. DB 장애로 등록이 실패했다고 요청을 500 으로
 * 끊으면 장애 범위가 넓어진다. 예외는 WARN 으로만 남기고 null 을 돌려주며, 그 요청은 <b>무권한
 * 으로 흐른다</b>(인가 자체는 fail-closed 그대로다).
 *
 * <p>★ 포털 채널은 대상이 아니다 — 호출자가 내부 채널에서만 부른다. 포털 토큰의 주체는 이
 * 마스터의 사용자번호와 매핑이 확인되지 않아 등록하면 남의 행을 만들 수 있다.
 *
 * <p>보안: 로그에는 {@code userNo}(Long)와 역할 코드만 남긴다 — 토큰·PII 를 남기지 않으므로
 * 개행 주입 표면도 없다(CWE-359/117).
 *
 * @design ADR-055
 * @design AC-126
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutoWorkerRegistrar {

    private final AutoWorkerRegisterTxService txService;
    private final UserRoleResolver userRoleResolver;
    private final LsUserRoleRepository lsUserRoleRepository;

    /**
     * 역할이 없는 내부 채널 사용자를 작업자로 등록한다.
     *
     * <p>절대 예외를 던지지 않는다 — 호출자(인증 필터)는 이 결과에 의존하지 않는다.
     *
     * @param userNo JWT subject 에서 파싱한 사용자번호. null 이면(비숫자/누락 sub) 아무것도 하지
     *               않는다 — 식별할 수 없는 주체로 행을 만들지 않는다.
     * @param userNm 인계 토큰의 이름 클레임(없을 수 있다). 사용자 마스터의 표시 이름을 채운다.
     * @return 이번에 부여한 역할({@link Role#WORKER}). 등록하지 않았거나 실패했으면 null.
     */
    public Role registerAsWorker(Long userNo, String userNm) {
        if (userNo == null) {
            return null;
        }
        try {
            // 2차 게이트 — 역할 <행>이 이미 있으면 쓰기 경로에 들어가지 않는다(클래스 javadoc 참조).
            //   읽기 한 번으로 트랜잭션 1 + 문장 2 를 아낀다. 해석이 비었다고 행이 없는 것은 아니다.
            if (lsUserRoleRepository.existsById(userNo)) {
                return null;
            }
            if (!txService.registerAsWorker(userNo, userNm)) {
                return null;
            }
        } catch (RuntimeException e) {
            // fail-open — 부가 등록 실패가 요청을 죽이지 않는다. 다만 무음으로 넘기지 않는다.
            log.warn("[Auth] auto worker registration failed userNo={} (fail-open) message={}",
                    userNo, e.getMessage());
            return null;
        }
        // 커밋 후 무효화 — 트랜잭션 경계가 이미 닫힌 뒤다. resolve 는 null 을 캐시하지 않지만
        // (unless="#result == null") 일관성을 위해 부여 시에도 비운다.
        userRoleResolver.evict(userNo);
        log.info("[Auth] auto-registered userNo={} role={}", userNo, Role.WORKER.name());
        return Role.WORKER;
    }
}
