// 우측 메타 탭의 요약 카드 — 「영상 분석 설명 · 이벤트 어노테이션」 창을 여는 자리.
// [@design UI-157] [@design SCREEN-005] [@design SCREEN-019]
//
// 좁은 메타 탭(폭 약 288~360px)에서 수백 자 서술과 후보 목록을 읽고 쓰는 것이 불가능해, 그 자리에는
// <b>요약과 버튼 하나만</b> 두고 전문은 큰 창에서 다룬다. 필드·섹션별 팝업은 두지 않는다 —
// 항목마다 팝업이 뜨면 창 하나를 여는 것보다 더 많은 조작을 요구하게 된다.
//
// ★이 카드에서는 「영상 분석 설명」이라고 쓴다(「시계열」이라고 쓰지 않는다).
// ★검수 화면에는 검토 상태 행을 두지 않는다 — 검토 상태 확정은 영상 검수 승인 시 자동 처리라
//   검수자가 이 자리에서 판단할 것이 없다.

import { type ReactNode } from 'react';

import { Button } from '@/components/common/Button';
import { cn } from '@/lib/cn';

import type { AnnotationWindowState } from '../hooks/useAnnotationWindow';

import { ANNOTATION_WINDOW_TITLE, SUMMARY_CARD } from './annotationWording';
import { useMetaHelpVisible } from './metaHelp';

export interface TimeseriesAnnotationSummaryCardProps {
  /** editable=라벨링, readOnly=검수(검토 상태 행 없음). */
  mode: 'editable' | 'readOnly';
  windowState: AnnotationWindowState;
  eventTypeCd?: string | null;
  /** 이벤트 분류 이름. 없으면 코드만 보인다. */
  eventTypeName?: string | null;
  descriptionFirstLine?: string | null;
  /** 이벤트 어노테이션 검토 상태 — editable 에서만 보인다. */
  reviewStatus?: ReactNode;
  /** 닫혀 있으면 열고, 열려 있으면 앞으로 가져오고, 접혀 있으면 펼친다. */
  onOpenWindow: () => void;
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="mb-1.5 grid grid-cols-[96px_1fr] gap-x-2 gap-y-1 text-body-md">
      <span className="leading-snug text-gray-500">{label}</span>
      <span className="min-w-0 break-words text-gray-900">{children}</span>
    </div>
  );
}

/** 버튼 문구와 상태 표시는 창 상태 하나로 정해진다 — 두 곳이 따로 판정하지 않게 한 곳에 둔다. */
function buttonLabelOf(state: AnnotationWindowState, mode: 'editable' | 'readOnly'): string {
  if (state === 'open') return SUMMARY_CARD.bringToFront;
  if (state === 'folded') return SUMMARY_CARD.expandWindow;
  if (state === 'picking') return SUMMARY_CARD.pickingDisabled;
  return mode === 'editable' ? SUMMARY_CARD.openEditable : SUMMARY_CARD.openReadOnly;
}

function stateLabelOf(state: AnnotationWindowState): string | null {
  if (state === 'open') return SUMMARY_CARD.stateOpen;
  if (state === 'folded') return SUMMARY_CARD.stateFolded;
  if (state === 'picking') return SUMMARY_CARD.statePicking;
  return null;
}

export function TimeseriesAnnotationSummaryCard({
  mode,
  windowState,
  eventTypeCd,
  eventTypeName,
  descriptionFirstLine,
  reviewStatus,
  onOpenWindow,
}: TimeseriesAnnotationSummaryCardProps) {
  const stateLabel = stateLabelOf(windowState);
  const helpVisible = useMetaHelpVisible();
  const hasCode = typeof eventTypeCd === 'string' && eventTypeCd.trim() !== '';
  return (
    <section
      aria-label={`${ANNOTATION_WINDOW_TITLE} 요약`}
      data-testid="annotation-summary-card"
      // 창이 떠 있으면 카드를 강조한다(시안 `.card.open`) — 창과 카드가 같은 것을 가리킨다는
      // 사실을 이 자리에서 알 수 있어야 「창 앞으로 가져오기」 버튼의 대상이 분명해진다.
      className={cn(
        'm-3 rounded-lg border bg-white p-3',
        windowState === 'closed'
          ? 'border-gray-200'
          : 'border-primary-400 ring-2 ring-primary-100',
      )}
    >
      <div className="mb-2 flex items-center justify-between gap-2">
        <h3 className="text-body-md font-bold text-gray-900">{ANNOTATION_WINDOW_TITLE}</h3>
        {stateLabel !== null && (
          <span className="shrink-0 text-caption text-primary-600" data-testid="annotation-summary-state">
            {stateLabel}
          </span>
        )}
      </div>
      {/* ★두 설명 문장은 <b>도움말을 켰을 때만</b> 보인다(기본 감춤 — 2026-09-15 사용자 확정).
          좁은 메타 탭에서 이 둘이 네다섯 줄을 차지해 정작 보여야 할 값을 아래로 밀어냈다.
          문장 자체는 그대로다 — 지운 것이 아니라 도움말 뒤로 옮긴 것이다.
          ★보조 설명은 왜 이 자리에 요약만 있는지 알린다. 사양은 「설명 아래」로 못박는다
          (시안은 버튼 아래에 두지만 사양이 정본이다 — 보고 대상으로 남겼다). */}
      {helpVisible && (
        <>
          <p className="mb-1 text-caption text-gray-500">{SUMMARY_CARD.description}</p>
          <p className="mb-2 text-caption text-gray-600" data-testid="annotation-summary-hint">
            {mode === 'editable' ? SUMMARY_CARD.hint.editable : SUMMARY_CARD.hint.readOnly}
          </p>
        </>
      )}

      <Row label={SUMMARY_CARD.eventClassLabel}>
        {hasCode ? (
          <span data-testid="annotation-summary-event-class">
            {typeof eventTypeName === 'string' && eventTypeName !== '' && (
              <span className="font-bold">{eventTypeName} </span>
            )}
            {/* 이름을 모르는 코드는 코드만 보인다 — 코드를 이름인 것처럼 굵게 쓰지 않는다. */}
            <span className={eventTypeName ? 'text-gray-500' : 'text-gray-900'}>
              {eventTypeCd}
            </span>
          </span>
        ) : (
          <span className="text-gray-500">{SUMMARY_CARD.none}</span>
        )}
      </Row>

      <Row label={SUMMARY_CARD.descriptionLabel}>
        {typeof descriptionFirstLine === 'string' && descriptionFirstLine !== '' ? (
          <span className="line-clamp-2" data-testid="annotation-summary-description">
            {descriptionFirstLine}
          </span>
        ) : (
          <span className="text-gray-500">{SUMMARY_CARD.none}</span>
        )}
      </Row>

      {/* ⚠ [폐기] 구 구현의 「아직 채우지 않은 항목」 행 — 2026-09-15 사용자 확정으로 <b>없앴다</b>.
          근거: 무엇이 비었는지는 창을 열면 그 자리에서 바로 보이는데, 이 행이 좁은 탭에서 값을
          아래로 밀어냈다. 판정 함수({@code annotationSummary.unfilledItems})는 그대로 두었다 —
          그 함수는 순수 함수이고 자체 시험을 가지며, 되살릴 자리가 생기면 다시 쓴다. */}

      {mode === 'editable' && reviewStatus !== undefined && reviewStatus !== null && (
        <Row label={SUMMARY_CARD.reviewStatusLabel}>
          <span data-testid="annotation-summary-review-status">{reviewStatus}</span>
        </Row>
      )}

      <Button
        fullWidth
        size="sm"
        data-testid="annotation-summary-open"
        onClick={onOpenWindow}
        disabled={windowState === 'picking'}
        className="mt-2"
      >
        {buttonLabelOf(windowState, mode)}
      </Button>
    </section>
  );
}
