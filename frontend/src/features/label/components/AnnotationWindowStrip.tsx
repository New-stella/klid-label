// 창이 숨거나 접혀 있는 동안 화면 위쪽에 떠서 창 자리를 대신하는 띠. [@design UI-158]
//
// 창 대신 띠만 남기는 이유는 <b>뒤 화면을 가리는 면적</b> 때문이다 — 근거로 쓸 프레임·객체를
// 고르는 동안에는 캔버스가 보여야 하고, 근거로 적힌 프레임을 확인하는 동안에도 마찬가지다.
// 어느 형태에서든 창 안에서 입력하던 값은 그대로 유지된다(창은 언마운트되지 않는다).

import { Button } from '@/components/common/Button';

import { STRIP } from './annotationWording';

export type AnnotationWindowStripVariant = 'pick' | 'evidenceJump' | 'folded';

export interface AnnotationWindowStripProps {
  variant: AnnotationWindowStripVariant;
  /** 근거 후보 번호(저장 키의 숫자) — pick·evidenceJump 에서 쓴다. */
  evidenceNo?: number | null;
  /** 지금 프레임 순번(1-base). */
  frameIndex?: number;
  /** 영상의 프레임 수. */
  frameTotal?: number;
  /** 화면에서 고른 객체의 분류 이름과 번호. */
  selectedObject?: { label: string; id: string } | null;
  /** 이번 지정에서 담은 프레임·객체 수. */
  pickedCounts?: { frames: number; objects: number };
  /** 저장 안 된 변경이 있는가 — 접힘 띠에 병기한다. */
  dirty?: boolean;
  onCaptureFrame?: () => void;
  onCaptureObject?: () => void;
  onCancel?: () => void;
  onComplete?: () => void;
  onExpand?: () => void;
}

const SHELL_CLASS =
  'fixed left-1/2 top-16 z-50 w-[min(1120px,calc(100vw-4rem))] -translate-x-1/2 rounded-lg border border-primary-500 bg-white p-3 shadow-lg';

export function AnnotationWindowStrip({
  variant,
  evidenceNo,
  frameIndex,
  frameTotal,
  selectedObject,
  pickedCounts,
  dirty = false,
  onCaptureFrame,
  onCaptureObject,
  onCancel,
  onComplete,
  onExpand,
}: AnnotationWindowStripProps) {
  if (variant === 'pick') {
    const heading = STRIP.pickHeading(evidenceNo ?? 0);
    const objectText =
      selectedObject === null || selectedObject === undefined
        ? STRIP.pickNoObject
        : `${selectedObject.label} (번호 ${selectedObject.id})`;
    return (
      <section className={SHELL_CLASS} aria-label={heading} data-testid="annotation-strip-pick">
        <div className="flex flex-wrap items-center gap-2.5">
          <span className="inline-flex h-6 items-center rounded-full bg-primary-50 px-2 text-caption font-semibold text-primary-700">
            {heading}
          </span>
          <span className="text-body-md text-gray-600" data-testid="annotation-strip-status">
            {STRIP.pickStatus(frameIndex ?? 0, frameTotal ?? 0, objectText)}
          </span>
          <span className="flex-1" />
          <Button size="sm" variant="secondary" onClick={onCaptureFrame}>
            {STRIP.captureFrame}
          </Button>
          <Button
            size="sm"
            variant="secondary"
            onClick={onCaptureObject}
            disabled={selectedObject === null || selectedObject === undefined}
          >
            {STRIP.captureObject}
          </Button>
          <span
            className="inline-flex h-6 items-center rounded-full bg-gray-100 px-2 text-caption font-semibold text-gray-700"
            data-testid="annotation-strip-picked-counts"
          >
            {STRIP.pickedCounts(pickedCounts?.frames ?? 0, pickedCounts?.objects ?? 0)}
          </span>
          <Button size="sm" variant="ghost" onClick={onCancel}>
            {STRIP.cancel}
          </Button>
          <Button size="sm" onClick={onComplete}>
            {STRIP.complete}
          </Button>
        </div>
        <p className="mt-1.5 text-caption text-gray-500">{STRIP.pickGuide(evidenceNo ?? 0)}</p>
      </section>
    );
  }

  if (variant === 'evidenceJump') {
    const heading = STRIP.evidenceJumpHeading(evidenceNo ?? 0, frameIndex ?? 0);
    return (
      <section
        className={SHELL_CLASS}
        aria-label={heading}
        data-testid="annotation-strip-evidence-jump"
      >
        <div className="flex flex-wrap items-center gap-2.5">
          <span className="inline-flex h-6 items-center rounded-full bg-primary-50 px-2 text-caption font-semibold text-primary-700">
            {heading}
          </span>
          <span className="text-body-md text-gray-600">{STRIP.evidenceJumpGuide}</span>
          <span className="flex-1" />
          <Button size="sm" onClick={onExpand}>
            {STRIP.expand}
          </Button>
        </div>
      </section>
    );
  }

  return (
    <section className={SHELL_CLASS} aria-label="창 접힘" data-testid="annotation-strip-folded">
      <div className="flex flex-wrap items-center gap-2.5">
        <span className="text-body-md text-gray-600">{STRIP.foldedGuide}</span>
        {dirty && (
          <span
            data-testid="annotation-strip-folded-dirty"
            className="inline-flex items-center gap-1 text-caption font-semibold text-warning-700"
          >
            <span aria-hidden="true" className="h-2 w-2 rounded-full bg-warning-500" />
            {STRIP.foldedDirty}
          </span>
        )}
        <span className="flex-1" />
        <Button size="sm" onClick={onExpand}>
          {STRIP.expand}
        </Button>
      </div>
    </section>
  );
}
