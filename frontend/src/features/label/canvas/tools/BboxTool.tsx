// BBox 도구. 실제 그리기 로직은 OverlayLayer에 통합되어 있고,
// 이 컴포넌트는 도구 활성 시 ToolBar 등에서 import해 표시 보조용.

import { Square } from 'lucide-react';

export function BboxTool() {
  return <Square aria-label="Bbox 도구" size={18} />;
}

export const BBOX_TOOL_NAME = 'BBox' as const;
