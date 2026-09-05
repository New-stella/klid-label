package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관제 인계 진입자 로컬 식별 레코드 발급의 <b>트랜잭션 경계</b>만 담당하는 얇은 서비스
 * (@design ADR-063 · UC-041 · AC-1016).
 *
 * <h3>왜 {@link ControlUserProvisioner} 와 분리했나</h3>
 * <p>{@link AutoWorkerRegisterTxService} 와 같은 이유 둘이다.
 * <ol>
 *   <li><b>자기호출 프록시 우회</b> — 한 빈 안에서 {@code @Transactional} 메서드를 스스로 부르면
 *       프록시를 타지 않아 트랜잭션이 열리지 않는다. 그러면 {@code @Modifying} 문장이 런타임에
 *       실패하는데 테스트는 통과할 수 있다.</li>
 *   <li><b>트랜잭션 안에서 예외 삼키기</b> — fail-closed 를 위해 예외를 잡는 곳이 트랜잭션 안쪽이면
 *       그 트랜잭션은 rollback-only 로 표시돼 커밋 시점에 다시 터진다. try/catch 는 이 경계
 *       <b>바깥</b>(프로비저너)에 둔다.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ControlUserProvisionTxService {

    private final UserRepository userRepository;

    /**
     * userNo 를 시퀀스로 발급해 {@code LS_ACNT_USER} 에 <b>원자 upsert</b> 한다(발급 + USER_ID 충돌
     * 흡수). 2노드 동시 진입에서도 USER_ID 유니크 위반으로 트랜잭션이 abort 되지 않는다 —
     * {@code ON CONFLICT (USER_ID) DO NOTHING} 이 충돌을 예외 없이 흡수한다. 진 노드가 뽑은
     * nextval 은 버려지고(간극 허용), 호출자가 뒤이어 재조회로 이긴 행의 실제 userNo 를 읽는다.
     *
     * @param userId 정규화된 사용자아이디(안정 키). 호출자가 정규화·컬럼 폭 절단을 마친 값이다.
     * @param userNm 정규화된 표시 이름(없으면 null → 빈 문자열로 저장).
     * @return 신규 발급됐으면 1, 이미 같은 USER_ID 행이 있어 충돌 흡수됐으면 0
     */
    @Transactional("controlTransactionManager")
    public int issueUserNo(String userId, String userNm) {
        return userRepository.insertWithIssuedUserNo(userId, userNm);
    }
}
