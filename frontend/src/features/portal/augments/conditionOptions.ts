/**
 * 포털 증강 요청의 **생성 조건 어휘** — 항목·순서·허용 코드·우리말 표시명의 단일 정의 지점.
 *
 * <h3>값역은 닫혀 있다</h3>
 * 시간대·계절·날씨·지형·심각도 **다섯 항목 전부 필수**이고 값은 허용 코드로 닫혀 있다. 하나라도
 * 비거나 코드 밖 값이면 접수 창구가 입력 오류로 거부한다. 다섯을 모두 요구하는 것은 하나라도
 * 비면 위탁받는 쪽이 어떤 기본값으로 채울지 이쪽에서 알 수 없어 같은 요청의 결과가 비결정적이
 * 되기 때문이다 — 외부 계약 자체는 최소 한 항목만 요구하므로 **우리가 더 엄격한 쪽이며 의도된
 * 선택**이다. "계약이 선택이니 완화하자"로 되돌리지 말 것.
 *
 * ⚠ **구 서술 폐기.** *"조건을 이루는 개별 항목과 그 허용 값역은 정하지 않는다"* 와
 *   *"내부 채널 증강 요청 창구의 조건 항목과 값역을 그대로 옮겨 오지 말 것"* 은 **둘 다
 *   폐기**됐다. 그 둘을 근거로 이 모듈을 지우지 말 것 — 지우면 화면이 무엇을 물어야 할지 모르고
 *   빈 조건은 접수가 거부되므로 요청 조작 자체가 성립하지 않던 상태로 되돌아간다.
 *
 * <h3>이벤트 유형·세부 유형·증강 종류를 여기 두지 않는다</h3>
 * 요청자가 고르지 않는다. 서버가 위탁 시점에 중립 값을 고정으로 싣고 세부 유형은 아예 보내지
 * 않으며, 증강 종류를 고르는 자리는 요청 본문에 없다. **이 축은 최근에 뒤집혔다 — 요청자가
 * 고르게 하는 형태로 되돌리지 말 것.**
 *
 * <h3>왜 내부 채널 상수를 가져다 쓰지 않는가</h3>
 * 코드 값역은 같지만 **우리말 표시명이 다르다**(예: `DUSK` 를 내부 채널은 「황혼」, 이 화면은
 * 「해질녘」으로 부른다). 표시명은 각 화면 사양이 소유하므로 한쪽을 다른 쪽에 맞추면 상대 화면의
 * 문구가 조용히 바뀐다. 코드가 갈라지지 않는지는 시험이 두 표를 맞대어 지킨다.
 *
 * @design API-231
 * @design SCREEN-033
 * @design ADR-061
 */

/** 항목 키와 **표시 순서**. 화면의 입력 차례이자 조건 표기의 나열 차례다. */
export const PORTAL_AUGMENT_CONDITION_KEYS = [
  'time',
  'season',
  'weather',
  'terrain',
  'severity',
] as const;
export type PortalAugmentConditionKey = (typeof PORTAL_AUGMENT_CONDITION_KEYS)[number];

/** 축별 허용 코드 — 접수 창구가 정본이고 이 표는 그것을 인용한다. */
export const PORTAL_AUGMENT_CONDITION_CODES = {
  time: ['DAWN', 'DAY', 'DUSK', 'NIGHT'],
  season: ['SPRING', 'SUMMER', 'AUTUMN', 'WINTER'],
  weather: ['CLEAR', 'CLOUDY', 'RAIN', 'SNOW', 'FOG', 'WINDY'],
  terrain: ['ROAD', 'UNDERPASS', 'RIVER', 'URBAN', 'RESIDENTIAL', 'RURAL', 'MOUNTAIN', 'FOREST'],
  severity: ['LOW', 'MEDIUM', 'HIGH'],
} as const satisfies Record<PortalAugmentConditionKey, readonly string[]>;

export type PortalAugmentTimeCode = (typeof PORTAL_AUGMENT_CONDITION_CODES.time)[number];
export type PortalAugmentSeasonCode = (typeof PORTAL_AUGMENT_CONDITION_CODES.season)[number];
export type PortalAugmentWeatherCode = (typeof PORTAL_AUGMENT_CONDITION_CODES.weather)[number];
export type PortalAugmentTerrainCode = (typeof PORTAL_AUGMENT_CONDITION_CODES.terrain)[number];
export type PortalAugmentSeverityCode = (typeof PORTAL_AUGMENT_CONDITION_CODES.severity)[number];

/**
 * 항목별 표시 메타 — **우리말 이름과 코드 표시 문구의 단일 정의 지점**.
 *
 * 컴포넌트는 이 표를 순회할 뿐 목록을 복제하지 않는다. 복제하면 코드가 늘 때 한쪽만 고쳐져
 * 화면과 전송값이 갈라진다. 전송값은 언제나 코드다.
 */
// ★ 우리말 이름표는 포털 생성형 AI 와 같게 둔다(황혼 · 도심 · 주거지역 · 시골 · 숲) — 포털 화면 검토 결정(2026-09-14).
//   가리키는 코드는 그대로다.
export const PORTAL_AUGMENT_CONDITION_FIELDS: {
  [K in PortalAugmentConditionKey]: {
    label: string;
    codes: readonly (typeof PORTAL_AUGMENT_CONDITION_CODES)[K][number][];
    codeLabel: Readonly<Record<(typeof PORTAL_AUGMENT_CONDITION_CODES)[K][number], string>>;
  };
} = {
  time: {
    label: '시간대',
    codes: PORTAL_AUGMENT_CONDITION_CODES.time,
    codeLabel: { DAWN: '새벽', DAY: '낮', DUSK: '황혼', NIGHT: '밤' },
  },
  season: {
    label: '계절',
    codes: PORTAL_AUGMENT_CONDITION_CODES.season,
    codeLabel: { SPRING: '봄', SUMMER: '여름', AUTUMN: '가을', WINTER: '겨울' },
  },
  weather: {
    label: '날씨',
    codes: PORTAL_AUGMENT_CONDITION_CODES.weather,
    codeLabel: {
      CLEAR: '맑음',
      CLOUDY: '흐림',
      RAIN: '비',
      SNOW: '눈',
      FOG: '안개',
      WINDY: '바람',
    },
  },
  terrain: {
    label: '지형',
    codes: PORTAL_AUGMENT_CONDITION_CODES.terrain,
    codeLabel: {
      ROAD: '도로',
      UNDERPASS: '지하차도',
      RIVER: '하천',
      URBAN: '도심',
      RESIDENTIAL: '주거지역',
      RURAL: '시골',
      MOUNTAIN: '산지',
      FOREST: '숲',
    },
  },
  severity: {
    label: '심각도',
    codes: PORTAL_AUGMENT_CONDITION_CODES.severity,
    codeLabel: { LOW: '낮음', MEDIUM: '보통', HIGH: '높음' },
  },
};

/** 조건 항목의 우리말 이름. **모르는 키는 받은 이름을 그대로 돌려준다**(감추지 않는다). */
export function portalAugmentConditionLabel(key: string): string {
  return key in PORTAL_AUGMENT_CONDITION_FIELDS
    ? PORTAL_AUGMENT_CONDITION_FIELDS[key as PortalAugmentConditionKey].label
    : key;
}

/** 코드의 우리말 문구. **모르는 값은 받은 값을 그대로 돌려준다**(감추지 않는다). */
export function portalAugmentConditionCodeLabel(key: string, code: string): string {
  if (!(key in PORTAL_AUGMENT_CONDITION_FIELDS)) return code;
  const table = PORTAL_AUGMENT_CONDITION_FIELDS[key as PortalAugmentConditionKey].codeLabel;
  return (table as Record<string, string>)[code] ?? code;
}

/** 그 축의 허용 코드인가 — 초안 문자열을 전송값으로 좁히는 fail-closed 판정. */
export function isPortalAugmentConditionCode(key: PortalAugmentConditionKey, value: string): boolean {
  return (PORTAL_AUGMENT_CONDITION_CODES[key] as readonly string[]).includes(value);
}

/** 자유 지시문 길이 상한. 초과분을 잘라 보내지 않고 요청이 거부되므로 화면이 먼저 막는다. */
export const PORTAL_AUGMENT_PROMPT_MAX_LENGTH = 1000;

/** 전송되는 구조화 생성 조건 — 다섯 항목 전부 필수. */
export interface PortalAugmentGenerationCondition {
  time: PortalAugmentTimeCode;
  season: PortalAugmentSeasonCode;
  weather: PortalAugmentWeatherCode;
  terrain: PortalAugmentTerrainCode;
  severity: PortalAugmentSeverityCode;
}

/**
 * 입력 폼이 들고 있는 **초안** — 미선택은 빈 문자열이다.
 *
 * 전송 타입과 분리하는 이유는 "아직 고르지 않음"을 타입으로 표현하기 위해서다.
 */
export type PortalAugmentConditionDraft = Record<PortalAugmentConditionKey, string>;

/** 빈 초안 — 상수 객체를 공유하지 않도록 매 호출 새 객체를 만든다(불변성). */
export const createEmptyPortalAugmentCondition = (): PortalAugmentConditionDraft => ({
  time: '',
  season: '',
  weather: '',
  terrain: '',
  severity: '',
});

/**
 * 초안을 전송값으로 좁힌다 — 다섯 항목이 **모두 허용 코드일 때만** 값을 돌려준다.
 *
 * 미선택과 코드 밖 값을 가르지 않는 이유는 화면이 닫힌 목록만 제공해 코드 밖 값이 들어올 경로가
 * 없기 때문이다. 그럼에도 값이 새면 접수 창구가 같은 사유로 거부한다(이중 방어).
 */
export function toPortalAugmentGenerationCondition(
  draft: PortalAugmentConditionDraft,
): PortalAugmentGenerationCondition | null {
  const ok = PORTAL_AUGMENT_CONDITION_KEYS.every((key) =>
    isPortalAugmentConditionCode(key, draft[key]),
  );
  return ok ? ({ ...draft } as unknown as PortalAugmentGenerationCondition) : null;
}

/** 아직 고르지 않았거나 허용 코드가 아닌 항목의 **우리말 이름** 목록(표시 순서 그대로). */
export function missingPortalAugmentConditionLabels(
  draft: PortalAugmentConditionDraft,
): string[] {
  return PORTAL_AUGMENT_CONDITION_KEYS.filter(
    (key) => !isPortalAugmentConditionCode(key, draft[key]),
  ).map((key) => PORTAL_AUGMENT_CONDITION_FIELDS[key].label);
}
