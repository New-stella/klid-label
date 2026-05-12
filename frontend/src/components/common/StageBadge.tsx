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
  YOLO: 'YOLO',
  SAM2: 'SAM2',
  VLM_VERIFY: 'VLM',
  VLM: 'VLM',
};

const SIZE_CLASSES = {
  sm: 'text-xs px-2 py-0.5',
  md: 'text-sm px-2.5 py-1',
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
  if (status === 'COMPLETED' || status === 'DONE') return 'bg-green-100 text-green-700';
  if (status === 'FAILED' || status === 'FAIL') return 'bg-red-100 text-red-700';
  if (stage === 'VLM_VERIFY' || stage === 'VLM') return 'bg-purple-100 text-purple-700';
  if (status === 'IN_PROGRESS' || status === 'PROGRESS') return 'bg-blue-100 text-blue-700';
  return 'bg-yellow-100 text-yellow-700';
}

export function StageBadge({ stage, status, size = 'sm', className }: StageBadgeProps) {
  const label = STAGE_LABEL[stage] ?? stage;
  return (
    <span
      className={cn(
        'inline-flex items-center font-medium rounded-full',
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
