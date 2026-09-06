/**
 * 포털 라벨링 화면의 **자산 출처 판정 · 진입 주소 조립**의 단일 진실원.
 *
 * <h3>왜 이 파일이 있나</h3>
 * 포털의 라벨링 화면은 **하나뿐**이다(`/portal/label/:id`). 그 한 화면이 두 출처를 다룬다 —
 * 데이터마트에서 불러온 영상의 프레임과, 본인이 올린 영상에서 추출된 프레임이다. 업로드 자산
 * 전용 라벨링 화면은 폐기됐고 그 목적지도 없어졌다.
 *
 * ★**두 출처는 조회·저장 창구가 갈려 있다.** 업로드 자산의 프레임 라벨은 자산 축 창구
 *   (`/portal/uploads/frames/...`)로, 데이터마트 프레임은 프레임 축 창구(`/portal/frames/...`)로
 *   오간다. 그리고 경로의 `:id` 가 가리키는 것도 출처마다 다르다(아래 ⚠) — **숫자만으로는 어느
 *   출처인지 판정할 수 없다.**
 *
 * ⚠ 구 서술 폐기 — *"업로드 프레임과 데이터마트 프레임이 서로 다른 표에 앉아 있다"*.
 *   실측상 **업로드 자산은 공용 원장에 앉아 있고 자산 식별자가 곧 영상 식별자**다(`ADR-058`).
 *   그렇다고 **주소 규약을 바꾸지 않는다** — 갈라야 하는 것은 표가 아니라 위의 **창구**이고,
 *   그 축은 그대로다.
 *
 * ⇒ 그래서 출처를 **주소에 명시**한다. 이 파일이 그 표기의 유일한 지점이다.
 *   창구가 실제로 합쳐지면 이 표기를 걷어내고 `:id` 하나로 여는 것이 최종 모습이며,
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
 * @design SCREEN-028
 * @design SCREEN-029
 * @design SCREEN-034
 * @design NAV-002
 */

import type { PortalWorkAssetSource } from './api';

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

/**
 * 데이터마트 자산 라벨링 진입 주소 — 표기 없는 그대로의 경로.
 *
 * ★ 이 문자열이 화면에 흩어져 있으면 위의 주소 규약이 **선언만 남고 실제로는 두 곳**이 된다.
 *   실제로 목록 화면이 이 경로를 직접 조립하고 있었고, 그래서 업로드 축 행도 같은 경로로
 *   보내는 결함이 났다.
 */
export function buildPortalDatamartLabelPath(srcSn: number): string {
  return `/portal/label/${srcSn}`;
}

/**
 * 「내 작업」 목록 한 행의 **이어서 작업 진입 주소** — 출처에 따라 갈린다. @design SCREEN-028
 *
 * <ul>
 *   <li>데이터마트: 프레임 식별자로 연다 → {@link buildPortalDatamartLabelPath}</li>
 *   <li>업로드 자산: **자산 식별자로 열고** 프레임은 표기가 나른다 → {@link buildPortalUploadLabelPath}</li>
 * </ul>
 *
 * ★ **진입 대상 프레임이 없으면 `null` 을 돌려준다** — 두 축 모두 들어갈 자리가 없다. 「첫 프레임으로
 *   대신 연다」로 때우지 않는다(그 판정은 서버가 이미 했고, 서버가 `null` 을 준 것은 프레임이 0건이라
 *   여는 것 자체가 성립하지 않는다는 뜻이다). 호출측은 `null` 을 받으면 그 행의 이어서 작업을
 *   **누를 수 없게** 하고 사유를 보여 준다.
 *
 * ⚠ 반환 타입에 `null` 을 둔 것이 그 처리를 **강제하는 장치**다 — 문자열만 돌려주면 호출측이
 *   빠뜨려도 컴파일이 통과해 `/portal/label/null` 같은 주소가 나간다.
 *
 * @param assetSource 자산 출처(BE `assetSource` 값 그대로)
 * @param rawSn       대상 영상 식별자. 업로드 축에서는 자산 식별자와 같은 값이다
 * @param entrySrcSn  진입 대상 프레임. 프레임이 0건이면 `null`
 */
export function buildPortalWorkLabelPath(
  assetSource: PortalWorkAssetSource,
  rawSn: number,
  entrySrcSn: number | null,
): string | null {
  if (entrySrcSn === null || entrySrcSn === undefined) return null;
  return assetSource === 'PORTAL_UPLOAD'
    ? buildPortalUploadLabelPath(rawSn, entrySrcSn)
    : buildPortalDatamartLabelPath(entrySrcSn);
}
