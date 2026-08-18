package kr.co.cudo.authoring.user.service;

import kr.co.cudo.authoring.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 최종로그인일시 기록의 <b>트랜잭션 경계</b>만 담당하는 얇은 서비스.
 *
 * <h3>왜 {@link LastLoginRecorder} 와 분리했나 (두 가지 실패 패턴 차단)</h3>
 * <ol>
 *   <li><b>자기호출 프록시 우회</b> — 한 빈 안에서 {@code @Transactional} 메서드를 스스로 부르면
 *       프록시를 타지 않아 트랜잭션이 <b>열리지 않는다</b>. 그러면 {@code @Modifying} 문장이
 *       런타임에 실패하는데 테스트는 통과할 수 있다(이 저장소의 반복 결함 패턴). 호출자와 트랜잭션
 *       경계를 <b>서로 다른 빈</b>으로 두면 그 우회가 구조적으로 불가능하다.</li>
 *   <li><b>트랜잭션 안에서 예외 삼키기</b> — fail-open 을 위해 예외를 잡는 곳이 트랜잭션
 *       <b>안쪽</b>이면 그 트랜잭션은 이미 rollback-only 로 표시돼 커밋 시점에 다시 터진다. 그래서
 *       try/catch 는 이 경계 <b>바깥</b>(recorder)에 둔다.</li>
 * </ol>
 *
 * <p>기록은 필터에서 호출되므로 진행 중인 트랜잭션이 없다 — 이 메서드가 짧은 쓰기 트랜잭션을
 * 직접 연다. 읽기 트랜잭션({@code readOnly})과 섞이지 않도록 기존 {@code UserService} 에 얹지 않았다.
 *
 * @design SCREEN-024
 */
@Component
@RequiredArgsConstructor
public class LastLoginTouchTxService {

    private final UserRepository userRepository;

    /**
     * 조건부 UPDATE 로 최종로그인일시를 기록한다.
     *
     * @return 기록했으면 1, throttle 창 안이거나 대상 사용자가 없으면 0
     */
    @Transactional("controlTransactionManager")
    public int touch(long userNo, LocalDateTime now, LocalDateTime threshold) {
        return userRepository.touchLastLogin(userNo, now, threshold);
    }
}
