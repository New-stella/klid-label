/**
 * 디자인 채널 — **어느 디자인 시스템으로 산출할지**의 단일 판정 지점.
 *
 * <h3>이 파일이 판정하는 것과 하지 않는 것</h3>
 * 여기서 정하는 것은 **토큰 값의 출처**(DS-001 / DS-002) 하나다. 어느 화면이 뜨는지는
 * `router/index.tsx` 가 같은 환경변수로 이미 가른다 — 두 판정이 **같은 키**
 * (`VITE_BUILD_CHANNEL`)를 읽는 것이 핵심이고, 그래서 「화면은 포털인데 토큰은 관제」 같은
 * 어긋난 조합이 생기지 않는다.
 *
 * ⚠ **`src/lib/buildChannel.ts` 의 사본이 아니다 — 실행 환경이 다르다.** 그쪽은 브라우저에서
 *   `import.meta.env` 를 읽고, 이 파일은 Tailwind 설정을 평가하는 **Node 프로세스**에서
 *   `process.env` 를 읽는다. 같은 키를 읽되 읽는 수단이 달라 한 파일로 합칠 수 없다.
 *   두 축이 어긋나지 않는 것은 회귀 가드가 **키 이름과 값 집합의 일치**를 단언해 지킨다.
 *
 * ⚠⚠ **값은 셸 환경변수로 넘겨야 한다.** `npm run build:portal` 이
 *   `VITE_BUILD_CHANNEL=portal vite build` 로 셸 앞자리에 실어 주므로 `process.env` 에 들어온다.
 *   `.env` 파일에 적는 방식은 Vite 가 `import.meta.env` 로만 주입하고 `process.env` 는
 *   건드리지 않아 **여기까지 닿지 않는다** — 화면은 포털인데 색은 관제인 상태가 된다.
 *
 * ⚠ 미설정·오타·미지의 값은 모두 관제로 떨어진다(fail-closed) — 지금 동작 보존.
 *   그 대가로 **포털 산출물은 반드시 채널을 명시해 만들어야 한다.** 명시하지 않으면 빌드는
 *   성공하고 오류도 없이 관제 색이 올라간다(`buildChannel.ts` 가 라우트 축에서 경고한 것과
 *   같은 함정이다).
 */

import { portalThemeExtend } from './ds002.js';

/** 채널 값 — `src/lib/buildChannel.ts` 의 `BUILD_CHANNELS` 와 같아야 한다(가드가 대조한다). */
export const DESIGN_CHANNELS = ['control', 'portal'];

/** 채널을 정하는 환경변수 이름 — 라우트 분기와 **같은 키**다. */
export const DESIGN_CHANNEL_ENV_KEY = 'VITE_BUILD_CHANNEL';

/** 미설정·오타·미지의 값이 떨어지는 자리. 지금 동작(관제)을 보존한다. */
export const DEFAULT_DESIGN_CHANNEL = 'control';

/**
 * 원시 환경변수 값 → 채널.
 * @param {string | undefined} raw
 * @returns {'control' | 'portal'}
 */
export function resolveDesignChannel(raw) {
  return DESIGN_CHANNELS.includes(raw) ? raw : DEFAULT_DESIGN_CHANNEL;
}

/**
 * 채널이 쓰는 theme.extend 를 돌려준다.
 *
 * @param {'control' | 'portal'} channel
 * @param {object} controlExtend 관제 채널(DS-001) theme.extend — 정의는 `tailwind.config.js` 소유
 * @returns {object}
 */
export function themeExtendForChannel(channel, controlExtend) {
  return channel === 'portal' ? portalThemeExtend(controlExtend) : controlExtend;
}
