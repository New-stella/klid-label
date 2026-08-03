package kr.co.cudo.authoring.augment.repository;

import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import kr.co.cudo.authoring.video.entity.QLsDataRaw;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

/**
 * 파생영상 등재 게이트의 <b>명령형(단건 판정) 창구</b> — 목록이 아니라 <b>쓰기 경로</b>에서 쓴다.
 *
 * <h2>왜 별도 조회가 필요한가 (S1)</h2>
 * <p>목록만 게이팅하면 배정 <b>생성 API</b>가 그대로 뚫린다 — {@code POST /v1/assignments} 는
 * {@code rawDataId} 를 요청 바디로 받아 INSERT 하므로, REVIEWER 가 미등재 파생의 rawSn 을 알아내면
 * 목록을 거치지 않고 배정할 수 있다. 목록 필터는 인가가 아니다.
 *
 * <h2>판정식을 복제하지 않는다</h2>
 * <p>술어는 {@link DerivativeWorkEligibility} <b>그 자체</b>를 재사용한다(문자열 SQL 복제 금지).
 * 두 번째 구현을 만들면 규칙이 바뀔 때 목록과 배정이 조용히 갈라져, 목록에서 사라진 영상이 배정은
 * 되거나 그 반대가 된다. 입력은 전부 파라미터 바인딩이다(CWE-89).
 */
@Repository
public class DerivativeWorkGateRepository {

    @PersistenceContext(unitName = "control")
    private EntityManager entityManager;

    /**
     * 입력 영상 중 <b>작업 대상이 될 수 없는</b>(미등재 파생) 것만 골라 반환한다.
     *
     * @param rawSns 검사 대상 영상 식별자 (비어 있으면 조회하지 않는다)
     * @return 미등재 파생 rawSn 목록 — 원본 영상·해상도 파생·그랜드퍼더링 대상은 포함되지 않는다
     */
    public List<Long> findIneligibleRawSns(Collection<Long> rawSns) {
        if (rawSns == null || rawSns.isEmpty()) {
            return List.of();
        }
        QLsDataRaw raw = QLsDataRaw.lsDataRaw;
        return new JPAQueryFactory(entityManager)
                .select(raw.rawSn)
                .from(raw)
                .where(raw.rawSn.in(rawSns),
                        DerivativeWorkEligibility.eligible(raw).not())
                .fetch();
    }
}
