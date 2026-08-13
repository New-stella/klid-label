import { cn } from '@/lib/cn';

export interface StageBadgeProps {
  stage: string;
  status?: string;
  size?: 'sm' | 'md';
  className?: string;
}

// mock 정합 — stage(이름)별 라벨
// V1.x 후속: 모두 DONE인 영상은 'COMPLETED' stage로 전달 → '완료' 라벨(초록 톤)
const STAGE_LABEL: Record<string, string> = {
  COMPLETED: '완료',
  FAILED: '실패',
  FRAME_EXTRACT: '프레임추출',
  DEIDENTIFY: '비식별화',
  YOLO: 'AI 탐지',
  SAM2: 'AI 분할',
  VLM_VERIFY: 'VLM',
  VLM: 'VLM',
};

// 배지 = DS-001 ladder `label` 축. md 17px 유지 근거는 EventTypeBadge 주석과 동일.
const SIZE_CLASSES = {
  sm: 'text-label px-2 py-0.5',
  md: 'text-body-md px-2.5 py-1',
} as const;

/**
 * mock 정합 — stage 이름 + status 조합으로 톤 결정.
 * - 완료(COMPLETED) → 초록
 * - 실패(FAILED) → 빨강
 * - VLM(특수 단계) → 보라
 * - 진행 중(IN_PROGRESS) → 파랑
 * - 대기 등 그 외 → 노랑(YOLO/SAM2/프레임추출 등 진행 단계 강조용)
 */
function toneClasses(stage: string, status?: string): string {
  // ⚠ 2026-08-08: text-{color}-700 사용 이유는 StatusBadge.tsx 상단 주석 참조(AA 대비 회복).
  if (status === 'COMPLETED' || status === 'DONE') return 'bg-success/10 text-success-700';
  if (status === 'FAILED' || status === 'FAIL') return 'bg-danger/10 text-danger-700';
  // KRDS 예외: VLM 단계 purple 은 범주 구분색(특수 단계 강조, 상태 의미 아님) — 토큰 획일화 제외.
  if (stage === 'VLM_VERIFY' || stage === 'VLM') return 'bg-purple-100 text-purple-700';
  if (status === 'IN_PROGRESS' || status === 'PROGRESS') return 'bg-info/10 text-info-700';
  return 'bg-warning/10 text-warning-700';
}

/**
 * ★ 단계 아이콘은 폐지했다(2026-08-10 확정) — 배지가 항상 **한글 단계 라벨**을 함께 보여주므로
 *   색상 단독 구분 금지(KRDS) 요건은 텍스트가 단독으로 충족한다. 아이콘은 그 위의 장식이었다.
 *   ⚠ 되살리지 말 것. 톤 결정 로직·라벨 매핑·`STAGE_LABEL` 폴백은 그대로 유지한다.
 *
 * @design UI-017
 */
export function StageBadge({ stage, status, size = 'sm', className }: StageBadgeProps) {
  const label = STAGE_LABEL[stage] ?? stage;
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 font-medium rounded-full',
        toneClasses(stage, status),
        SIZE_CLASSES[size],
        className,
      )}
    >
      {label}
    </span>
  );
}

export default StageBadge;
