package kr.co.cudo.authoring.augment.dto;

import java.time.LocalDateTime;

/**
 * 증강 결과 항목의 <b>폐기(소프트 삭제) 상태</b> — {@code LS_DATA_AUG_DSCD} 비석의 조회 축.
 *
 * <p>REVIEWER 가 반려한 증강 결과는 폐기 원장에 표식이 남고 유예 경과 후 배치가 실삭제한다. 그 정보를
 * 내려주는 GET 이 하나도 없어 화면이 <b>복구 버튼도 유예 안내도 만들 수 없었다</b> — 이 레코드가 그
 * 경로다. 폐기 상태가 <b>아닌</b> 항목(표식 없음 · 복구됨 · 해상도 파생)에서는 이 필드 자체가 {@code null}
 * 이며, "폐기되지 않음" 을 뜻하는 값 조합을 따로 두지 않는다.
 *
 * <h3>절대 싣지 않는 것 (CWE-209/359)</h3>
 * <p>{@code VDO_FILE_PATH}(NAS 실경로)는 <b>필드로도 만들지 않는다</b> — 이 응답 경로의 규약은
 * "스토리지 경로 미노출" 이다. {@code DSCD_RSN}(폐기 사유) 역시 싣지 않는다: 같은 사실이 이미
 * {@code AugmentResultItemResponse.rejectReason} 으로 나가므로 중복이고, 축이 둘이면 복구 후 두 값이
 * 어긋난다.
 *
 * @param discardedAt 폐기 표식 시각({@code DSCD_DT}) — 유예 기산점.
 * @param purgeAt     실삭제 예정 시각 = {@code discardedAt + grace-days}.
 *                    <p>폐기 스윕이 비활성({@code authoring.augment.discard.enabled=false})이면
 *                    <b>{@code null}</b> 이다 — 스윕이 뜨지 않아 영원히 지워지지 않으므로 예정 시각을
 *                    내리는 것은 거짓말이 된다.
 *                    <p><b>이 값이 이미 과거인데 {@link #purged()} 가 false 인 상태는 정상</b>이다.
 *                    스윕 주기(기본 1시간)만큼 지연되며, 그 사이에도 복구할 수 있다.
 * @param purged      DB 실삭제가 커밋됐는가 — 복구 불가(비교 이미지가 영구히 없다).
 *                    <p><b>관측 창이 좁다</b>: 실삭제는 {@code LS_DATA_AUG} 행 자체를 지우므로, 커밋 이후
 *                    그 항목은 결과 목록에서 통째로 사라져 이 축을 실을 대상조차 없다. {@code true} 가
 *                    나가는 경우는 <b>조회 트랜잭션이 잡은 증강 스냅샷과 그 뒤 읽은 폐기 표식 사이에
 *                    스윕이 커밋된 창</b>뿐이다 — 죽은 필드가 아니라 그 창에서 응답이 자기모순에 빠지지
 *                    않게 하는 장치다. 그 창에서 같은 응답의 다음 값이 <b>모두 함께</b> 맞춰진다:
 *                    {@code resultState=PURGED} · {@code decision=REJECTED}(폐기 표식의 존재 자체가
 *                    사람이 반려했다는 증거 — 검수 행은 스윕이 먼저 지운다) · {@code reviewable=false}
 *                    (누르면 404 인 버튼을 그리지 않는다) · {@code framePairs=[]} 와
 *                    {@code totalFramePairs=0}(이미 사라진 {@code srcSn} 의 죽은 이미지 링크 차단).
 * @param restorable  지금 복구를 시도할 수 있는가 — <b>UI 힌트일 뿐 최종 판정이 아니다</b>.
 *                    실제 판정은 복구 API({@code AugmentDiscardService.restore})가
 *                    {@code findOpenForUpdate}(PESSIMISTIC_WRITE)로 행을 잠그고 내린다. 그래서 화면이
 *                    버튼을 그린 뒤에도 <b>409 가 날 수 있다</b>(그 사이 스윕이 클레임·삭제를 커밋한 경우).
 *                    이 값을 인가·집행 판단에 사용하지 말 것.
 */
public record AugmentDiscardStateResponse(
        LocalDateTime discardedAt,
        LocalDateTime purgeAt,
        boolean purged,
        boolean restorable
) {
}
