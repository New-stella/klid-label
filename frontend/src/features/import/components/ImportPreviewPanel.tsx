import { Alert } from '@/components/common/Alert';

import type { ImportScanResult } from '../types';
import { groupWarnings } from '../warningDisplay';

const STAT_LABEL_CLASS = 'text-caption text-gray-600';
const STAT_VALUE_CLASS = 'text-title-sm text-gray-900';

export interface ImportPreviewPanelProps {
  result: ImportScanResult;
}

/**
 * 미리보기 — 검사 결과를 보여준다.
 *
 * 알림은 서버가 한 목록으로 돌려주며 그 안에 적재를 막는 것과 알리기만 하는 것이 함께 담긴다.
 * 여기서는 **보여주기 위해서만** 두 묶음으로 가른다({@link groupWarnings}).
 *
 * ★적재 가능 여부는 이 화면이 세지 않는다 — 응답의 `importable` 값 하나가 정하고 적재 버튼이
 * 그 값을 그대로 읽는다. 알림이 몇 건인지는 판정에 쓰지 않는다.
 *
 * 선언된 프레임 수와 실제 파일 수가 다르면 둘 다 보여주고 실제 파일을 기준으로 적재한다는 것을
 * 명시한다.
 *
 * @design SCREEN-039
 * @design API-205
 */
export function ImportPreviewPanel({ result }: ImportPreviewPanelProps) {
  const { blocking, advisory } = groupWarnings(result.warnings);
  const declaredMismatch =
    result.declaredFrameCount > 0 && result.declaredFrameCount !== result.frameCount;

  return (
    <section
      aria-labelledby="import-preview-heading"
      data-testid="import-preview"
      className="flex flex-col gap-4 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
    >
      <h2 id="import-preview-heading" className="text-title-sm text-gray-900">
        가져올 내용
      </h2>

      <dl className="grid grid-cols-2 gap-4 md:grid-cols-4">
        <div>
          <dt className={STAT_LABEL_CLASS}>영상 파일명</dt>
          <dd className={STAT_VALUE_CLASS} data-testid="import-preview-video">
            {result.videoFileName ?? '없음'}
          </dd>
        </div>
        <div>
          <dt className={STAT_LABEL_CLASS}>프레임 수 (실제 / 선언)</dt>
          <dd className={STAT_VALUE_CLASS} data-testid="import-preview-frames">
            {result.frameCount} / {result.declaredFrameCount}
          </dd>
        </div>
        <div>
          <dt className={STAT_LABEL_CLASS}>라벨 수</dt>
          <dd className={STAT_VALUE_CLASS} data-testid="import-preview-labels">
            {result.labelCount}
          </dd>
        </div>
        <div>
          <dt className={STAT_LABEL_CLASS}>대응이 필요한 분류</dt>
          <dd className={STAT_VALUE_CLASS} data-testid="import-preview-unmapped-count">
            {result.unmappedCategories.length}
          </dd>
        </div>
      </dl>

      {declaredMismatch && (
        <p className="text-body-sm text-gray-700" data-testid="import-declared-mismatch">
          산출물 문서가 선언한 프레임 수({result.declaredFrameCount})와 실제 파일 수(
          {result.frameCount})가 다릅니다. 적재는 <strong>실제 파일</strong>을 기준으로 합니다.
        </p>
      )}

      {result.duplicate && (
        <Alert variant="error" title="이미 가져온 산출물입니다" data-testid="import-duplicate">
          기존 영상 번호: {result.duplicate.rawSn}
        </Alert>
      )}

      {blocking.length > 0 && (
        <Alert
          variant="error"
          title="적재를 막는 알림"
          data-testid="import-blocking-warnings"
        >
          <ul className="list-inside list-disc">
            {blocking.map((w) => (
              <li key={`${w.code}-${w.message}`}>{w.message}</li>
            ))}
          </ul>
        </Alert>
      )}

      {advisory.length > 0 && (
        <Alert
          variant="info"
          title="알리기만 하는 사항 — 이 사항들은 적재를 막지 않습니다"
          data-testid="import-advisory-warnings"
        >
          <ul className="list-inside list-disc">
            {advisory.map((w) => (
              <li key={`${w.code}-${w.message}`}>{w.message}</li>
            ))}
          </ul>
        </Alert>
      )}
    </section>
  );
}
