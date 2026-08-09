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
  //
  // ★완료 버튼은 **마크 건수로 비활성화하지 않는다** (확정 사양). 수동 모드에서 마크 0건일 때
  //   버튼을 죽이면 "왜 눌리지 않는가" 를 화면이 말해 주지 못한다 — 사양은 **누를 수 있게 두고
  //   눌렀을 때 안내(토스트)로 사유를 말하며 제출만 막는** 것이다(안내 발화는 호출부가 담당).
  //   자동 모드는 개별 마크를 쌓지 않으므로 애초에 이 검사 대상이 아니다.
  // ⚠ 저장 중(submitting) 비활성은 **유지**한다 — 중복 제출 차단은 별개 축이라 함께 걷어내면
  //   in-flight 중 재클릭으로 마킹 POST 가 중복 발화한다.
  const disabled = submitting || (mode === 'AUTO' && intervalFrames < 1);
  return (
    <div className={cn('flex flex-wrap items-center gap-3 p-3 bg-white border rounded-lg', className)}>
      <div className="flex items-center gap-1">
        <button
          type="button"
          onClick={() => onModeChange('AUTO')}
          className={cn(
            'px-3 py-1.5 rounded text-body-md font-medium transition-colors',
            mode === 'AUTO' ? 'bg-primary-600 text-white' : 'bg-gray-100 text-gray-700 hover:bg-gray-200',
          )}
        >
          자동
        </button>
        <button
          type="button"
          onClick={() => onModeChange('MANUAL')}
          className={cn(
            'px-3 py-1.5 rounded text-body-md font-medium transition-colors',
            mode === 'MANUAL' ? 'bg-primary-600 text-white' : 'bg-gray-100 text-gray-700 hover:bg-gray-200',
          )}
        >
          수동
        </button>
      </div>

      {mode === 'AUTO' && (
        <label className="flex items-center gap-1 text-label text-gray-600">
          간격(프레임)
          <input
            type="number"
            // 사양(SCREEN-006): 1 이상 정수, 상한 없음 — 서버가 백스톱한다. 클라이언트
            // 상한을 두면 그 값이 두 번째 진실원이 되어 서버 상한과 어긋날 수 있다
            // (실사고: 자동 마킹 intervalFrames=300 이 목서버의 남은 FPS 상한 오해로 422 났던 사례).
            min={1}
            value={intervalFrames}
            onChange={(e) => onIntervalFramesChange(parseInt(e.target.value, 10) || 1)}
            className="w-16 rounded border px-2 py-1.5 text-body-md"
          />
        </label>
      )}

      {mode === 'MANUAL' && (
        <span className="text-body-md text-gray-500">
          Space: 마킹 | Del: 삭제 | Enter: 완료
        </span>
      )}

      <div className="ml-auto flex items-center gap-2">
        <span className="text-body-md text-gray-500">{markCount}건</span>
        <button
          type="button"
          onClick={onClear}
          className="rounded px-3 py-1.5 text-button bg-gray-100 hover:bg-gray-200 text-gray-700"
        >
          초기화
        </button>
        <button
          type="button"
          onClick={onSubmit}
          disabled={disabled}
          className={cn(
            'rounded px-4 py-1.5 text-button font-medium text-white transition-colors',
            disabled
              ? 'bg-gray-400 cursor-not-allowed'
              : 'bg-primary-600 hover:bg-primary-700',
          )}
        >
          {submitting ? '저장 중...' : '마킹 완료'}
        </button>
      </div>
    </div>
  );
}
