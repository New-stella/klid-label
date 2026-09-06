// 포털 메타 패널의 <b>항목 식별·배치·표시</b> 규칙. 컴포넌트 import 없는 순수 모듈.
//
// <h3>★★ 식별자는 메타 키 단독이 아니라 (축, 메타 키) 쌍이다</h3>
// 개인정보 세 항목({@link PRIVACY_KEYS})이 <b>영상 축과 프레임 축 양쪽에 같은 이름</b>으로 있다.
// 키만으로 찾거나 키를 React key 로 쓰면 두 축의 값이 한 목록에서 섞이고, 한쪽을 고치면 다른
// 쪽이 함께 움직인다. 이 모듈의 모든 조회·비교는 {@link axisKeyOf} 를 거친다.
//
// <h3>배치 순서는 사양 고정이다</h3>
// 촬영환경 → 영상축 개인정보 → 프레임 설명 → 프레임축 개인정보 → 시계열 메타 → 이벤트 어노테이션.
// (이벤트 어노테이션은 창구가 달라 별도 패널이다.) 임의로 바꾸지 말 것.
//
// <h3>어느 목록에 담길지는 <b>서버가 정한다</b></h3>
// 편집 가능 / 표시 전용 / 영상 기술 세 목록의 배분은 응답이 이미 갈라서 준다. 화면이 키 접두를
// 파싱해 스스로 가르지 않는다 — 그러면 두 번째 진실원이 되어 서버가 접두를 늘리는 날 어긋난다.
// 이 모듈이 하는 것은 <b>편집 가능 목록 안에서의 배치</b>와 컨트롤 종류 결정뿐이다.

// @design SCREEN-029 @design API-234 @design AC-1068

import {
  SEASON_OPTIONS,
  TIME_OF_DAY_OPTIONS,
  WEATHER_OPTIONS,
} from '@/features/label/utils/shootingEnvironmentOptions';
import { importedMetaLabel } from '@/features/auto/metaKeys';

import type { PortalMetaItem, PortalMetaScope } from './types';

/** 메타 키 — BE `PortalColumnMetaField.Keys` 미러(값의 소유자는 데이터 계층이다). */
export const PORTAL_META_KEYS = {
  ENV_WEATHER: 'env.weather',
  ENV_TIME_OF_DAY: 'env.timeOfDay',
  ENV_SEASON: 'env.season',
  PRIVACY_ANONYMITY: 'privacy.anonymity',
  PRIVACY_PSEUDONYMITY: 'privacy.pseudonymity',
  PRIVACY_PRIVACY_INCLUDED: 'privacy.privacyIncluded',
  FRAME_DESCRIPTION: 'frame.description',
} as const;

/** ★두 축에 <b>같은 이름</b>으로 존재하는 키들 — 이 목록이 곧 「키만으로 식별하면 안 되는」 근거다. */
export const PRIVACY_KEYS = [
  PORTAL_META_KEYS.PRIVACY_ANONYMITY,
  PORTAL_META_KEYS.PRIVACY_PSEUDONYMITY,
  PORTAL_META_KEYS.PRIVACY_PRIVACY_INCLUDED,
] as const;

/**
 * 프레임 설명 입력 폭 — <b>좁은 쪽</b>이다.
 *
 * 오버레이 저장 폭은 2000자인데 원본 컬럼이 1000자라, 넓은 쪽을 허용하면 사용자가 다 쓰고 나서
 * 거부당한다(창구도 1000자로 거부한다). 화면이 입력 단계에서 좁은 쪽을 강제한다.
 */
export const FRAME_DESCRIPTION_MAX_LENGTH = 1000;

/** 그 밖의 메타 값 폭 — 저장 창구·메타 원장 컬럼과 같은 값. */
export const META_VALUE_MAX_LENGTH = 2000;

/** 항목의 입력 수단. */
export type PortalMetaControl =
  | { kind: 'select'; options: readonly { code: string; label: string }[] | readonly string[] }
  /** 예/아니오 판정(Y·N) — 값이 없는 상태도 표현할 수 있어야 한다(판정하지 않음 ≠ 아니오). */
  | { kind: 'yn' }
  | { kind: 'text'; maxLength: number };

export interface PortalMetaFieldDef {
  scope: PortalMetaScope;
  metaKey: string;
  label: string;
  control: PortalMetaControl;
}

const YN: PortalMetaControl = { kind: 'yn' };

function privacyFields(scope: PortalMetaScope): PortalMetaFieldDef[] {
  return [
    { scope, metaKey: PORTAL_META_KEYS.PRIVACY_ANONYMITY, label: '익명여부', control: YN },
    { scope, metaKey: PORTAL_META_KEYS.PRIVACY_PSEUDONYMITY, label: '가명여부', control: YN },
    {
      scope,
      metaKey: PORTAL_META_KEYS.PRIVACY_PRIVACY_INCLUDED,
      label: '개인정보 포함여부',
      control: YN,
    },
  ];
}

/** 사양 고정 순서의 섹션 정의. 마지막 시계열 메타 섹션은 «나머지 전부»라 여기 열거하지 않는다. */
export const PORTAL_META_SECTIONS: { title: string; fields: PortalMetaFieldDef[] }[] = [
  {
    title: '촬영환경',
    fields: [
      {
        scope: 'video',
        metaKey: PORTAL_META_KEYS.ENV_WEATHER,
        label: '날씨',
        control: { kind: 'select', options: WEATHER_OPTIONS },
      },
      {
        scope: 'video',
        metaKey: PORTAL_META_KEYS.ENV_TIME_OF_DAY,
        label: '시간대',
        control: { kind: 'select', options: TIME_OF_DAY_OPTIONS },
      },
      {
        scope: 'video',
        metaKey: PORTAL_META_KEYS.ENV_SEASON,
        label: '계절',
        control: { kind: 'select', options: SEASON_OPTIONS },
      },
    ],
  },
  { title: '개인정보(영상)', fields: privacyFields('video') },
  {
    title: '프레임 설명',
    fields: [
      {
        scope: 'frame',
        metaKey: PORTAL_META_KEYS.FRAME_DESCRIPTION,
        label: '설명',
        control: { kind: 'text', maxLength: FRAME_DESCRIPTION_MAX_LENGTH },
      },
    ],
  },
  { title: '개인정보(프레임)', fields: privacyFields('frame') },
];

/** 섹션 정의가 자리를 잡아 둔 (축, 키) 쌍 전부 — 시계열 섹션은 이 여집합이다. */
const PLACED_AXES = new Set(
  PORTAL_META_SECTIONS.flatMap((s) => s.fields.map((f) => axisKeyOf(f.scope, f.metaKey))),
);

/**
 * ★원소의 식별자 — <b>(축, 메타 키) 쌍</b>.
 *
 * 키만 쓰면 개인정보 세 항목의 영상 값과 프레임 값이 같은 자리로 접힌다. 화면의 상태 맵·React
 * key·전송 대상 판정이 모두 이 값을 쓴다.
 */
export function axisKeyOf(scope: PortalMetaScope, metaKey: string): string {
  return `${scope}:${metaKey}`;
}

/** 원소의 식별자. */
export function axisKeyOfItem(item: { scope: PortalMetaScope; metaKey: string }): string {
  return axisKeyOf(item.scope, item.metaKey);
}

/**
 * 편집 가능 목록을 사양 순서의 섹션으로 나눈다.
 *
 * ★응답에 없는 항목은 <b>만들어 내지 않는다</b>(그 축이 서버에서 오지 않았다는 사실이 사라진다).
 * ★섹션 정의에 없는 항목은 <b>버리지 않고</b> 시계열 메타로 모은다 — 서버가 키를 늘려도 값이
 *   화면에서 증발하지 않아야 한다(조용한 손실 금지).
 */
export function splitEditableItems(items: PortalMetaItem[]): {
  sections: { title: string; rows: { field: PortalMetaFieldDef; item: PortalMetaItem }[] }[];
  timeseries: PortalMetaItem[];
} {
  const byAxis = new Map<string, PortalMetaItem>();
  for (const item of items) byAxis.set(axisKeyOfItem(item), item);

  const sections = PORTAL_META_SECTIONS.map((section) => ({
    title: section.title,
    rows: section.fields.flatMap((field) => {
      const item = byAxis.get(axisKeyOf(field.scope, field.metaKey));
      return item === undefined ? [] : [{ field, item }];
    }),
  }));

  const timeseries = items.filter((item) => !PLACED_AXES.has(axisKeyOfItem(item)));
  return { sections, timeseries };
}

/**
 * 「내가 덮었는가」 표시. <b>메타 패널이 그리는 배지는 이것 하나뿐이다.</b>
 *
 * <h3>★★ 표시가 하나로 줄었다고 두 축이 하나가 된 것은 아니다</h3>
 * 이쪽은 «내 값이 덮었는가»이고, {@link PortalMetaItem.source}(원본 쪽 값의 출처)는 «덮인 쪽이
 * 무엇이었는가»다. 여전히 서로 다른 것을 말한다 — 다만 <b>출처는 화면에 표시하지 않고 전송
 * 판정에만</b> 쓴다(`metaSavePayload.buildMetaSavePayload` → `isUserDeterminedMetaValue`).
 * 내부 화면도 같은 값을 표시하지 않고 전송 판정에만 쓰므로 포털만 다르게 할 근거가 없다.
 *
 * ⚠ <b>화면에 안 쓴다는 이유로 {@link PortalMetaItem.source} 를 죽은 필드로 보고 응답 타입·파싱·
 *   저장 판정에서 걷어내지 말 것</b> — 사람이 손대지 않은 자동 계산값이 그대로 되돌아가 <b>사람의
 *   판정으로 승격</b>되는 것을 막는 방어가 통째로 사라진다. 그런데 화면상 증상은 없다(저장은 200
 *   이고 값도 그대로 보인다) — 오염은 조용히 쌓인다.
 *
 * ⚠ 표시 방식(문구·배지 모양)은 <b>확정된 시안이 없다</b>. 최소한의 평이한 표기이며 시안 확정 전
 *   잠정이다.
 */
export function overriddenBadge(item: PortalMetaItem): string | null {
  return item.overridden ? '내가 고침' : null;
}

/**
 * 표시 전용·영상 기술 메타 행의 이름.
 *
 * ★이름을 정하지 못한 열쇠는 <b>버리지 않고 원문 열쇠 그대로</b> 쓴다(조용한 손실 금지).
 * 이관 원문 열쇠의 이름표는 이미 단일 지점이 있어 그것을 부른다 — 여기서 다시 표를 만들지 않는다.
 */
export function readOnlyMetaLabel(metaKey: string): string {
  return importedMetaLabel(metaKey);
}
