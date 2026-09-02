/**
 * 포털 라벨링 화면의 **자산 출처 판정 · 진입 주소 조립**의 단일 진실원.
 *
 * <h3>왜 이 파일이 있나</h3>
 * 포털의 라벨링 화면은 **하나뿐**이다(`/portal/label/:id`). 그 한 화면이 두 출처를 다룬다 —
 * 데이터마트에서 불러온 영상의 프레임과, 본인이 올린 영상에서 추출된 프레임이다. 업로드 자산
 * 전용 라벨링 화면은 폐기됐고 그 목적지도 없어졌다.
 *
 * ★**그런데 지금 두 출처의 식별자 체계는 아직 하나가 아니다.** 확정 사양은 「업로드 자산의
 *   프레임도 데이터마트 자산의 프레임과 같은 원장에 앉아 식별자 체계가 같다」고 적지만, 그
 *   원장 통합은 **아직 이뤄지지 않았다** — 지금은 업로드 프레임과 데이터마트 프레임이 서로
 *   다른 표에 앉아 있고 조회·저장 창구도 갈려 있다. 두 식별자는 **숫자 공간이 겹치므로**
 *   경로의 `:id` 숫자만으로는 어느 출처인지 판정할 수 없다.
 *
 * ⇒ 그래서 출처를 **주소에 명시**한다. 이 파일이 그 표기의 유일한 지점이다.
 *   원장이 실제로 합쳐지면 이 표기를 걷어내고 `:id` 하나로 여는 것이 최종 모습이며,
 *   그때 고쳐야 할 자리가 **여기 한 곳**이 되도록 화면·목록에 문자열을 흩지 않는다.
 *
 * <h3>주소 규약</h3>
 * <ul>
 *   <li>데이터마트: `/portal/label/{srcSn}` — 표기 없음(지금 동작 그대로).</li>
 *   <li>업로드 자산: `/portal/label/{uldSn}?source=upload[&frame={uldFrmeSn}]`</li>
 * </ul>
 *
 * ⚠ **업로드 출처에서 `:id` 는 프레임이 아니라 자산(`uldSn`)이다.** 두 출처가 `:id` 로 가리키는
 *   것이 다른 이유는 목록에서 행을 누르는 시점에 **첫 프레임 식별자를 알 수 없기** 때문이다 —
 *   목록 응답이 프레임 식별자를 싣는 것은 단일 프레임 자산뿐이라 영상 자산에는 그 값이 없다.
 *   자산으로 열고 화면이 자산 상세로 프레임 목록을 받는 것이 유일하게 성립하는 진입이다.
 *   현재 프레임은 `frame` 표기가 나른다(없으면 첫 프레임).
 *
 * ⚠ 표기가 없거나 아는 값이 아니면 **데이터마트로 판정**한다(fail-closed) — 지금까지의 동작이
 *   그것이라, 모르는 값에서 업로드 경로로 새면 있지도 않은 자산을 조회하게 된다.
 *
 * @design SCREEN-029
 * @design SCREEN-034
 * @design NAV-002
 */

/** 자산 출처를 나르는 조회 문자열 키. */
export const PORTAL_LABEL_SOURCE_PARAM = 'source';

/** 업로드 자산 출처 표기값. 이 값일 때만 업로드 경로로 판정한다. */
export const PORTAL_LABEL_SOURCE_UPLOAD = 'upload';

/** 업로드 출처에서 현재 프레임을 나르는 조회 문자열 키. */
export const PORTAL_LABEL_FRAME_PARAM = 'frame';

/** 포털 라벨링 화면이 다루는 자산 출처. */
export type PortalLabelSource = 'datamart' | 'upload';

/**
 * 주소의 출처 표기를 읽는다. 표기가 없거나 아는 값이 아니면 `datamart`.
 *
 * 판정을 이 함수 하나로 모으는 이유 — 화면·목록·리다이렉트가 각자 문자열을 비교하면 표기를
 * 바꿀 때 한쪽만 갱신돼 「목록은 업로드로 보내는데 화면은 데이터마트로 읽는」 상태가 된다.
 */
export function resolvePortalLabelSource(search: URLSearchParams): PortalLabelSource {
  return search.get(PORTAL_LABEL_SOURCE_PARAM) === PORTAL_LABEL_SOURCE_UPLOAD
    ? 'upload'
    : 'datamart';
}

/**
 * 업로드 출처의 현재 프레임 식별자. 표기가 없거나 양수로 읽히지 않으면 `undefined`(첫 프레임).
 *
 * 지어내지 않는다 — 잘못된 값을 0 이나 NaN 으로 흘려보내면 그 값으로 조회가 나간다.
 */
export function readPortalLabelFrameSn(search: URLSearchParams): number | undefined {
  const raw = search.get(PORTAL_LABEL_FRAME_PARAM);
  if (raw === null || raw.trim() === '') return undefined;
  const n = Number(raw);
  return Number.isInteger(n) && n > 0 ? n : undefined;
}

/**
 * 업로드 자산 라벨링 진입 주소. 목록의 행 액션과 폐기 경로 리다이렉트가 **함께** 쓴다.
 *
 * @param uldSn     자산 PK
 * @param uldFrmeSn 열 프레임(선택). 없으면 화면이 첫 프레임을 연다.
 */
export function buildPortalUploadLabelPath(uldSn: number, uldFrmeSn?: number): string {
  const search = new URLSearchParams({
    [PORTAL_LABEL_SOURCE_PARAM]: PORTAL_LABEL_SOURCE_UPLOAD,
  });
  if (uldFrmeSn !== undefined && Number.isInteger(uldFrmeSn) && uldFrmeSn > 0) {
    search.set(PORTAL_LABEL_FRAME_PARAM, String(uldFrmeSn));
  }
  return `/portal/label/${uldSn}?${search.toString()}`;
}
