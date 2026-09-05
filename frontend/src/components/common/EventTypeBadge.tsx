import { useEventTypeLabels } from '@/features/eventType/hooks';
import { cn } from '@/lib/cn';
import { labelOf } from '@/lib/eventTypeLabel';

interface EventTypeBadgeProps {
  /**
   * 영상/작업의 **EV-코드**(상세 코드, 예 EV01000102) 또는 이미 해석된 한글 라벨(eventName)을 받는다.
   * 내부적으로 useEventTypeLabels() 의 **EV-코드→라벨 맵**으로 해석하므로,
   * 호출부는 반드시 EV-코드 또는 한글 라벨을 넘겨야 한다.
   *
   * - 코드(EV01000102 등) → EV-코드 맵으로 한글 카테고리명 변환 후 색상 매핑
   * - 한글 라벨(eventName) → 맵에 없으면 원문 그대로 색상 매핑
   *
   * ⚠️ **categoryKey(예 "020002")는 넘기지 말 것** — EV-코드 맵에 없어 원문이 그대로 노출된다.
   *    categoryKey 를 표시해야 하는 화면(프리셋 등)은 useEventTypes() 의
   *    categoryKey→label 맵으로 **선변환 후** 한글 라벨을 넘겨야 한다.
   */
  eventType: string;
  size?: 'sm' | 'md';
  className?: string;
}

// 카테고리 한글명 → 색상 (cosmetic). 미매핑 라벨은 회색 폴백 — 색상은 표시 보조용일 뿐.
//
// 범주 구분색 축이다 — 이벤트 유형은 서로 우열이 없는 대등한 분류라 semantic(성패·경고) 이 아니라
// DS-001 의 **범주 구분색 8슬롯**(`category-N`)에서 고른다. 세 범주 축(이벤트 유형·라벨 형태·역할)이
// 이 한 팔레트를 공유하며, 축마다 별도 색표를 만들지 않는다. 슬롯 번호는 우열·순서를 뜻하지 않는다.
//
// ⚠ semantic 대역(red/rose/amber/orange/emerald/green/yellow)을 이 표에 되돌리지 말 것 —
//   '폭력'이 red 면 배지가 **오류**로, '유괴'가 amber 면 **경고**로 읽힌다. 이벤트 유형은 그 축이
//   아니다. 구 표는 red 3 · amber 2 · rose 2 · orange 1 로 semantic 대역을 절반 가까이 쓰고 있었다.
// [@design DS-001]
const EVENT_COLORS: Record<string, string> = {
  교통사고: 'bg-category-1-100 text-category-1-700',
  쓰러짐: 'bg-category-2-100 text-category-2-700',
  침수: 'bg-category-3-100 text-category-3-700',
  '침수(범람)': 'bg-category-3-100 text-category-3-700',
  파손: 'bg-category-4-100 text-category-4-700',
  '이상행동(유괴)': 'bg-category-5-100 text-category-5-700',
  '납치(유괴)': 'bg-category-5-100 text-category-5-700',
  폭력: 'bg-category-6-100 text-category-6-700',
  싸움: 'bg-category-6-100 text-category-6-700',
  흉기소지: 'bg-category-6-100 text-category-6-700',
  산사태: 'bg-category-7-100 text-category-7-700',
  산불: 'bg-category-8-100 text-category-8-700',
  화재: 'bg-category-8-100 text-category-8-700',
};

// 배지 = DS-001 ladder `label` 축.
// ⚠ md 는 17px 로 **유지**한다 — ladder 에 "큰 label" step 이 없어서, `label`(14px)로 내리면
//   sm 과 크기가 같아져 변형이 무의미해진다. 크기를 보존하는 17px step(`body-md`)을 쓰고
//   판정은 보류한다(md 사용처는 VideoDetailPage 1곳뿐).
const SIZE_CLASSES = {
  sm: 'text-label px-2 py-0.5',
  md: 'text-body-md px-2.5 py-1',
} as const;

/**
 * 이벤트 색상 뱃지 — 라벨 맵(useEventTypeLabels) 기반 코드→한글 변환 후 색상 매핑.
 * 보안: 라벨은 텍스트 노드로만 렌더 (XSS 방지).
 *
 * ★ 태그 아이콘은 폐지했다(2026-08-10 확정) — 배지가 이벤트 **한글 라벨 텍스트**를 그대로
 *   보여주므로 색상 단독 구분 금지(KRDS) 요건은 텍스트가 단독으로 충족한다. 게다가 태그 아이콘은
 *   모든 이벤트에 동일해 카테고리를 구분해 주지도 않았다(순수 장식). ⚠ 되살리지 말 것.
 *
 * @design UI-016
 */
export function EventTypeBadge({ eventType, size = 'sm', className }: EventTypeBadgeProps) {
  const { data: labelMap } = useEventTypeLabels();
  // 빈 입력은 빈 뱃지로 유지 (labelOf 의 '-' 폴백 회피).
  const label = eventType ? labelOf(labelMap, eventType) : '';
  const colorClass = EVENT_COLORS[label] ?? 'bg-gray-100 text-gray-600';

  // 빈 입력은 빈 뱃지로 유지한다(위 labelOf 폴백 회피와 같은 취지).
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 font-medium rounded-full',
        colorClass,
        SIZE_CLASSES[size],
        className,
      )}
    >
      {label}
    </span>
  );
}

export default EventTypeBadge;
