import { Check, Loader2, X } from 'lucide-react';
import type { VideoDto, BatchStage, StageStatus } from '../../api/types';
import { stageLabel } from '../../utils/format';

interface BatchStageIndicatorProps {
  stages: VideoDto['stages'];
}

const STAGE_ORDER: BatchStage[] = ['FRAME_EXTRACT', 'DEIDENTIFY', 'YOLO', 'SAM2', 'VLM'];

function StageIcon({ status }: { status: StageStatus }): JSX.Element {
  if (status === 'DONE') {
    return (
      <div className="w-8 h-8 rounded-full bg-green-500 flex items-center justify-center">
        <Check size={14} className="text-white" />
      </div>
    );
  }
  if (status === 'PROGRESS') {
    return (
      <div className="w-8 h-8 rounded-full bg-blue-500 flex items-center justify-center">
        <Loader2 size={14} className="text-white animate-spin" />
      </div>
    );
  }
  if (status === 'FAIL') {
    return (
      <div className="w-8 h-8 rounded-full bg-red-500 flex items-center justify-center">
        <X size={14} className="text-white" />
      </div>
    );
  }
  // PENDING
  return (
    <div className="w-8 h-8 rounded-full bg-gray-200 border-2 border-gray-300 flex items-center justify-center">
      <div className="w-2 h-2 rounded-full bg-gray-400" />
    </div>
  );
}

function connectorColor(status: StageStatus): string {
  if (status === 'DONE') return 'bg-green-400';
  return 'bg-gray-200';
}

export function BatchStageIndicator({ stages }: BatchStageIndicatorProps): JSX.Element {
  // Map by name for quick lookup
  const stageMap = new Map(stages.map((s) => [s.name, s]));

  return (
    <div className="flex items-center gap-0">
      {STAGE_ORDER.map((stageName, idx) => {
        const stage = stageMap.get(stageName) ?? { name: stageName, status: 'PENDING' as StageStatus, progress: 0 };
        const isLast = idx === STAGE_ORDER.length - 1;

        return (
          <div key={stageName} className="flex items-center">
            <div className="flex flex-col items-center gap-1">
              <StageIcon status={stage.status} />
              <span className="text-xs text-gray-500 text-center whitespace-nowrap" style={{ fontSize: '10px' }}>
                {stageLabel(stageName)}
              </span>
            </div>
            {!isLast && (
              <div
                className={['flex-1 h-0.5 w-6 mx-1 mb-4', connectorColor(stage.status)].join(' ')}
              />
            )}
          </div>
        );
      })}
    </div>
  );
}

export default BatchStageIndicator;
