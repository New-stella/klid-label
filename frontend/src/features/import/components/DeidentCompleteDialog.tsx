import { useState } from 'react';

import { Alert } from '@/components/common/Alert';
import { Button } from '@/components/common/Button';
import { Field, FieldLabel } from '@/components/common/Field';
import { Input } from '@/components/common/Input';
import { Modal } from '@/components/common/Modal';

import { useRecordDeidentComplete } from '../hooks/useDeidentComplete';
import type { DeidentCompleteResult } from '../types';

export interface DeidentCompleteDialogProps {
  /** 대상 영상. null 이면 닫혀 있다. */
  rawSn: number | null;
  onClose: () => void;
}

/**
 * 비식별 완료 기록 — 외부에서 비식별한 산출물이 놓인 폴더 위치를 사람이 입력해 기록한다.
 *
 * 이 조작은 비식별을 수행하지 않는다. 서버가 그 자리에 산출물이 실재하는지 확인한 뒤에만 기록이
 * 성립하며, 이름이 맞는 파일이 한 건도 없으면 아무것도 기록하지 않고 거부한다(승인 보류는 그대로).
 *
 * ★결과의 **비워 둔 프레임 수**를 반드시 보여준다. 0 이 아니면 그만큼의 프레임이 비식별 이미지
 * 없이 남아 그 영상의 학습데이터 산출물에 빠진 채로 나간다 — 사람이 그 사실을 알아야 한다.
 *
 * @design SCREEN-039
 * @design API-215
 */
export function DeidentCompleteDialog({ rawSn, onClose }: DeidentCompleteDialogProps) {
  const [folderPath, setFolderPath] = useState('');
  const [result, setResult] = useState<DeidentCompleteResult | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const { mutate: record, isPending } = useRecordDeidentComplete({
    onSuccess: (r) => {
      setErrorMessage(null);
      setResult(r);
    },
    onError: (e) => {
      setResult(null);
      setErrorMessage(
        e instanceof Error && e.message ? e.message : '비식별 완료를 기록하지 못했습니다.',
      );
    },
  });

  const close = () => {
    setFolderPath('');
    setResult(null);
    setErrorMessage(null);
    onClose();
  };

  return (
    <Modal
      open={rawSn !== null}
      onClose={close}
      size="md"
      title="비식별 완료 기록"
      description={
        rawSn === null
          ? undefined
          : `영상 번호 ${rawSn} — 외부에서 비식별한 산출물의 위치를 알려 승인 보류를 풉니다.`
      }
      footer={
        <>
          <Button variant="outline" onClick={close} disabled={isPending}>
            닫기
          </Button>
          <Button
            variant="primary"
            data-testid="deident-complete-submit"
            loading={isPending}
            disabled={folderPath.trim().length === 0 || rawSn === null}
            onClick={() => {
              if (rawSn !== null) {
                record({ rawSn, deidentifiedFolderPath: folderPath.trim() });
              }
            }}
          >
            기록
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-3">
        <Field className="gap-1.5">
          <FieldLabel
            className="text-label font-semibold text-gray-900"
            htmlFor="deident-complete-path"
            required
          >
            비식별 산출물 폴더 경로
          </FieldLabel>
          <Input
            id="deident-complete-path"
            type="text"
            value={folderPath}
            placeholder="예: /nas-storage/handover/00000073-deid"
            onChange={(e) => setFolderPath(e.target.value)}
          />
          <p className="text-caption text-gray-600">
            허용된 저장소 범위 밖이거나 그 자리에 산출물이 실재하지 않으면 서버가 받지 않습니다.
          </p>
        </Field>

        {errorMessage && (
          <Alert variant="error" title="기록하지 못했습니다" data-testid="deident-complete-error">
            {errorMessage}
          </Alert>
        )}

        {result && (
          <div className="flex flex-col gap-3" data-testid="deident-complete-result">
            <Alert
              variant="info"
              title={
                result.approvalHoldReleased
                  ? '기록했습니다 — 승인 보류가 풀렸습니다'
                  : '기록했습니다 — 승인 보류는 그대로 남아 있습니다'
              }
            >
              비식별 이미지를 채운 프레임 {result.deidentFrameMatchedCount}건 · 이름이 맞는 파일이
              없어 비워 둔 프레임 {result.deidentFrameUnmatchedCount}건
            </Alert>

            {result.deidentFrameUnmatchedCount > 0 && (
              <Alert
                variant="error"
                title="비워 둔 프레임이 남았습니다"
                data-testid="deident-complete-unmatched"
              >
                {result.deidentFrameUnmatchedCount}건의 프레임이 비식별 이미지 없이 남아 이 영상의
                학습데이터 산출물에 빠진 채로 나갑니다.
              </Alert>
            )}
          </div>
        )}
      </div>
    </Modal>
  );
}
