import { Check, Loader2, X } from 'lucide-react';

import { cn } from '@/lib/cn';
import type { BatchStageItem, BatchStageStatus } from '@/features/video/types';

export type { BatchStageItem, BatchStageStatus } from '@/features/video/types';

interface BatchStageIndicatorProps {
  stages: BatchStageItem[];
}

// BE canonical 단계 코드(BatchStage.name) → 사용자 한글 라벨.
// ⚠ 기술 모델명(YOLO/SAM2) 화면 노출 금지 → "AI 탐지"/"AI 분할" (코드/name 은 유지).
// BE 가 stages 배열의 순서/상태를 canonical 로 내려주므로 FE 는 순서를 가정하지 않고
// 배열을 그대로 렌더하며 name→라벨 매핑만 한다.
const STAGE_LABEL: Record<string, string> = {
  DEIDENTIFY: '비식별',
  MARKING: '마킹',
  VLM: 'VLM',
  FRAME_EXTRACT: '프레임추출',
  YOLO: 'AI 탐지',
  SAM2: 'AI 분할',
  INTERPOLATE: '보간',
};

// 매핑에 없는 단계 코드(BE 가 향후 단계 추가 시)는 기술 코드명이 화면에 새지 않도록
// 한글 기본값으로 폴백한다(기술 코드명 노출 금지).
const STAGE_LABEL_FALLBACK = '처리중';

function StageIcon({ status }: { status: BatchStageStatus }) {
  if (status === 'DONE') {
    return (
      <div className="w-8 h-8 rounded-full bg-success flex items-center justify-center">
        <Check size={14} className="text-white" aria-hidden />
      </div>
    );
  }
  if (status === 'PROGRESS') {
    return (
      <div className="w-8 h-8 rounded-full bg-info flex items-center justify-center">
        <Loader2 size={14} className="text-white animate-spin" aria-hidden />
      </div>
    );
  }
  if (status === 'FAIL') {
    return (
      <div className="w-8 h-8 rounded-full bg-danger flex items-center justify-center">
        <X size={14} className="text-white" aria-hidden />
      </div>
    );
  }
  return (
    <div className="w-8 h-8 rounded-full bg-gray-200 border-2 border-gray-300 flex items-center justify-center">
      <div className="w-2 h-2 rounded-full bg-gray-400" />
    </div>
  );
}

function connectorColor(status: BatchStageStatus): string {
  if (status === 'DONE') return 'bg-success';
  return 'bg-gray-200';
}

export function BatchStageIndicator({ stages }: BatchStageIndicatorProps) {
  // BE stages 가 비면(배치 로그 없는 기존 영상) 아무것도 렌더 안 함 → 상위 배지 폴백(하위호환).
  if (!stages || stages.length === 0) return null;

  return (
    <div className="flex items-center gap-0" data-testid="batch-stage-indicator">
      {stages.map((stage, idx) => {
        const isLast = idx === stages.length - 1;

        return (
          <div key={stage.name} className="flex items-center">
            <div className="flex flex-col items-center gap-1">
              <StageIcon status={stage.status} />
              <span
                // 단계명 캡션 — ladder `caption`(14px). 크기는 구 `text-xs` 와 동일.
                // ⚠ 바로 아래 인라인 `fontSize: '10px'` 이 최종적으로 이긴다(ladder 밖 값).
                className="text-caption text-gray-500 text-center whitespace-nowrap"
                style={{ fontSize: '10px' }}
              >
                {STAGE_LABEL[stage.name] ?? STAGE_LABEL_FALLBACK}
              </span>
            </div>
            {!isLast && (
              <div className={cn('flex-1 h-0.5 w-6 mx-1 mb-4', connectorColor(stage.status))} />
            )}
          </div>
        );
      })}
    </div>
  );
}

export default BatchStageIndicator;
