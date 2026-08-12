package kr.co.cudo.authoring.version.dto;

/**
 * 「시작 버전 선택」 해석용 <b>경량 스냅샷 참조</b>(payload 미포함 프로젝션).
 *
 * <h3>어디서 오나</h3>
 * 회차↔스냅샷 매핑({@code LS_OUTPUT_VER_SNPSH}, V183)의 프로젝션이다. 즉 <b>"산출 회차 N 의 프레임 F
 * 내용은 이 스냅샷이었다"</b> 한 건을 뜻한다. 구 원천이던 {@code LS_LABEL_VERSION.VER_NO} 는 값이
 * 하나뿐이라 한 스냅샷이 여러 회차의 내용인 경우(1:N)를 담지 못해 판정 원천에서 제외됐다.
 *
 * <h3>왜 엔티티가 아니라 프로젝션인가</h3>
 * 스냅샷 본문({@code LBL_PAYLOAD})은 프레임당 최대 10MB 다. "요청 회차 이하"를 엔티티로 전부 읽으면
 * 프레임 수 × 회차 수만큼의 본문이 힙에 올라온다(CWE-770). 해석에 필요한 것은 <b>식별자와 회차</b>
 * 뿐이므로 세 값만 읽고, 프레임마다 최종 선택된 <b>1건만</b> 본문을 조회한다.
 *
 * @param dataSrcSn      프레임 PK ({@code LS_DATA_SRC.SRC_SN})
 * @param versionNo      산출 회차 번호 ({@code LS_OUTPUT_VER_SNPSH.OUTPUT_VER_NO})
 * @param labelVersionSn 스냅샷 행 PK ({@code LS_LABEL_VERSION.LBL_VERSION_SN}) — 본문 재조회 키
 * @design D5
 * @req R6
 */
public record SnapshotVersionRef(Long dataSrcSn, Integer versionNo, Long labelVersionSn) {
}
