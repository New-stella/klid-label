import { Button } from '@/components/common/Button';
import { KRDS_ICON_HIT_AREA } from '@/lib/focusRing';

interface FrameNavigatorProps {
  currentIndex: number;
  total: number;
  onPrev: () => void;
  onNext: () => void;
  /** 프레임 전환 차단(장시간 작업 진행 중) — 이전/다음 버튼을 비활성화한다. */
  disabled?: boolean;
}

/**
 * 프레임 N/M 표시 + 이전/다음 버튼.
 * 이전/다음은 단독 icon-only 네비 버튼이므로 KRDS 최소 터치 타깃 44x44px 보장.
 */
export function FrameNavigator({
  currentIndex,
  total,
  onPrev,
  onNext,
  disabled = false,
}: FrameNavigatorProps) {
  const display = total === 0 ? '0/0' : `${currentIndex + 1}/${total}`;
  return (
    <div className="flex items-center gap-2" role="group" aria-label="프레임 이동">
      <Button
        variant="ghost"
        size="sm"
        className={`${KRDS_ICON_HIT_AREA} p-0`}
        onClick={onPrev}
        disabled={disabled}
        aria-label="이전 프레임"
      >
        ◀
      </Button>
      <span className="text-sub text-primary" data-testid="frame-counter">
        프레임 {display}
      </span>
      <Button
        variant="ghost"
        size="sm"
        className={`${KRDS_ICON_HIT_AREA} p-0`}
        onClick={onNext}
        disabled={disabled}
        aria-label="다음 프레임"
      >
        ▶
      </Button>
    </div>
  );
}
