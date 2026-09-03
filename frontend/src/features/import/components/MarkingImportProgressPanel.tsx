import { Link } from 'react-router-dom';

import { Alert } from '@/components/common/Alert';
import { Field, FieldLabel } from '@/components/common/Field';
import { ProgressBar } from '@/components/common/ProgressBar';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/common/Select';
import { Spinner } from '@/components/common/Spinner';

import {
  MarkingItemStatus,
  MarkingJobStatus,
  type MarkingProgress,
} from '../markingTypes';

const STAT_LABEL_CLASS = 'text-caption text-gray-600';
const STAT_VALUE_CLASS = 'text-title-sm text-gray-900';
// 표 표면 관례(DS-001 do_rules) — 헤더 배경은 secondary 최옅단, 헤더 글자는 전용 타이포
// 토큰이 굵기까지 단독으로 정하고, 본문 크기는 표 루트가 선언한 17px 을 상속한다.
const TH_CLASS = 'px-3 py-2 text-left text-table-header uppercase tracking-wide text-gray-600';
const TD_CLASS = 'px-3 py-2 text-gray-800 align-top';

/** 상태 거르기 선택지 — 계약이 정한 값 밖은 고를 수 없다. 빈 값은 「전체」다. */
const STATUS_OPTIONS: ReadonlyArray<{ value: string; label: string }> = [
  { value: '', label: '전체' },
  { value: MarkingItemStatus.PENDING, label: '대기' },
  { value: MarkingItemStatus.PROCESSING, label: '처리 중' },
  { value: MarkingItemStatus.SUCCESS, label: '성공' },
  { value: MarkingItemStatus.FAILED, label: '실패' },
  { value: MarkingItemStatus.SKIPPED, label: '건너뜀' },
];

const ITEM_STATUS_LABEL: Record<string, string> = {
  [MarkingItemStatus.PENDING]: '대기',
  [MarkingItemStatus.PROCESSING]: '처리 중',
  [MarkingItemStatus.SUCCESS]: '성공',
  [MarkingItemStatus.FAILED]: '실패',
  [MarkingItemStatus.SKIPPED]: '건너뜀',
};

const JOB_STATUS_LABEL: Record<string, string> = {
  [MarkingJobStatus.RUNNING]: '진행 중',
  [MarkingJobStatus.COMPLETED]: '완료',
  [MarkingJobStatus.FAILED]: '실패가 남은 채 종결',
  [MarkingJobStatus.CANCELED]: '취소',
};

export interface MarkingImportProgressPanelProps {
  jobSn: number;
  progress: MarkingProgress | undefined;
  loading: boolean;
  /** 조회 실패 안내 — 서버 메시지를 그대로 싣는다. */
  errorMessage: string | null;
  statusFilter: string;
  onStatusFilterChange: (value: string) => void;
}

/**
 * 일괄 적재 진행 — 작업 식별번호로 되풀이 조회한 결과를 보여준다.
 *
 * <p>적재 요청이 곧바로 반환하므로 끝나는 시점을 응답으로 알 수 없고, 이 패널이 그 자리를
 * 대신한다. 작업이 종결되면 되풀이를 멈춘다.
 *
 * <p>★<b>집계는 언제나 전체 기준</b>이다. 상태로 거르는 것은 아래 목록뿐이며, 걸렀다고 위쪽
 * 수치가 함께 줄면 사람이 보는 진행률이 필터에 따라 달라져 무엇이 참인지 알 수 없다.
 *
 * <p>★건별 결과가 상한에 걸려 일부만 담기면 그 사실을 함께 알린다.
 *
 * <p>백 건이면 영상 복사가 시간을 지배해 겉보기에 멈춘 듯 보일 수 있으므로, 지금 어느 영상을
 * 옮기고 있는지를 함께 보여 준다.
 *
 * <p>⚠ 실패와 건너뜀은 사람이 할 일이 다르지만 <b>집계에서는 한 수치로 온다</b>(계약이 둘을
 * 합쳐 준다). 그래서 위쪽에서는 합쳐 보여주고 갈래는 아래 목록의 상태 칸에서 가른다 —
 * 화면이 목록을 세어 집계를 다시 만들지 않는다(목록은 걸러지거나 잘릴 수 있다).
 *
 * @design SCREEN-039
 * @design API-218
 */
export function MarkingImportProgressPanel({
  jobSn,
  progress,
  loading,
  errorMessage,
  statusFilter,
  onStatusFilterChange,
}: MarkingImportProgressPanelProps) {
  const percent =
    progress && progress.targetCount > 0
      ? Math.round((progress.doneCount / progress.targetCount) * 100)
      : 0;
  const processing = progress?.items.find((i) => i.status === MarkingItemStatus.PROCESSING);
  const finishedWithFailure = progress?.status === MarkingJobStatus.FAILED;

  return (
    <section
      aria-labelledby="marking-progress-heading"
      data-testid="marking-progress-panel"
      className="flex flex-col gap-4 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
    >
      <h2 id="marking-progress-heading" className="text-title-sm text-gray-900">
        일괄 적재 진행 — 작업 {jobSn}
      </h2>

      {errorMessage && (
        <Alert variant="error" title="진행을 불러오지 못했습니다" data-testid="marking-progress-error">
          {errorMessage}
        </Alert>
      )}

      {!progress && loading && (
        <div className="flex items-center gap-2 text-body-sm text-gray-700">
          <Spinner size="sm" />
          진행을 불러오는 중…
        </div>
      )}

      {progress && (
        <>
          <div className="flex flex-col gap-2">
            <div className="flex items-baseline justify-between">
              <span className={STAT_LABEL_CLASS}>진행률</span>
              <span className={STAT_VALUE_CLASS} data-testid="marking-progress-count">
                {progress.doneCount} / {progress.targetCount}
              </span>
            </div>
            <ProgressBar
              value={percent}
              tone={finishedWithFailure ? 'danger' : 'primary'}
              showLabel
            />
            <p className="text-body-sm text-gray-700" data-testid="marking-progress-current">
              {processing
                ? `지금 옮기는 중 — ${processing.videoFileName ?? processing.markingFileName}`
                : `작업 상태 — ${JOB_STATUS_LABEL[progress.status] ?? progress.status}`}
            </p>
          </div>

          <dl className="grid grid-cols-3 gap-4">
            <div>
              <dt className={STAT_LABEL_CLASS}>성공</dt>
              <dd className={STAT_VALUE_CLASS} data-testid="marking-succeeded-count">
                {progress.succeededCount}
              </dd>
            </div>
            <div>
              <dt className={STAT_LABEL_CLASS}>실패·건너뜀</dt>
              <dd className={STAT_VALUE_CLASS} data-testid="marking-failed-count">
                {progress.failedCount}
              </dd>
            </div>
            <div>
              <dt className={STAT_LABEL_CLASS}>대상</dt>
              <dd className={STAT_VALUE_CLASS} data-testid="marking-target-count">
                {progress.targetCount}
              </dd>
            </div>
          </dl>

          {finishedWithFailure && (
            <Alert
              variant="error"
              role="alert"
              title="실패가 남은 채 종결되었습니다"
              data-testid="marking-progress-failure"
            >
              아래 목록에서 사유를 확인하고, 고친 뒤 그 항목만 다시 올려 주세요. 이미 성공한
              항목은 다시 올리지 않아도 됩니다.
            </Alert>
          )}

          <Field className="gap-1.5 md:max-w-xs">
            <FieldLabel className="text-label font-semibold text-gray-900" htmlFor="marking-item-status">
              상태로 거르기
            </FieldLabel>
            <Select value={statusFilter} onValueChange={onStatusFilterChange}>
              <SelectTrigger id="marking-item-status">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {STATUS_OPTIONS.map((opt) => (
                  <SelectItem key={opt.value || 'all'} value={opt.value}>
                    {opt.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            <p className={STAT_LABEL_CLASS}>
              거르기는 아래 목록만 좁힙니다. 위쪽 집계는 언제나 전체 기준입니다.
            </p>
          </Field>

          {progress.itemsTruncated && (
            <Alert
              variant="error"
              role="alert"
              title="건별 결과를 일부만 담았습니다"
              data-testid="marking-items-truncated"
            >
              상한에 걸려 아래 목록이 전체가 아닙니다. 상태로 걸러 보면 찾는 항목을 볼 수 있습니다.
            </Alert>
          )}

          <div className="overflow-x-auto">
            <table className="min-w-full border-collapse text-body-md" data-testid="marking-progress-table">
              <caption className="sr-only">건별 결과</caption>
              <thead>
                <tr className="border-b border-gray-200 bg-secondary-50">
                  <th scope="col" className={TH_CLASS}>
                    마킹 문서
                  </th>
                  <th scope="col" className={TH_CLASS}>
                    영상 파일
                  </th>
                  <th scope="col" className={TH_CLASS}>
                    상태
                  </th>
                  <th scope="col" className={TH_CLASS}>
                    영상 번호
                  </th>
                  <th scope="col" className={TH_CLASS}>
                    사유
                  </th>
                </tr>
              </thead>
              <tbody>
                {progress.items.map((item) => (
                  <tr
                    key={item.markingFileName}
                    className="border-t border-gray-200 hover:bg-rowHover"
                    data-testid={`marking-progress-row-${item.markingFileName}`}
                  >
                    <td className={TD_CLASS}>{item.markingFileName}</td>
                    <td className={TD_CLASS}>{item.videoFileName ?? '—'}</td>
                    <td className={TD_CLASS}>{ITEM_STATUS_LABEL[item.status] ?? item.status}</td>
                    <td className={TD_CLASS}>{item.rawSn ?? '—'}</td>
                    <td className={TD_CLASS}>{item.failureReason ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {progress.items.length === 0 && (
            <p className="text-body-sm text-gray-700" data-testid="marking-progress-empty">
              담긴 항목이 없습니다.
            </p>
          )}

          <Link
            to="/video/status"
            className="text-body-md text-primary-700 underline"
            data-testid="marking-video-status-link"
          >
            영상 처리 현황으로 이동
          </Link>
        </>
      )}
    </section>
  );
}
