// 프리셋 연결 상태 칩 — 이벤트유형 관리 목록의 '프리셋' 칸.
//
// ★ 이 컴포넌트는 <서버가 판정해 내려준 상태를 표기로 옮길 뿐> 프리셋 유무·실효 여부를 다시
//   판정하지 않는다. 그래서 props 가 `status` 하나다 — 프리셋 목록이나 라벨 매핑을 받지 않는
//   것이 계약이며, 받으면 그 순간 판정이 두 곳으로 갈린다.
//
// ★★ 「연결됨(무효)」와 「연결됨(제외)」는 <한눈에 갈라 보이게> 그린다(사양 SCREEN-038).
//    앞은 <사고>다 — 운영자는 저장 기준을 걸었다고 믿는데 담긴 라벨이 전부 AI 검출 클래스에
//    매핑돼 있지 않아 실제로는 걸리지 않았고, 그래서 그 유형의 영상은 오토라벨링이 보류된다.
//    뒤는 <사람이 일부러 뺀 선언>이라 보류가 아니다. 두 상태가 비슷해 보이면 사고를 의도로
//    오인해 지나친다 — 그래서 사고 축만 danger 톤이고 선언 축은 중립이다.
//
// 색상 대비(WCAG 실측):
//   연결됨 success-700/success-50 6.99:1 · 연결됨(무효) danger-700/danger-50 8.01:1 ·
//   연결됨(제외) gray-800/gray-100 9.85:1 · 미연결 warning-700/warning-50 8.43:1 — 전부 AA 이상.
//   `label` 텍스트가 곧 뜻이라 색상 단독으로 의미를 전달하지 않는다.
//
// @design SCREEN-038, API-185

import { cn } from '@/lib/cn';

import type { PresetLinkStatus } from '../presetLinkStatus';

interface ChipStyle {
  label: string;
  className: string;
}

/**
 * 표기 문구는 사양 SCREEN-038 확정값이다 — 임의로 다듬지 말 것.
 *
 * 「연결됨(무효)」를 '경고'·'미적용' 같은 말로 바꾸면 그 옆의 「연결됨(제외)」와의 대비가 흐려진다.
 */
const STYLE: Record<PresetLinkStatus, ChipStyle> = {
  LINKED: { label: '연결됨', className: 'bg-success-50 text-success-700' },
  // 사고 축 — 오토라벨이 보류된다.
  LINKED_INEFFECTIVE: { label: '연결됨(무효)', className: 'bg-danger-50 text-danger-700' },
  // 선언 축 — 보류가 아니라 사람이 뺀 것이라 중립 톤이다.
  LINKED_EXCLUDED: { label: '연결됨(제외)', className: 'bg-gray-100 text-gray-800' },
  UNLINKED: { label: '미연결', className: 'bg-warning-50 text-warning-700' },
};

export interface PresetLinkStatusChipProps {
  /** 서버가 내려준 연결 상태(`presetLinkStatus`). */
  status: PresetLinkStatus;
  className?: string;
}

export function PresetLinkStatusChip({ status, className }: PresetLinkStatusChipProps) {
  const style = STYLE[status];

  return (
    <span
      data-preset-link-status={status}
      className={cn(
        'inline-flex shrink-0 items-center rounded px-2 py-0.5 text-label font-medium',
        style.className,
        className,
      )}
    >
      {style.label}
    </span>
  );
}

export default PresetLinkStatusChip;
