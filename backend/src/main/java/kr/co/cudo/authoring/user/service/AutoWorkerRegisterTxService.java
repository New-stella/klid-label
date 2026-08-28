package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.user.repository.LsUserRoleRepository;
import kr.co.cudo.authoring.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 진입 시 작업자 자동 등록의 <b>트랜잭션 경계</b>만 담당하는 얇은 서비스.
 *
 * <h3>왜 {@link AutoWorkerRegistrar} 와 분리했나</h3>
 * <p>{@code LastLoginTouchTxService} 와 같은 이유 둘이다.
 * <ol>
 *   <li><b>자기호출 프록시 우회</b> — 한 빈 안에서 {@code @Transactional} 메서드를 스스로 부르면
 *       프록시를 타지 않아 트랜잭션이 열리지 않는다. 그러면 {@code @Modifying} 문장이 런타임에
 *       실패하는데 테스트는 통과할 수 있다.</li>
 *   <li><b>트랜잭션 안에서 예외 삼키기</b> — fail-open 을 위해 예외를 잡는 곳이 트랜잭션 안쪽이면
 *       그 트랜잭션은 rollback-only 로 표시돼 커밋 시점에 다시 터진다. try/catch 는 이 경계
 *       <b>바깥</b>(등록기)에 둔다.</li>
 * </ol>
 *
 * @design AC-126
 */
@Component
@RequiredArgsConstructor
public class AutoWorkerRegisterTxService {

    private final UserRepository userRepository;
    private final LsUserRoleRepository lsUserRoleRepository;

    /**
     * 사용자 행과 작업자 역할을 만든다. 두 쓰기 모두 <b>원자 upsert</b> 라 2노드 동시 진입에서도
     * PK 위반으로 트랜잭션이 abort 되지 않는다.
     *
     * <ul>
     *   <li>사용자 행 — <b>이름만</b> 인계 토큰의 {@code name} 클레임에서 채운다. 역할 클레임이
     *       부트스트랩 전용으로 닫히면서 표시 정보를 채우던 유일한 경로가 사라졌고, 그대로 두면
     *       배정 대상 목록이 <b>전원 공란 이름</b>으로 그려진다.
     *       ★{@code userId} 는 채우지 않는다 — 그 값은 요청 바디에서 오는 위조 가능한 값이고,
     *       이름과 달리 식별자로 읽힐 여지가 있다(CWE-639). 이름은 위조돼도 자기 행의 표시만 바뀐다.
     *       매 요청 쓰기가 되지 않는 근거는 호출 조건(역할 없음)이지 값의 유무가 아니다.</li>
     *   <li>역할 — {@code DO NOTHING} 이라 <b>이미 부여된 역할을 덮지 않는다</b>.</li>
     * </ul>
     *
     * @param userNm 인계 토큰의 이름 클레임(없을 수 있다). 공백·제어문자는 정규화되고 컬럼 폭으로 잘린다.
     * @return 역할을 실제로 부여했으면 true. 이미 역할이 있으면 false — 이때 그 역할이 무엇인지는
     *         여기서 판단하지 않는다(호출자가 fail-closed 로 무권한 처리한다).
     */
    @Transactional("controlTransactionManager")
    public boolean registerAsWorker(long userNo, String userNm) {
        // 이름만 채운 행을 만든다(값이 같으면 no-op — upsert 의 IS DISTINCT FROM 조건이 막는다).
        userRepository.upsertUser(userNo, null,
                UserDisplayNames.normalize(userNm, UserDisplayNames.MAX_USER_NM_LENGTH));
        return lsUserRoleRepository.insertRoleIfAbsent(userNo, Role.WORKER.name()) == 1;
    }
}
