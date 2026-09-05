package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 관제 인계 <b>미등록</b> 진입자에게 로컬 식별 레코드(userNo)를 발급하는 프로비저너
 * (@design ADR-063 · @design UC-041 · @design AC-1016).
 *
 * <h3>왜 진입 순간 발급하는가</h3>
 * <p>관제 인계 토큰은 문자열 로그인 ID(예 {@code sub="admin"}, {@code userId="admin"})로 식별하고
 * 숫자 {@code userNo} 를 싣지 않는다. 우리 인가는 숫자 {@code USER_NO} 로 도는데
 * {@code LS_ACNT_USER} 에 해당 {@code USER_ID} 행이 없으면 조회가 0건이라 무권한이고
 * 부트스트랩(role-claim)도 성립하지 않는다. 상용은 사용자 시드가 없어 관제 사용자 전원이 이
 * 상태가 되어 관제 채널이 통째로 잠긴다. 그래서 진입 순간 우리 DB 에 로컬 식별 레코드를 만든다.
 * 이는 회원가입 화면이 아니라 관제가 이미 인증한 사용자에 대한 투명한 식별 레코드 생성이며,
 * 「역할 클레임 시점 자동등록」({@link AutoWorkerRegisterTxService})의 연장이다.
 *
 * <h3>★ 역할은 부여하지 않는다 (role=null)</h3>
 * <p>관제 권한 클레임을 우리 역할로 매핑하지 않는 원칙({@code ADR-021}) 그대로다. 발급은 식별
 * 계층의 일이고, 인가 계층에서는 여전히 무권한이다 — 발급 후 첫 진입자는 관리자 비밀번호로 ADMIN
 * 부트스트랩하고 이후 사용자는 관리자가 역할을 부여한다. 그래서 이 프로비저너는 {@link AutoWorkerRegistrar}
 * 처럼 역할을 만들지 않고, 발급된 {@code userNo}(Long)만 돌려준다.
 *
 * <h3>★ 0건일 때만 발급하고 다중 매칭이면 발급하지 않는다 (AC-1017, CWE-639)</h3>
 * <p>호출자(필터)의 {@code resolveUserNoByUserId} 는 0건과 다중을 모두 null 로 축약하므로 여기서
 * 재조회로 갈라 본다 — <b>정확히 1건</b>이면 그 값(진입 직전 다른 노드가 만든 경합 결과)을 그대로
 * 쓰고, <b>2건 이상</b>이면 어느 행으로 잇는지가 추측이 되므로 발급 없이 fail-closed(null)로 닫는다.
 * <b>0건</b>일 때만 시퀀스 발급 upsert 로 나아간다.
 *
 * <h3>★ 원자 upsert 로 재진입·2노드 동시진입을 같은 userNo 로 수렴시킨다</h3>
 * <p>USER_ID 부분 유니크(V32)가 안정 키다. 발급은 {@code nextval → INSERT ON CONFLICT (USER_ID)
 * DO NOTHING} 뒤 <b>USER_ID 로 재조회</b>해 실제 userNo 를 얻는다 — 경합에서 진 노드는 자기 nextval
 * 을 버리고 이긴 행의 userNo 를 읽는다. 같은 사용자가 두 행으로 갈리면 이후 조회가 다중매칭
 * fail-closed 로 다시 잠기기 때문에 원자성이 정확성의 근거다(CWE-362).
 *
 * <h3>★ 발급 실패는 요청을 죽이지 않는다 (fail-closed, 예외 미전파)</h3>
 * <p>이것은 인가 게이트가 아니라 식별 레코드 생성이다. DB 장애로 발급이 실패했다고 요청을 500 으로
 * 끊지 않는다 — 예외를 WARN 으로만 남기고 null 을 돌려준다. 그러면 userNo 가 null 로 남아 그 요청은
 * 무권한(fail-closed)으로 흐른다(권한 상승 fail-open 절대 금지).
 *
 * <p>★ INTERNAL 채널 + 비숫자 sub 진입자에게만 호출된다 — 이 분기 판정은 호출자(필터)가 한다.
 * 숫자 sub(내부·포털)·PORTAL 채널 경로는 이 프로비저너에 닿지 않는다.
 *
 * <p>보안: 로그에 {@code userId}(로그인 ID)·PII·토큰을 남기지 않는다 — 개행 주입 표면도 없다
 * (CWE-359/117). 저장값은 {@link UserDisplayNames} 로 제어문자 제거·컬럼 폭 절단하며 조회·발급은
 * 모두 파라미터 바인딩이다(CWE-89).
 *
 * @design ADR-063
 * @design UC-041
 * @design AC-1016
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ControlUserProvisioner {

    private final UserRepository userRepository;
    private final ControlUserProvisionTxService txService;

    /**
     * 관제 인계 진입자에게 로컬 식별 레코드(userNo)를 발급하거나, 이미 있으면 그 값을 돌려준다.
     *
     * <p>절대 예외를 던지지 않는다 — 호출자(인증 필터)는 이 결과에 의존하지 않는다.
     *
     * @param userIdClaim 관제 인계 토큰의 {@code userId} 클레임(로그인 ID). null/공백이면 식별할 수
     *                    없어 아무것도 하지 않는다 — 식별 키 없이 행을 만들지 않는다.
     * @param userNm      인계 토큰의 이름 클레임(없을 수 있다). 표시 이름을 채운다.
     * @return 발급됐거나 이미 있던 userNo. 발급하지 않았거나(다중 매칭·식별 키 없음) 실패했으면 null.
     */
    public Long provision(String userIdClaim, String userNm) {
        String userId = UserDisplayNames.normalize(userIdClaim, UserDisplayNames.MAX_USER_ID_LENGTH);
        if (userId == null) {
            // 식별 키 없음(공백/제어문자뿐) — 지어내지 않는다(비숫자 sub + userId 미수신 경로).
            return null;
        }
        try {
            // 다중/경합 판정 — 재조회로 0건과 그 밖을 가른다(호출자는 둘을 null 로만 안다).
            List<Long> existing = userRepository.findUserNosByUserId(userId);
            if (existing.size() == 1) {
                // 진입 직전 다른 노드가 이미 만들었다 — 발급 없이 그 값으로 수렴한다.
                return existing.get(0);
            }
            if (existing.size() >= 2) {
                // 다중 매칭 — 어느 행인지 추측이 되므로 발급하지 않고 닫는다(AC-1017, CWE-639).
                log.warn("[Auth] control provisioning skipped — ambiguous userId (fail-closed, deny)");
                return null;
            }
            // 0건 — 시퀀스 발급 + USER_ID 원자 upsert. 진 노드의 nextval 은 버려진다.
            txService.issueUserNo(userId, UserDisplayNames.normalize(userNm, UserDisplayNames.MAX_USER_NM_LENGTH));
            // 이긴 행의 실제 userNo 재조회. 유니크(V32)라 정확히 1건이어야 한다.
            List<Long> after = userRepository.findUserNosByUserId(userId);
            if (after.size() == 1) {
                log.info("[Auth] provisioned local userNo={} for control entrant", after.get(0));
                return after.get(0);
            }
            // 재조회가 1건이 아니면(동시 다중 삽입 등 비정상) 지어내지 않고 닫는다.
            log.warn("[Auth] control provisioning inconclusive after issue (fail-closed, deny) matches={}",
                    after.size());
            return null;
        } catch (RuntimeException e) {
            // fail-closed — 발급 실패가 요청을 죽이지 않는다(무권한으로 흐른다). 무음 catch 금지.
            log.warn("[Auth] control user provisioning failed (fail-closed, deny) message={}", e.getMessage());
            return null;
        }
    }
}
