import { cn } from '@/lib/cn';

export interface SideBySideCompareProps {
  leftImage: string;
  rightImage: string;
  leftLabel: string;
  rightLabel: string;
  /** 우측 이미지 누락 시 placeholder 텍스트 — 비식별 실패 등 */
  rightMissingText?: string;
  className?: string;
}

/**
 * V1.6: 50:50 좌우 비교 — 슬라이더 오버레이 X.
 * SCR-DEIDENT-002, SCR-AUG-001 등에서 사용.
 *
 * 보안: src URL은 호출자가 BE 응답값으로 전달하는 전제. FE에서는 단순 렌더만.
 */
export function SideBySideCompare({
  leftImage,
  rightImage,
  leftLabel,
  rightLabel,
  rightMissingText,
  className,
}: SideBySideCompareProps) {
  const rightMissing = !rightImage;
  return (
    <div
      data-testid="side-by-side-compare"
      aria-label="좌우 비교"
      className={cn('grid grid-cols-2 gap-2', className)}
    >
      <figure className="flex flex-col gap-1">
        <figcaption className="text-sub font-medium text-primary">{leftLabel}</figcaption>
        <img
          src={leftImage}
          alt={leftLabel}
          width={640}
          height={360}
          loading="lazy"
          data-testid="side-by-side-left"
          className="h-auto w-full rounded border border-border object-contain"
        />
      </figure>
      <figure className="flex flex-col gap-1">
        <figcaption className="text-sub font-medium text-primary">{rightLabel}</figcaption>
        {rightMissing ? (
          <div
            data-testid="side-by-side-right-missing"
            className="flex h-48 w-full items-center justify-center rounded border border-dashed border-border bg-bgLight text-body text-neutral"
          >
            {rightMissingText ?? `${rightLabel} 이미지 없음`}
          </div>
        ) : (
          <img
            src={rightImage}
            alt={rightLabel}
            width={640}
            height={360}
            loading="lazy"
            data-testid="side-by-side-right"
            className="h-auto w-full rounded border border-border object-contain"
          />
        )}
      </figure>
    </div>
  );
}
