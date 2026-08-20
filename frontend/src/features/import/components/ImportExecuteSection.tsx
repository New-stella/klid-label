import { Link } from 'react-router-dom';

import { Alert } from '@/components/common/Alert';
import { Button } from '@/components/common/Button';
import { Checkbox } from '@/components/common/Checkbox';

import type { ImportCreateResult, ImportScanResult } from '../types';

export interface ImportExecuteSectionProps {
  scan: ImportScanResult;
  /** 이 산출물을 비식별이 끝난 것으로 지정했는지 — 보류 안내의 갈래를 정한다. */
  deidentified: boolean;
  /** 원본 영상 파일을 함께 넘겼는지 — 보류를 푸는 길이 어느 쪽인지를 정한다. */
  hasVideoPath: boolean;
  acknowledged: boolean;
  importing: boolean;
  result: ImportCreateResult | null;
  /** 적재 실패 안내 — 서버 메시지를 그대로 싣는다(409 의 기존 영상 번호가 여기에 있다). */
  errorMessage: string | null;
  onAcknowledgedChange: (value: boolean) => void;
  onImport: () => void;
}

/**
 * 적재 실행.
 *
 * ★★버튼의 활성 여부는 **검사 응답의 `importable` 값 하나**가 정한다. 알림이 몇 건인지 세지
 * 않는다 — 판정 지점을 둘로 두면 두 값이 어긋날 때 어느 쪽이 진실인지 알 수 없다. 막는 알림이
 * 있어도 `importable` 이 참이면 활성이고, 알림이 하나도 없어도 거짓이면 비활성이다.
 *
 * 확인 칸은 **감사 기록**이라 차단 사유를 해제하지 않는다. 다만 사람이 알림을 읽고 진행했다는
 * 것을 남기려고, 알림이 있을 때는 확인해야 누를 수 있게 한다.
 *
 * 적재 결과에는 승인 보류가 섰는지와 그 보류를 푸는 길이 어느 갈래인지가 함께 나온다. 이 보류는
 * 검수 승인 하나만 막는다 — 검수 착수·라벨 조회·프레임 이미지 열람은 그대로 열려 있으므로 그
 * 범위를 함께 알린다(화면 전체가 잠긴 것으로 오해하지 않도록).
 *
 * @design SCREEN-039
 * @design API-206
 */
export function ImportExecuteSection({
  scan,
  deidentified,
  hasVideoPath,
  acknowledged,
  importing,
  result,
  errorMessage,
  onAcknowledgedChange,
  onImport,
}: ImportExecuteSectionProps) {
  const hasWarnings = scan.warnings.length > 0;
  // 활성 여부의 유일한 판정값. 확인 칸은 감사 기록이라 이 판정에 얹지 않고, 알림이 있을 때만
  // 진행 전 읽었다는 표시를 요구한다.
  const importable = scan.importable;
  const disabled = !importable || (hasWarnings && !acknowledged) || importing;
  const approvalHeld = !deidentified;

  return (
    <section
      aria-labelledby="import-execute-heading"
      data-testid="import-execute-section"
      className="flex flex-col gap-3 rounded-lg border border-gray-200 bg-white p-4 shadow-sm"
    >
      <h2 id="import-execute-heading" className="text-title-sm text-gray-900">
        적재 실행
      </h2>

      {hasWarnings && (
        <div className="flex items-center gap-2">
          <Checkbox
            id="import-ack"
            data-testid="import-ack-checkbox"
            checked={acknowledged}
            onCheckedChange={(v) => onAcknowledgedChange(v === true)}
          />
          <label className="text-body-sm text-gray-700" htmlFor="import-ack">
            검사에서 돌아온 알림을 확인했습니다
          </label>
        </div>
      )}

      {!importable && (
        <p className="text-body-sm text-gray-700" data-testid="import-not-importable-reason">
          지금 상태로는 적재할 수 없습니다. 위 알림과 대응이 정해지지 않은 분류를 먼저 해결해
          주세요.
        </p>
      )}

      <div className="flex justify-end">
        <Button
          variant="primary"
          data-testid="import-execute-button"
          loading={importing}
          disabled={disabled}
          onClick={onImport}
        >
          가져오기
        </Button>
      </div>

      {errorMessage && (
        <Alert variant="error" title="적재하지 못했습니다" data-testid="import-execute-error">
          {errorMessage}
        </Alert>
      )}

      {result && (
        <div className="flex flex-col gap-3" data-testid="import-execute-result">
          <Alert variant="info" title="적재를 마쳤습니다">
            영상 번호 {result.rawSn} · 프레임 {result.frameCount}건 · 라벨 {result.labelCount}건
          </Alert>

          {approvalHeld && (
            <Alert
              variant="error"
              title="승인 보류 — 비식별이 끝나야 검수 승인이 가능합니다"
              data-testid="import-approval-hold"
            >
              검수 착수와 라벨 조회, 프레임 이미지 열람은 그대로 열려 있습니다.
            </Alert>
          )}

          {approvalHeld && (
            <p className="text-body-sm text-gray-700" data-testid="import-approval-hold-path">
              {hasVideoPath
                ? '보류를 푸는 길 — 원본 영상을 함께 가져왔으므로 저작도구가 비식별 단계를 태우고 그 비식별이 성공으로 기록되면 풀립니다.'
                : '보류를 푸는 길 — 프레임만 가져왔으므로 외부에서 비식별한 산출물을 받아 아래 이관 이력에서 기록하면 풀립니다.'}
            </p>
          )}

          <Link
            to="/review"
            className="text-body-md text-primary-700 underline"
            data-testid="import-review-link"
          >
            검수 목록으로 이동
          </Link>
        </div>
      )}
    </section>
  );
}
