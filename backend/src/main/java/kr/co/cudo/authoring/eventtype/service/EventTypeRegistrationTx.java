package kr.co.cudo.authoring.eventtype.service;

import kr.co.cudo.authoring.eventtype.repository.LsEvntTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 이벤트유형 자동등록의 <b>트랜잭션 경계 빈</b> — 등록 1건을 {@link Propagation#REQUIRES_NEW}
 * 독립 트랜잭션으로 실행한다.
 *
 * <h3>왜 별도 빈인가 (Critical — 합치지 말 것)</h3>
 * <p>등록은 <b>부가 기능</b>이라 실패해도 영상 적재를 막으면 안 되는데, 그 보장은 <b>두 겹</b>이
 * 있어야 성립한다.
 * <ol>
 *   <li><b>REQUIRES_NEW</b> — 별도 물리 트랜잭션·커넥션이라, 잠금 대기·데드락·타임아웃·제약 위반으로
 *       PostgreSQL 이 트랜잭션을 aborted 로 만들어도 <b>호출자의 트랜잭션은 멀쩡</b>하다.
 *       ({@code ON CONFLICT DO NOTHING} 이 막는 것은 PK 유니크 위반 <b>하나</b>뿐이다.)</li>
 *   <li><b>프록시 <em>바깥</em>에서의 예외 흡수</b> — 트랜잭션 메서드 <b>안</b>에서 예외를 잡는 것만으로는
 *       부족하다. Spring Data 리포지토리 호출이 예외를 던지면 참여 트랜잭션이 rollback-only 로
 *       마킹되고, 이 메서드가 정상 반환해도 <b>커밋 시점에 트랜잭션 인터셉터가</b>
 *       {@code UnexpectedRollbackException} 을 던진다 — 메서드 본문의 {@code catch} 는 이 예외를
 *       볼 수 없다. 그래서 흡수는 반드시 <b>이 빈을 호출하는 쪽</b>({@link EventTypeAutoRegistrar})
 *       에서 한다. 두 책임을 한 클래스에 합치면 자기호출로 프록시를 우회해 ①도 ②도 깨진다.</li>
 * </ol>
 * <p>⚠ 조건부 {@code DO UPDATE} 는 {@code DO NOTHING} 보다 <b>잠금 경합이 크다</b>(같은 행을 실제로
 * 갱신하므로 row lock 을 잡는다). 참여 트랜잭션이었다면 영상 적재가 롤백될 위험이 오히려 커진다 —
 * 갱신 규칙 도입으로 이 분리의 필요성이 <b>더 커졌다</b>.
 *
 * <p>회귀 가드: {@code EventTypeAutoRegisterIsolationIT}.
 */
@Component
@RequiredArgsConstructor
public class EventTypeRegistrationTx {

    private final LsEvntTypeRepository repository;

    /**
     * 미등록 유형은 등록하고, 등록된 유형은 <b>조건부로 갱신</b>한다(원자 upsert).
     *
     * <p>갱신 조건(운영자 정정 보호 · 미송신 보존 · no-op 방지)은
     * {@link LsEvntTypeRepository#registerOrRefresh} 참조.
     *
     * @return 신규 등록되거나 실제로 갱신됐으면 1, 변화 없으면 0
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, transactionManager = "controlTransactionManager")
    public int registerOrRefresh(String evntTypeCd, String evntNm, String evntClsfCd,
                                 String evntCtgryCd) {
        return repository.registerOrRefresh(evntTypeCd, evntNm, evntClsfCd, evntCtgryCd);
    }
}
