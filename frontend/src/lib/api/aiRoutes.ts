// AI 보조 창구 경로 — 채널(내부/포털)에 따른 경로 조립의 **단일 지점**.
//
// ★ 포털 채널은 내부 AI 창구를 쓰지 않는다. 서버가 포털 전용 창구를 따로 두었고(채널 격리 유지)
//   본문·응답·취소 규약은 내부 창구와 같다. 그래서 화면이 달리 할 일은 **경로 접두** 하나뿐이다.
//   이 규칙이 호출 함수마다 흩어지면 한 곳만 빠뜨려도 포털에서 그 기능이 403 이 된다(특히 취소 —
//   빠뜨리면 «취소했는데 서버는 계속 돈다»가 조용히 생긴다). 그래서 여기서만 조립한다.
//
// ★ 채널 판정은 이 모듈이 하지 않는다 — 인자로 받는다. 판정은 라벨링 화면의 `portalMode` 한 축에서
//   파생해 내려보낸다(두 번째 게이팅 축을 만들지 않는다).
//
// ⚠ 선택 객체 추적(`sam2-track`)은 여기에 없다 — 포털에 대응 창구가 없는 내부 전용 기능이다.
//
// @design API-255, API-257, API-254, API-256, API-258

/** 프레임 단위 AI 실행 창구 — 두 채널에 모두 있는 것만 둔다. */
export type AiFrameOp = 'autolabel' | 'sam2-segment' | 'yolo-track';

/** 포털 채널이면 `/portal` 접두, 내부 채널이면 접두 없음. */
function channelPrefix(portal: boolean): string {
  return portal ? '/portal' : '';
}

/** `POST /v1[/portal]/frames/{srcSn}/{op}` */
export function aiFramePath(portal: boolean, srcSn: number, op: AiFrameOp): string {
  return `${channelPrefix(portal)}/frames/${srcSn}/${op}`;
}

/** `GET /v1[/portal]/ai-defaults` */
export function aiDefaultsPath(portal: boolean): string {
  return `${channelPrefix(portal)}/ai-defaults`;
}

/**
 * `POST /v1[/portal]/ai-requests/{requestId}/cancel`
 *
 * 경로 조립은 언제나 인코딩한다(CWE-22) — 지금은 화면이 만든 값만 들어오지만 규칙은 입력 출처에
 * 따라 흔들리지 않는다.
 */
export function aiCancelPath(portal: boolean, requestId: string): string {
  return `${channelPrefix(portal)}/ai-requests/${encodeURIComponent(requestId)}/cancel`;
}
