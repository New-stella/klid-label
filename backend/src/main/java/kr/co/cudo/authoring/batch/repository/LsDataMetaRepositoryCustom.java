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
}
