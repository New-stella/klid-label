package kr.co.cudo.authoring.batch.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Session;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * {@link LsDataMetaRepositoryCustom} 구현 — 단일 JDBC 배치로 다건 메타 upsert.
 *
 * <p>Spring Data JPA 명명 규약({@code <RepositoryName>Impl})으로 {@link LsDataMetaRepository} 에 병합된다.
 * {@code control} 영속 유닛 {@link EntityManager} 를 사용하며, 배치는 <b>세션의 동일 JDBC 커넥션·트랜잭션</b>
 * ({@link Session#doWork})에서 실행돼 호출자의 활성 트랜잭션에 원자적으로 참여한다.
 */
public class LsDataMetaRepositoryImpl implements LsDataMetaRepositoryCustom {

    /**
     * {@code (RAW_SN, META_KEY)} upsert 단문 — {@code ?} 파라미터 바인딩만(CWE-89). 신규 삽입 시
     * {@code MDFCN_DT=NULL}, 충돌(기존 행) 갱신 시에만 {@code MDFCN_DT=now()} 로 {@code upsertMeta} 와
     * 동일 의미를 재현한다.
     */
    private static final String UPSERT_SQL =
            "INSERT INTO LS_DATA_META (RAW_SN, META_KEY, META_VL, REG_DT, MDFCN_DT) "
                    + "VALUES (?, ?, ?, now(), NULL) "
                    + "ON CONFLICT (RAW_SN, META_KEY) "
                    + "DO UPDATE SET META_VL = EXCLUDED.META_VL, MDFCN_DT = now()";

    @PersistenceContext(unitName = "control")
    private EntityManager entityManager;

    @Override
    public void upsertMetaBatch(Long rawSn, List<MetaUpsert> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        // 순서 계약 보존 — 배치 전 확정 dirty 변경을 먼저 flush(구 flushAutomatically=true 재현).
        entityManager.flush();

        entityManager.unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement ps = connection.prepareStatement(UPSERT_SQL)) {
                for (MetaUpsert e : entries) {
                    ps.setLong(1, rawSn);
                    ps.setString(2, e.metaKey());
                    ps.setString(3, e.metaVl());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });

        // 1차 캐시 stale 방지 — 배치 후 컨텍스트 clear(구 clearAutomatically=true 재현). 이후 조회가
        // 방금 배치 삽입한 DB 값을 읽는다.
        entityManager.clear();
    }
}
