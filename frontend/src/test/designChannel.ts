import resolveConfig from 'tailwindcss-v3-compat/resolveConfig';

// tailwind.config.js 는 타입 선언이 없는 plain JS(ESM)
// @ts-expect-error -- 설정 파일은 .js 라 타입 선언이 없음
import tailwindConfig, { controlThemeExtend } from '../../tailwind.config.js';
// @ts-expect-error -- 토큰 투영도 .js 라 타입 선언이 없음
import { portalThemeExtend } from '../../design-tokens/ds002.js';
// @ts-expect-error -- 채널 판정도 .js
import { DESIGN_CHANNELS as RUNTIME_CHANNELS, themeExtendForChannel } from '../../design-tokens/channel.js';

/**
 * 디자인 채널 — **가드가 「어느 시스템으로 판정할지」를 정하는 단일 지점.**
 *
 * <h3>왜 필요한가</h3>
 * 관제 채널은 `DS-001`(KRDS), 포털 채널은 `DS-002` 를 쓴다. 두 시스템은 **토큰 이름이 같고
 * 값이 다르다** — 주색·중립 온도·본문 기준 크기·굵기 상한·모서리·그림자가 전부 갈린다.
 * 그래서 표면 관례 가드가 한 규격만 강제하면 **한쪽 채널이 반드시 틀린 판정을 받는다**:
 * 관제 규격을 포털에 걸면 시안대로 고친 화면이 FAIL 하고, 포털 규격을 관제에 걸면 멀쩡한
 * 관제 화면 수십 개가 FAIL 한다.
 *
 * <h3>★목록을 두 벌로 두지 않는다</h3>
 * 「어느 파일이 어느 축인가」의 판정은 {@link designChannelOfSourceFile} **하나**다. 가드마다
 * 자기 목록을 들면 화면이 늘 때 한쪽만 갱신돼 새 화면이 조용히 무검사 구역으로 떨어진다.
 * 판정을 **열거가 아니라 경로 규칙**으로 둔 이유도 같다 — 새 포털 파일이 자동으로 포털 축이 된다.
 *
 * ⚠ 판정이 틀리는 방향은 **안전한 쪽**이다. 포털 파일을 못 알아보면 관제 규격으로 판정되어
 *   시안대로 쓴 코드가 **시끄럽게 실패**한다(조용히 통과하지 않는다).
 */
export const DESIGN_CHANNELS = ['control', 'portal'] as const;

export type DesignChannel = (typeof DESIGN_CHANNELS)[number];

/**
 * 소스 파일이 **어느 채널 산출물에만 존재하는 화면**을 그리는가.
 *
 * 라우트가 산출 시점에 갈리므로(`router/index.tsx` 의 `IS_PORTAL_CHANNEL_BUILD`) 포털 화면은
 * 관제 산출물에 없고 그 반대도 없다. 판정 규칙은 이 저장소의 배치 관례를 그대로 쓴다:
 *
 *  · 경로에 `portal` 디렉터리 구획이 있다 (`src/pages/portal/**` · `src/features/portal/**`)
 *  · 또는 파일 이름이 `Portal`/`portal` 로 시작한다 (`PortalLayout.tsx` · `portalNav.ts`)
 *
 * 그 밖은 전부 `control` 로 본다 — 공용 컴포넌트(`DataTable` 등)도 여기 든다. 공용은 두 채널
 * 모두에서 쓰일 수 있지만, **채널이 갈리는 값은 토큰이 들고 있고 공용 컴포넌트는 토큰 이름만
 * 참조**하므로 이름 축 규격은 두 채널에서 같다(값 축은 토큰 표가 채널별로 검사된다).
 */
export function designChannelOfSourceFile(relPath: string): DesignChannel {
  const norm = relPath.replace(/\\/g, '/');
  const segments = norm.split('/');
  const dirs = segments.slice(0, -1);
  const base = segments[segments.length - 1];
  if (dirs.includes('portal')) return 'portal';
  if (/^[Pp]ortal[A-Z]/.test(base)) return 'portal';
  return 'control';
}

/** 채널별로 해석된 Tailwind theme. 두 채널의 값을 **한 번에** 들고 판정할 수 있게 한다. */
const RESOLVED: Record<DesignChannel, Record<string, unknown>> = {
  control: (
    resolveConfig({ content: [], theme: { extend: controlThemeExtend } } as never) as {
      theme: Record<string, unknown>;
    }
  ).theme,
  portal: (
    resolveConfig({
      content: [],
      theme: { extend: portalThemeExtend(controlThemeExtend) },
    } as never) as { theme: Record<string, unknown> }
  ).theme,
};

/**
 * 채널의 해석된 theme.
 *
 * ⚠ 두 번째 진실원이 아니다 — 위 `RESOLVED` 는 **설정 파일이 내보낸 같은 객체**
 *   (`controlThemeExtend`)와 **설정 파일이 쓰는 같은 함수**(`portalThemeExtend`)로 만든다.
 *   설정이 실제로 그 조합을 쓰는지는 `designTokensPortalChannel.test.ts` 가 별도로 단언한다.
 */
export function themeOf(channel: DesignChannel): Record<string, unknown> {
  return RESOLVED[channel];
}

const asObj = (v: unknown): Record<string, string> => v as Record<string, string>;

/** 채널의 색 스케일. */
export function colorScale(channel: DesignChannel, name: string): Record<string, string> {
  const colors = themeOf(channel).colors as Record<string, unknown>;
  return asObj(colors[name]);
}

/** 채널의 fontSize step — `[size, { lineHeight, fontWeight }]`. */
export function fontStep(
  channel: DesignChannel,
  name: string,
): [string, { lineHeight?: string; fontWeight?: string }] {
  const fontSize = themeOf(channel).fontSize as Record<
    string,
    [string, { lineHeight?: string; fontWeight?: string }]
  >;
  return fontSize[name];
}

/** 채널의 fontSize step 크기를 px 숫자로. */
export function fontPx(channel: DesignChannel, name: string): number {
  return Number.parseInt(fontStep(channel, name)[0], 10);
}

/**
 * 설정 쪽 채널 상수와 **값 집합이 같은지** 대조하기 위한 창구.
 * 두 목록이 갈리면 「설정은 아는데 가드는 모르는 채널」이 생겨 그 채널이 무검사가 된다.
 */
export const CONFIG_DESIGN_CHANNELS = RUNTIME_CHANNELS as readonly string[];

/** 설정이 실제로 쓰는 선택 함수 — 가드가 자기 사본으로 판정하지 않게 그대로 가져다 쓴다. */
export const configThemeExtendForChannel = themeExtendForChannel as (
  channel: string,
  controlExtend: unknown,
) => unknown;

/** 설정 파일의 default export(= 실제 산출에 쓰이는 형상). */
export const CONFIGURED = tailwindConfig as { theme: { extend: Record<string, unknown> } };

/** 설정 파일이 내보낸 관제 extend 원본. */
export const CONTROL_THEME_EXTEND = controlThemeExtend as Record<string, unknown>;

/** 포털 투영 함수 — 가드가 설정과 같은 함수를 쓴다. */
export const PORTAL_THEME_EXTEND = portalThemeExtend as (c: unknown) => Record<string, unknown>;
