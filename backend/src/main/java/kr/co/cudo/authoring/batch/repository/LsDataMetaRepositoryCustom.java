package kr.co.cudo.authoring.batch.repository;

import java.util.List;

/**
 * {@link LsDataMetaRepository} 커스텀 프래그먼트 — 다건 메타 배치 upsert.
 *
 * <p>파생영상(증강·해상도) 메타 전체 복사처럼 <b>수십~수백 키</b>를 한 번에 적재할 때, 키당 1문
 * ({@code upsertMeta}) 왕복은 N 회 라운드트립 + 매 호출 컨텍스트 clear 로 병목이 된다
 * (performance.md "1건씩 save 금지 → 배치"). 본 프래그먼트는 단일 JDBC 배치(1 라운드트립)로 전환한다.
 */
public interface LsDataMetaRepositoryCustom {

    /** 배치 upsert 1건 — {@code (metaKey, metaVl)}. rawSn 은 배치 공통이라 별도 인자. */
    record MetaUpsert(String metaKey, String metaVl) {
    }

    /**
     * {@link #upsertMetaReturning} 결과 — 대상 행 PK + <b>이 문장이 삽입이었는지</b>.
     *
     * @param metaSn   upsert 대상 {@code LS_DATA_META.META_SN} (삽입·갱신 모두 실제 행의 PK)
     * @param inserted {@code true}=신규 삽입 / {@code false}=기존 행 갱신(충돌). <b>같은 문장이 원자적으로
     *                 판정한 값</b>이라 선행 SELECT 와 달리 동시 실행에서도 정확히 한 쪽만 {@code true} 다.
     */
    record MetaUpsertOutcome(Long metaSn, boolean inserted) {
    }

    /**
     * {@code (RAW_SN, META_KEY)} 원자적 upsert 를 <b>단일 JDBC 배치</b>로 일괄 수행한다(PostgreSQL
     * {@code ON CONFLICT}). 모든 값은 {@code ?} 파라미터 바인딩(문자열 결합 없음 — CWE-89).
     *
     * <p><b>순서 계약 보존</b>: 실행 직전 {@code EntityManager.flush()} 로 호출자의 확정 dirty 변경
     * (예: 파생 RAW COMPLETED·deIdntfYn='Y')을 먼저 flush 하고, 실행 후 {@code clear()} 로 1차 캐시를
     * 비운다. 이는 구 {@code upsertMeta} 의 {@code @Modifying(flushAutomatically=true,
     * clearAutomatically=true)} 동작을 배치에서도 그대로 재현해, "확정 블록 뒤 메타 복사" 계약이
     * 깨지지 않도록 한다.
     *
     * @param rawSn   대상 RAW_SN (내부 파이프라인 값, 사용자 입력 아님)
     * @param entries 배치 대상 (metaKey, metaVl) 목록. 비었으면 no-op.
     */
    void upsertMetaBatch(Long rawSn, List<MetaUpsert> entries);

    /**
     * 단건 원자 upsert + <b>삽입/갱신 판정과 대상 PK 를 같은 문장에서</b> 돌려받는다 (CWE-362).
     *
     * <p><b>왜 필요한가</b>: 호출부가 "신규인가"를 upsert <i>앞의 별도 SELECT</i> 로 판정하면, 서로 다른
     * 트랜잭션 두 개가 READ COMMITTED 에서 각자 "없음"을 관측해 <b>둘 다 신규로 오판</b>한다. 값 자체는
     * {@code ON CONFLICT} 가 정리하지만, 신규 판정에 딸린 부수효과(검수큐 행 생성 등)는 2회 실행되어
     * 중복 행이 남는다. 판정을 upsert 문 자체로 옮기면 정확히 한 쪽만 {@code inserted=true} 를 받는다.
     *
     * <p><b>판정 근거는 {@code MDFCN_DT} 다</b> — 이 upsert 는 삽입 시 {@code MDFCN_DT=NULL},
     * 충돌 갱신 시 {@code MDFCN_DT=now()} 로 <b>항상</b> 세팅하므로 {@code RETURNING} 이 돌려주는 행의
     * {@code MDFCN_DT IS NULL} 이 곧 "이번에 삽입됨"이다(기존 행의 {@code MDFCN_DT} 가 NULL 이었어도
     * 갱신이 now() 로 덮으므로 오판이 없다). PostgreSQL 시스템 컬럼 {@code xmax = 0} 관용구 대신 이 축을
     * 쓰는 이유는 MVCC 구현 세부(락 획득 시 xmax 세팅)에 의존하지 않고 <b>바로 위 SET 절과 함께 눈에
     * 보이는</b> 자기 계약이기 때문이다. ⚠ {@code DO UPDATE} 의 {@code MDFCN_DT=now()} 를 지우면 이 판정이
     * 조용히 깨진다(회귀 가드: {@code VideoMetaUpsertIT}).
     *
     * <p>{@code upsertMetaBatch} 와 동일하게 실행 전 {@code flush()} / 실행 후 {@code clear()} 로 구
     * {@code @Modifying(flushAutomatically, clearAutomatically)} 계약을 재현한다. 값은 전부 {@code ?}
     * 파라미터 바인딩(문자열 결합 없음 — CWE-89).
     *
     * @return 대상 행 PK + 삽입 여부. {@code DO UPDATE} 는 항상 1행을 돌려주므로 결과가 없으면 예외.
     */
    MetaUpsertOutcome upsertMetaReturning(Long rawSn, String metaKey, String metaVl);
}
