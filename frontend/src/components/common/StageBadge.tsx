import { cn } from '@/lib/cn';
import { hasStageLabel, stageLabel } from './BatchStageIndicator';

export interface StageBadgeProps {
  stage: string;
  status?: string;
  size?: 'sm' | 'md';
  className?: string;
}

/**
 * **배지 축에만 존재하는 의사(pseudo) 단계**의 표시명.
 *
 * 배치 단계가 아니라 배지가 상태를 한 칸으로 요약할 때 쓰는 값이라 단계 표에 넣지 않는다:
 * - `COMPLETED` — 모두 DONE 인 영상을 한 배지로 요약(V1.x 후속)
 * - `FAILED` — 실패로 멈춘 영상 요약
 * - `VLM_VERIFY` — 시계열 위탁 검증. 이름을 손으로 적지 않고 **단계 표에서 파생**한다
 *   (`BUNDLE_LABEL.VLM` 과 같은 기법 — 같은 이름이 두 곳에 생기면 한쪽만 갱신된다).
 *
 * ⚠ 공유 단계 코드(비식별·마킹·시계열·프레임추출·AI 탐지·AI 분할·보간)는 여기 적지 않는다.
 * 그 이름의 정의처는 `BatchStageIndicator` 의 단계 표 하나뿐이다 — 이 파일이 자기 표를 갖고
 * 있던 동안 `DEIDENTIFY` 가 '비식별화'(여기) / '비식별'(단계 표) 두 이름으로 갈려 있었다.
 */
const BADGE_ONLY_LABEL: Record<string, string> = {
  COMPLETED: '완료',
  FAILED: '실패',
  VLM_VERIFY: stageLabel('VLM'),
};

/**
 * 이 표가 이름을 규정하는 코드 전체(**키만** — 표 자체는 내보내지 않는다).
 *
 * ★ 회귀 가드가 `STAGE_LABEL_CODES` 와의 **교집합이 공집합인지**를 단언하는 데 쓴다.
 *   즉 "배지 전용 표는 공유 표의 단계를 재정의하지 않는다"는 불변식을 직접 검사한다.
 *
 * ⚠ 값 비교 가드만으로는 부족하다 — 공유 표와 **같은 문자열**로 재복제하면 렌더 결과가 같아
 *   전부 통과하면서 표는 다시 둘이 된다(그 상태가 이 파일의 원래 결함이었고, 어긋난 것은
 *   나중에 한쪽만 갱신됐을 때다). 그래서 값이 아니라 **키의 존재 자체**를 금지한다.
 */
export const BADGE_ONLY_STAGE_CODES: readonly string[] = Object.keys(BADGE_ONLY_LABEL);

/**
 * 배지 라벨 판정. 의사 단계 → 공유 단계 표 → **원문 코드** 순으로 떨어진다.
 *
 * ⚠ 마지막 폴백이 원문 코드인 것은 UI-017 사양이다(빈 배지 방지). 스테퍼는 같은 상황에서
 * `처리중`으로 덮는데(UI-018), **두 폴백이 다른 것이 사양**이므로 통일하지 말 것.
 */
export function stageBadgeLabel(stage: string): string {
  return BADGE_ONLY_LABEL[stage] ?? (hasStageLabel(stage) ? stageLabel(stage) : stage);
}

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
 *   ⚠ 되살리지 말 것. 톤 결정 로직·라벨 판정·원문 코드 폴백은 그대로 유지한다.
 *
 * @design UI-017
 */
export function StageBadge({ stage, status, size = 'sm', className }: StageBadgeProps) {
  const label = stageBadgeLabel(stage);
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
