package kr.co.cudo.authoring.version.dto;

import java.time.LocalDateTime;

/**
 * 영상 단위 산출 버전 1건 — 「시작 버전 선택」 목록 항목.
 *
 * <p><b>번호가 찍힌 스냅샷이 있는 버전만 나온다</b>. 어떤 회차에 <b>모든 프레임의 내용이 그대로</b>였다면
 * {@code (DATA_SRC_SN, VERSION_HASH)} UNIQUE 때문에 그 회차 스냅샷이 하나도 생기지 않아 목록에서
 * 빠진다 — 그 회차를 골라도 직전 회차와 <b>완전히 같은 상태</b>라 선택지로서 의미가 없기 때문이다
 * (건너뛴 번호가 보이는 것은 정상이며 결손이 아니다).
 *
 * @param versionNo   산출 버전 번호 (= {@code LS_DATASET_EXPORT.OUTPUT_VER_NO} = 산출 폴더 {@code v{n}})
 * @param snapshotCnt 그 회차에 <b>내용이 바뀌어</b> 새로 스냅샷이 생긴 프레임 수(영상 전체 프레임 수가 아니다)
 * @param latestRegDt 그 회차 스냅샷 중 가장 늦은 생성 시각
 * @design D4
 * @req R6
 */
public record VideoVersionItem(Integer versionNo, long snapshotCnt, LocalDateTime latestRegDt) {
}
