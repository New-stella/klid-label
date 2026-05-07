// Pan 도구. OverlayLayer가 활성 시 useLabelStore.setPan으로 이동.

import { Hand } from 'lucide-react';

export function PanTool() {
  return <Hand aria-label="팬 도구" size={18} />;
}

export const PAN_TOOL_NAME = 'Pan' as const;
