import { cn } from '@/lib/cn';
import type { MarkingMode } from '../types';

interface MarkingToolbarProps {
  mode: MarkingMode;
  intervalFrames: number;
  onModeChange: (mode: MarkingMode) => void;
  onIntervalFramesChange: (frames: number) => void;
  onSubmit: () => void;
  onClear: () => void;
  submitting?: boolean;
  markCount: number;
  className?: string;
}

export function MarkingToolbar({
  mode,
  intervalFrames,
  onModeChange,
  onIntervalFramesChange,
  onSubmit,
  onClear,
  submitting,
  markCount,
  className,
}: MarkingToolbarProps) {
  // 이벤트명은 영상의 evntTypeCd 에서 자동 소싱되므로 입력 가드가 없다.
  // 완료 버튼 비활성화 기준: 수동=마크 0건, 자동=intervalFrames 무효(1 미만).
  const disabled =
    submitting || (mode === 'MANUAL' ? markCount === 0 : intervalFrames < 1);
  return (
    <div className={cn('flex flex-wrap items-center gap-3 p-3 bg-white border rounded-lg', className)}>
      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={() => onModeChange('AUTO')}
          className={cn(
            'px-3 py-1.5 rounded text-sm font-medium transition-colors',
            mode === 'AUTO' ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-700 hover:bg-gray-200',
          )}
        >
          자동
        </button>
        <button
          type="button"
          onClick={() => onModeChange('MANUAL')}
          className={cn(
            'px-3 py-1.5 rounded text-sm font-medium transition-colors',
            mode === 'MANUAL' ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-700 hover:bg-gray-200',
          )}
        >
          수동
        </button>
      </div>

      {mode === 'AUTO' && (
        <label className="flex items-center gap-1 text-sm text-gray-600">
          간격(프레임)
          <input
            type="number"
            min={1}
            max={3600}
            value={intervalFrames}
            onChange={(e) => onIntervalFramesChange(parseInt(e.target.value, 10) || 1)}
            className="w-16 rounded border px-2 py-1.5 text-sm"
          />
        </label>
      )}

      {mode === 'MANUAL' && (
        <span className="text-sm text-gray-500">
          Space: 마킹 | Del: 삭제 | Enter: 완료
        </span>
      )}

      <div className="ml-auto flex items-center gap-2">
        <span className="text-sm text-gray-500">{markCount}건</span>
        <button
          type="button"
          onClick={onClear}
          className="rounded px-3 py-1.5 text-sm bg-gray-100 hover:bg-gray-200 text-gray-700"
        >
          초기화
        </button>
        <button
          type="button"
          onClick={onSubmit}
          disabled={disabled}
          className={cn(
            'rounded px-4 py-1.5 text-sm font-medium text-white transition-colors',
            disabled
              ? 'bg-gray-400 cursor-not-allowed'
              : 'bg-blue-600 hover:bg-blue-700',
          )}
        >
          {submitting ? '저장 중...' : '마킹 완료'}
        </button>
      </div>
    </div>
  );
}
