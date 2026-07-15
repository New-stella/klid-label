package kr.co.cudo.authoring.dataset.export;

/**
 * Phase 4 — {@code LS_DATASET_EXPORT} 에 PENDING 으로 예약 삽입된 산출 레코드 식별자.
 *
 * @param exportSn 산출 레코드 PK
 * @param version  채번된 산출 버전(EXPORT_VER_NO)
 */
public record InsertedExport(long exportSn, int version) {
}
