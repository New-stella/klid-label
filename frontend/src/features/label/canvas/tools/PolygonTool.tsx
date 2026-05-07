// Polygon 도구. OverlayLayer가 그리기 로직 보유, 본 모듈은 표시용.

import { Pentagon } from 'lucide-react';

export function PolygonTool() {
  return <Pentagon aria-label="Polygon 도구" size={18} />;
}

export const POLYGON_TOOL_NAME = 'Polygon' as const;
