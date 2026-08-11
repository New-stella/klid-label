package kr.co.cudo.authoring.version.dto;

/**
 * 영상 단위 「시작 버전 선택」 적용 결과.
 *
 * <p><b>{@code unresolvedFrames} 를 숨기지 않는다</b>: 요청 버전 이하 스냅샷이 없는 프레임은
 * 되돌릴 근거가 없어 <b>건드리지 않고</b> 건너뛴다(없는 상태를 추측해 라벨을 지우거나 폐기하지 않는다).
 * 그 사실을 응답에 싣지 않으면 화면이 "전부 되돌렸다"고 표시해 거짓말이 된다.
 *
 * <p>스냅샷이 없는 대표 사례 ①그 버전 시점에 아직 추출되지 않은 프레임 ②그 시점에 라벨이 0건이라
 * 승인 스냅샷 자체가 만들어지지 않은 프레임(라벨 0건 프레임은 스냅샷을 남기지 않는 기존 규약).
 *
 * @param rawSn            영상 PK
 * @param versionNo        적용한 산출 버전 번호
 * @param totalFrames      영상의 전체 프레임 수(폐기분 포함)
 * @param appliedFrames    스냅샷을 찾아 되돌린 프레임 수(내용이 이미 같아 no-op 인 경우 포함)
 * @param revivedFrames    폐기 → 사용으로 되살아난 프레임 수
 * @param discardedFrames  사용 → 폐기로 되돌아간 프레임 수
 * @param unresolvedFrames 요청 버전 이하 스냅샷이 없어 건너뛴 프레임 수
 * @design D4
 * @req R6
 */
public record StartVersionApplyResult(Long rawSn, int versionNo, int totalFrames, int appliedFrames,
                                      int revivedFrames, int discardedFrames, int unresolvedFrames) {
}
