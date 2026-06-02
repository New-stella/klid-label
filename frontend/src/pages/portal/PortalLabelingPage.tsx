// SCR-PORTAL-002 — 포털 간편 라벨링 페이지 (V1.5).
// LabelingPage를 재사용 (portalMode는 useAuthStore.channel='PORTAL'에서 자동 분기).
// 포털 채널은 [히스토리]·[검수제출]·VLM/메타 탭 미노출 (LabelingPage 내부 분기).
// ADR-013: 포털 오토라벨링 미제공 — 데이터마트 영상 선택·간편 라벨링·저장 흐름만 유지.

import { LabelingPage } from '@/pages/label/LabelingPage';

export function PortalLabelingPage() {
  return <LabelingPage />;
}
