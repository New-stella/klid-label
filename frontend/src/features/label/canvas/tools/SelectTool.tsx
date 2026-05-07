// Select 도구. OverlayLayer가 활성 도구가 아니면 라벨 클릭 → selectLabel 동작.

import { MousePointer2 } from 'lucide-react';

export function SelectTool() {
  return <MousePointer2 aria-label="선택 도구" size={18} />;
}

export const SELECT_TOOL_NAME = 'Select' as const;
