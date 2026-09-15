// [@design INT-013]
/**
 * 후처리기의 타입 선언 — 본체는 «순수 JS 여야 한다».
 *
 * `postcss.config.js` 는 Node 가 그대로 실행하므로 본체를 TS 로 쓸 수 없다. 그런데 회귀 가드
 * (`src/styles/__tests__/portalBaseLayerScoping.test.ts`)는 그 후처리기를 «직접 돌려» 두 채널을
 * 한자리에서 재야 한다 — 산출물을 두 번 빌드하지 않고 같은 입력으로 비교하려면 그 길뿐이다.
 * 그래서 선언만 여기 둔다(본체 사본이 아니다).
 */
import type { Plugin } from 'postcss';

export interface ScopePortalBaseLayerOptions {
  /** 주지 않으면 `VITE_BUILD_CHANNEL === 'portal'` 일 때만 동작한다. */
  enabled?: boolean;
}

export default function scopePortalBaseLayer(options?: ScopePortalBaseLayerOptions): Plugin;

/** 시험이 값을 대조하기 위해 노출하는 내부 — 운영 코드에서 쓰지 않는다. */
export const __testing: {
  ANCHOR_CLASS: string;
  SCOPE_SELF_AND_DESCENDANTS: string;
  SCOPE_SELF_ONLY: string;
  DOCUMENT_LEVEL_TREATMENT: Map<string, string | null>;
  scopeSelector: (selector: string, scope?: string) => string;
  normalizeSelectorKey: (selector: string) => string;
};
