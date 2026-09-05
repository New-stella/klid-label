import { Alert } from '@/components/common/Alert';
import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';
import { cn } from '@/lib/cn';

import type { MarkingScanItem, MarkingScanResult } from '../markingTypes';

/**
 * 역산 속도와 실측 속도가 어긋난 항목을 <b>눈에 띄게</b> 표시하기 위한 사유 코드.
 *
 * ★표시 강조에만 쓴다 — 적재 가능 여부의 판정은 항목의 `importable` 하나가 소유한다.
 */
const FPS_MISMATCH_CODE = 'FPS_MISMATCH';

const STAT_LABEL_CLASS = 'text-caption text-gray-600';
const STAT_VALUE_CLASS = 'text-title-sm text-gray-900';
// 표 표면 관례(DS-001 do_rules) — 헤더 배경은 secondary 최옅단, 헤더 글자는 전용 타이포
// 토큰이 굵기까지 단독으로 정하고, 본문 크기는 표 루트가 선언한 17px 을 상속한다.
const TH_CLASS = 'px-3 py-2 text-left text-table-header uppercase tracking-wide text-gray-600';
const TD_CLASS = 'px-3 py-2 text-gray-800 align-top';

export interface MarkingScanResultPanelProps {
  result: MarkingScanResult;
  /** 고른 마킹 문서 이름 — 적재할 수 없는 항목은 여기 담기지 않는다. */
  selected: ReadonlySet<string>;
  /** 공통 정보가 다 채워졌는지 — 적재 버튼의 활성 조건 가운데 하나다. */
  metaReady: boolean;
  submitting: boolean;
  /** 적재 실패 안내 — 서버 메시지를 그대로 싣는다. */
  errorMessage: string | null;
  onToggle: (markingFileName: string) => void;
  onToggleAll: (checked: boolean) => void;
  onSubmit: () => void;
}

/** 소수점이 길게 흐르지 않게 다듬는다. 값이 없으면 「알 수 없음」으로 표시한다. */
function fps(value: number | null): string {
  return value === null ? '알 수 없음' : String(Math.round(value * 1000) / 1000);
}

function hasFpsMismatch(item: MarkingScanItem): boolean {
  return item.warnings.some((w) => w.code === FPS_MISMATCH_CODE);
}

/**
 * 검사 결과 — 짝 목록과 건별 사유를 보여주고 적재할 항목을 고른다.
 *
 * <p>★★<b>적재 가능 여부의 판정은 항목의 `importable` 값 하나가 한다.</b> 알림 목록에는 적재를
 * 막는 사유와 막지 않는 사유가 섞여 있으므로 알림이 있는지로 판정하면 「바로가기를 건너뛰었다」
 * 같은 알림 하나 때문에 정상 항목이 적재 불가로 보인다.
 *
 * <p>★상한에 걸려 일부만 훑었으면 그 사실을 <b>조용히 넘기지 않는다</b>. 알리지 않으면 일부만
 * 들어온 것이 전부로 보이고, 사람은 나머지가 원래 없었다고 판단한다.
 *
 * <p>어느 마킹 문서도 가리키지 않은 영상 수를 함께 보여 준다 — 받은 묶음이 온전한지 사람이
 * 판단하는 근거다.
 *
 * <p>짝을 찾지 못한 항목도 목록에 남긴다. 감추면 무엇이 빠졌는지 알 수 없다.
 *
 * @design SCREEN-039
 * @design API-216
 * @design API-217
 * @design AC-1032
 * @design AC-1033
 */
export function MarkingScanResultPanel({
  result,
  selected,
  metaReady,
  submitting,
  errorMessage,
  onToggle,
  onToggleAll,
  onSubmit,
}: MarkingScanResultPanelProps) {
  const importableNames = result.items.filter((i) => i.importable).map((i) => i.markingFileName);
  const allSelected = importableNames.length > 0 && importableNames.every((n) => selected.has(n));
  const disabledReason = !metaReady
    ? '일괄 공통 정보를 모두 채워야 적재할 수 있습니다.'
    : selected.size === 0
      ? '적재할 항목을 하나 이상 고르세요.'
      : null;

  return (
    <section
      aria-labelledby="marking-scan-result-heading"
      data-testid="marking-scan-result"
      className="flex flex-col gap-4 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
    >
      <h2 id="marking-scan-result-heading" className="text-title-sm text-gray-900">
        검사 결과
      </h2>

      <dl className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <div>
          <dt className={STAT_LABEL_CLASS}>훑은 파일 수</dt>
          <dd className={STAT_VALUE_CLASS} data-testid="marking-scanned-count">
            {result.scannedFileCount}
          </dd>
        </div>
        <div>
          <dt className={STAT_LABEL_CLASS}>짝을 찾은 수</dt>
          <dd className={STAT_VALUE_CLASS} data-testid="marking-matched-count">
            {result.matchedCount}
          </dd>
        </div>
        <div>
          <dt className={STAT_LABEL_CLASS}>적재할 수 있는 수</dt>
          <dd className={STAT_VALUE_CLASS} data-testid="marking-importable-count">
            {result.importableCount}
          </dd>
        </div>
        <div>
          <dt className={STAT_LABEL_CLASS}>가리켜지지 않은 영상 수</dt>
          <dd className={STAT_VALUE_CLASS} data-testid="marking-unmatched-video-count">
            {result.unmatchedVideoCount}
          </dd>
        </div>
      </dl>

      {result.truncated && (
        <Alert
          variant="error"
          role="alert"
          title="폴더를 일부만 훑었습니다"
          data-testid="marking-scan-truncated"
        >
          상한에 걸려 이 결과가 폴더 전체가 아닙니다. 폴더를 나눠 따로 올려 주세요.
        </Alert>
      )}

      {result.unmatchedVideoNames.length > 0 && (
        <Alert
          variant="info"
          title="어느 마킹 문서도 가리키지 않은 영상"
          data-testid="marking-unmatched-videos"
        >
          {/* Alert 본문은 문단이라 목록 태그를 품을 수 없다 — 줄바꿈 span 으로 나열한다. */}
          {result.unmatchedVideoNames.map((name) => (
            <span key={name} className="block">
              {name}
            </span>
          ))}
        </Alert>
      )}

      {result.warnings.length > 0 && (
        <Alert variant="info" title="알려야 하는 사항" data-testid="marking-scan-warnings">
          {result.warnings.map((w) => (
            <span key={`${w.code}-${w.message}`} className="block">
              {w.message}
            </span>
          ))}
        </Alert>
      )}

      <div className="flex items-center gap-2">
        <Checkbox
          id="marking-select-all"
          data-testid="marking-select-all"
          checked={allSelected}
          disabled={importableNames.length === 0}
          aria-label="적재 가능한 항목 전체 선택"
          onCheckedChange={(v) => onToggleAll(v === true)}
        />
        <label className="text-body-sm text-gray-700" htmlFor="marking-select-all">
          적재 가능한 항목 전체 선택
        </label>
      </div>

      <div className="overflow-x-auto">
        <table className="min-w-full border-collapse text-body-md" data-testid="marking-pair-table">
          <caption className="sr-only">짝 목록</caption>
          <thead>
            <tr className="border-b border-gray-200 bg-secondary-50">
              <th scope="col" className={TH_CLASS}>
                선택
              </th>
              <th scope="col" className={TH_CLASS}>
                마킹 문서
              </th>
              <th scope="col" className={TH_CLASS}>
                영상 파일
              </th>
              <th scope="col" className={TH_CLASS}>
                구간 수
              </th>
              <th scope="col" className={TH_CLASS}>
                시점 수
              </th>
              <th scope="col" className={TH_CLASS}>
                역산 속도
              </th>
              <th scope="col" className={TH_CLASS}>
                실측 속도
              </th>
              <th scope="col" className={TH_CLASS}>
                영상 프레임 수
              </th>
              <th scope="col" className={TH_CLASS}>
                적재 가능
              </th>
              <th scope="col" className={TH_CLASS}>
                사유
              </th>
            </tr>
          </thead>
          <tbody>
            {result.items.map((item) => {
              const mismatch = hasFpsMismatch(item);
              return (
                <tr
                  key={item.markingFileName}
                  data-testid={`marking-pair-row-${item.markingFileName}`}
                  className={cn(
                    'border-t border-gray-200 hover:bg-rowHover',
                    mismatch && 'bg-danger-50',
                    !item.importable && !mismatch && 'bg-gray-50',
                  )}
                >
                  <td className={TD_CLASS}>
                    <Checkbox
                      data-testid={`marking-pair-check-${item.markingFileName}`}
                      checked={selected.has(item.markingFileName)}
                      disabled={!item.importable}
                      aria-label={`${item.markingFileName} 적재 대상으로 고르기`}
                      onCheckedChange={() => onToggle(item.markingFileName)}
                    />
                  </td>
                  <td className={TD_CLASS}>{item.markingFileName}</td>
                  <td className={TD_CLASS}>
                    {item.videoFound ? (item.videoFileName ?? '알 수 없음') : '짝을 찾지 못함'}
                  </td>
                  <td className={TD_CLASS}>{item.segmentCount}</td>
                  <td className={TD_CLASS}>{item.markCount}</td>
                  <td className={TD_CLASS}>{fps(item.declaredFps)}</td>
                  <td className={TD_CLASS}>{fps(item.probedFps)}</td>
                  <td className={TD_CLASS}>{item.videoFrameCount ?? '알 수 없음'}</td>
                  <td className={TD_CLASS} data-testid={`marking-pair-importable-${item.markingFileName}`}>
                    {item.importable ? '가능' : '불가'}
                  </td>
                  <td className={TD_CLASS}>
                    {item.warnings.length === 0 ? '—' : item.warnings.map((w) => w.message).join(' / ')}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>

      {result.items.length === 0 && (
        <p className="text-body-sm text-gray-700" data-testid="marking-pair-empty">
          이 폴더에서 마킹 문서를 찾지 못했습니다.
        </p>
      )}

      <div className="flex items-center justify-end gap-3">
        {disabledReason && (
          <p className="text-body-sm text-gray-700" data-testid="marking-submit-disabled-reason">
            {disabledReason}
          </p>
        )}
        <Button
          variant="primary"
          data-testid="marking-submit-button"
          loading={submitting}
          disabled={disabledReason !== null || submitting}
          onClick={onSubmit}
        >
          선택한 항목 적재
        </Button>
      </div>

      {errorMessage && (
        <Alert variant="error" title="적재를 등록하지 못했습니다" data-testid="marking-submit-error">
          {errorMessage}
        </Alert>
      )}
    </section>
  );
}
