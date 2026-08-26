import { cn } from '@/lib/cn';

export type BadgeStatus =
  | 'BATCH_PROCESSING'
  | 'BATCH_COMPLETED'
  | 'BATCH_FAILED'
  | 'PENDING'
  | 'MARKING_READY'
  | 'IN_PROGRESS'
  | 'PROCESSING'
  | 'REVIEW_PENDING'
  | 'REVIEWING'
  | 'IN_REVIEW'
  | 'COMPLETED'
  | 'APPROVED'
  | 'REJECTED'
  | 'FAILED'
  | 'DEIDENT_IN_PROGRESS'
  | 'DEIDENT_FAILED'
  // Phase 7b — 검수 승인 이후 라벨/메타 수정으로 재검토가 필요한 영상(V177 REVLT_YN).
  // 워크플로 상태(COMPLETED 등)와 **나란히** 표시하는 보조 배지 — 대체가 아니다.
  | 'NEEDS_RECHECK';

export interface StatusBadgeProps {
  status: BadgeStatus | string;
  label?: string;
  className?: string;
}

interface StatusConfig {
  label: string;
  className: string;
}

// ★ 상태 아이콘은 폐지했다(2026-08-10 확정) — 배지가 이미 **한글 라벨 텍스트**를 항상 가지므로
//   색상 단독 구분 금지(KRDS) 요건은 텍스트가 단독으로 충족한다. 아이콘은 그 위에 얹힌 장식이었다.
//   ⚠ 되살리지 말 것. 색상 톤·라벨·`data-status` 는 그대로 유지한다.
//
// UI/UX §3.4 9종 상태 — mock modern blue tone (soft tonal pill) + BE alias
// @design UI-014
// ⚠ 2026-08-08: info/success/warning/danger 텍스트는 DEFAULT 가 아니라 각 스케일의 700
//   단계를 쓴다 — DS-001 정본 값으로 교체하며 bg-{color}/10 위 텍스트 대비가 3.9~4.1:1 로
//   AA(4.5:1) 미달이 됐다(WCAG 상대휘도 실측). {color}-700 은 같은 스케일의 이미 정의된
//   단계라 새 색을 만들지 않고도 6.8~8.2:1 대로 회복한다. 배경(bg-{color}/10)은 그대로
//   DEFAULT 톤을 쓴다.
const statusConfig: Record<string, StatusConfig> = {
  BATCH_PROCESSING: { label: '배치 처리중', className: 'bg-info/10 text-info-700' },
  BATCH_COMPLETED: { label: '배치 완료', className: 'bg-success/10 text-success-700' },
  BATCH_FAILED: { label: '배치 실패', className: 'bg-danger/10 text-danger-700' },
  PENDING: { label: '대기', className: 'bg-gray-100 text-gray-600' },
  MARKING_READY: { label: '마킹 대기', className: 'bg-info/10 text-info-700' },
  IN_PROGRESS: { label: '작업중', className: 'bg-info/10 text-info-700' },
  PROCESSING: { label: '처리중', className: 'bg-info/10 text-info-700' },
  REVIEW_PENDING: { label: '검수요청', className: 'bg-warning/10 text-warning-700' },
  // '검수중'은 범주가 아니라 **상태**다 — DS-001 의 warn 이 "검토 중, 주의가 필요한 상태"를
  // 소유하므로 warning 을 쓴다. 구 purple 은 semantic 팔레트에 마땅한 자리가 없어 범주 구분색을
  // 빌려 쓴 것이었고, 그래서 상태 배지 하나만 범주 축 색으로 튀어 있었다.
  // ⚠ REVIEW_PENDING('검수요청')과 같은 색이 되는 것은 의도다 — 이 배지는 항상 한글 라벨을
  //   함께 보여주므로 색상 단독 구분 금지(KRDS) 요건을 텍스트가 단독으로 충족한다. 두 상태를
  //   가르려고 새 색을 만들지 말 것.
  REVIEWING: { label: '검수중', className: 'bg-warning/10 text-warning-700' },
  IN_REVIEW: { label: '검수중', className: 'bg-warning/10 text-warning-700' },
  COMPLETED: { label: '완료', className: 'bg-success/10 text-success-700' },
  APPROVED: { label: '승인', className: 'bg-success/10 text-success-700' },
  REJECTED: { label: '반려', className: 'bg-danger/10 text-danger-700' },
  FAILED: { label: '실패', className: 'bg-danger/10 text-danger-700' },
  // Phase 3 — 비식별 처리 상태 (deidentStatus 기반). 진행중=정보(파랑), 실패=경고(빨강).
  DEIDENT_IN_PROGRESS: { label: '비식별 진행중', className: 'bg-info/10 text-info-700' },
  DEIDENT_FAILED: { label: '비식별 실패', className: 'bg-danger/10 text-danger-700' },
  // Phase 7b — REVIEW_PENDING(검수요청)과 같은 warning 톤을 재사용(신규 색 미도입).
  NEEDS_RECHECK: { label: '재검토 필요', className: 'bg-warning/10 text-warning-700' },
};

// 매핑에 없는 코드(BE alias 등)는 회색 톤으로 폴백하고 **라벨은 status 원문**을 그대로 보여준다.
// 그래서 폴백 설정에는 라벨을 넣지 않는다(넣으면 원문이 가려진다).
const FALLBACK: Omit<StatusConfig, 'label'> = {
  className: 'bg-gray-100 text-gray-600',
};

export function StatusBadge({ status, label, className }: StatusBadgeProps) {
  const cfg = statusConfig[status];
  const tone = cfg ?? FALLBACK;
  // ⚠ `??` 로 이으면 안 된다 — 빈 문자열은 null/undefined 가 아니라 그대로 통과해
  //   매핑 밖 상태에서 **텍스트가 없는 빈 배지**가 된다(구 FALLBACK.label='' 결함).
  //   아이콘이 폐지된 지금은 텍스트가 유일한 정보 전달 축이라 더 중요하다.
  const text = label || cfg?.label || status;
  return (
    <span
      data-status={status}
      className={cn(
        'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-sub font-medium',
        tone.className,
        className,
      )}
    >
      {text}
    </span>
  );
}
