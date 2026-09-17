// [@design INT-013]
/**
 * 후처리기의 타입 선언 — 본체는 «순수 JS 여야 한다».
 *
 * `postcss.config.js` 는 Node 가 그대로 실행하므로 본체를 TS 로 쓸 수 없다. 그런데 회귀 가드
 * (`src/styles/__tests__/portalBaseLayerScoping.test.ts`)는 그 후처리기를 «직접 돌려» 두 채널을
 * 한자리에서 재야 한다 — 산출물을 두 번 빌드하지 않고 같은 입력으로 비교하려면 그 길뿐이다.
 * 그래서 선언만 여기 둔다(본체 사본이 아니다).
 */
import type { Plugin, Root } from 'postcss';

export interface ScopePortalBaseLayerOptions {
  /** 주지 않으면 `VITE_BUILD_CHANNEL === 'portal'` 일 때만 동작한다. */
  enabled?: boolean;
}

export default function scopePortalBaseLayer(options?: ScopePortalBaseLayerOptions): Plugin;

/** 포털 모습 CSS 한 벌의 모든 규칙을 앵커 안으로 좁힌다(축 ②). */
export function scopePortalLookRoot(root: Root): void;

/** 시험이 값을 대조하기 위해 노출하는 내부 — 운영 코드에서 쓰지 않는다. */
export const __testing: {
  ANCHOR_CLASS: string;
  SCOPE_SELF_AND_DESCENDANTS: string;
  SCOPE_SELF_ONLY: string;
  DOCUMENT_LEVEL_TREATMENT: Map<string, string | null>;
  scopeSelector: (selector: string, scope?: string) => string;
  scopePortalLookRoot: (root: Root) => void;
  normalizeSelectorKey: (selector: string) => string;
  /** 「포털 모습 CSS 인가」를 가르는 파일 경로 정규식(축 ②). */
  PORTAL_LOOK_PATH: RegExp;
  /** 선택자 → 그 선택자에서 문서 루트에 남길 속성 집합. */
  ROOT_GLOBAL_DECLS: Map<string, Set<string>>;
  /** 앵커 «자신»으로 갈아끼우는 문서 수준 선택자. */
  DOCUMENT_LEVEL_SELF: Set<string>;
};
