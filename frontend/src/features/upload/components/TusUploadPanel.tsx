import { useMemo, useRef, useState, type ChangeEvent } from 'react';

import { Button } from '@/components/common/Button';
import { Card } from '@/components/common/Card';
import { useTusUpload } from '@/features/upload/hooks/useTusUpload';
import {
  EventFieldset,
  IdentityFieldset,
  LocationFieldset,
  TechnicalMetaFieldset,
} from '@/features/upload/components/TusMetaFieldsets';
import {
  initialForm,
  toPayload,
  type TusFormState,
} from '@/features/upload/components/tusUploadForm';

// 허용 확장자 (BE 와 동일) — `accept` 속성으로 1차 가드. BE 가 매직바이트로 본 검증.
const ACCEPT_MIME =
  'video/mp4,video/webm,video/quicktime,video/x-msvideo,.mp4,.webm,.mov,.avi';

/**
 * TUS 재개 가능 업로드 패널 (관리 화면 대용량 영상 적재).
 *
 * <p>폼은 **관제가 인입 테이블에 보내는 값 그대로**를 재현한다 — 여기서 올린 영상이 관제가 적재한
 * 영상과 같은 경로·같은 규칙으로 처리되는지 확인하는 것이 목적이다. 입력 fieldset 4종은
 * `TusMetaFieldsets`, 폼 상태·전송 변환은 `tusUploadForm` 으로 분리했다(`component.md` 400줄 규칙).
 *
 * <p>메타는 `Upload-Metadata` 헤더가 아니라 **세션 생성 POST 의 JSON 바디**로 전송한다(헤더 1KB
 * 상한으로는 관제일지 하나도 못 싣는다). 포털 업로드는 이 패널을 쓰지 않으며 헤더 방식 그대로다.
 *
 * 보안:
 * - filename 은 표시용 — 저장명은 BE 가 `{클립ID}.{확장자}` 로 강제(CWE-22).
 * - 에러 메시지는 BE 가 내려준 텍스트를 JSX 자동 이스케이프로 표시(XSS 방어).
 */
export function TusUploadPanel() {
  const [file, setFile] = useState<File | null>(null);
  const [form, setForm] = useState<TusFormState>(initialForm);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const upload = useTusUpload();

  const isUploading = upload.status === 'uploading';
  const percent = Math.round(upload.progress * 100);

  const payload = useMemo(
    () => (file ? toPayload(form, file.name) : null),
    [file, form],
  );

  const setValue = (key: keyof TusFormState, value: string) =>
    setForm((s) => ({ ...s, [key]: value }));

  const setField = (key: keyof TusFormState) => (e: ChangeEvent<HTMLInputElement>) =>
    setValue(key, e.target.value);

  const fieldsetProps = {
    form,
    onField: setField,
    onValue: setValue,
    disabled: isUploading,
  };

  const handleFileChange = (e: ChangeEvent<HTMLInputElement>) => {
    setFile(e.target.files?.[0] ?? null);
  };

  const handleStart = () => {
    if (!file || !payload) return;
    void upload.start(file, { filename: file.name }, payload).catch(() => undefined);
  };

  // 재개는 기존 세션을 이어받는다 — 세션이 없으면 훅이 거부하므로 페이로드를 함께 넘겨
  // "세션 없이 재개"가 바디 없는 POST 로 새지 않게 한다.
  const handleResume = () => {
    if (!file || !payload) return;
    void upload.resume(file, { filename: file.name }, payload).catch(() => undefined);
  };

  const handleCancel = () => {
    void upload.cancel();
    setFile(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  return (
    <Card title="TUS 재개 가능 업로드 (대용량) — 관제 인입 재현" padding="lg">
      <div className="space-y-5">
        <p className="text-sub text-gray-500">
          청크 단위 업로드로 네트워크 중단 시 이어받기를 지원합니다. 입력값은 관제서버가 인입
          테이블에 보내는 항목과 동일하게 적재됩니다.
        </p>

        <div className="flex flex-col gap-1">
          <label htmlFor="tus-file" className="text-body font-medium text-gray-700">
            영상 파일 <span className="text-danger">*</span>
          </label>
          <input
            ref={fileInputRef}
            id="tus-file"
            type="file"
            accept={ACCEPT_MIME}
            onChange={handleFileChange}
            disabled={isUploading}
            className="text-sm text-gray-700 file:mr-3 file:rounded-md file:border-0 file:bg-primary-50 file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-primary-700 hover:file:bg-primary-100 disabled:opacity-60"
          />
          {file && (
            <span data-testid="tus-selected-file" className="text-sub text-gray-700">
              선택: {file.name} ({(file.size / (1024 * 1024)).toFixed(2)} MB)
            </span>
          )}
        </div>

        <IdentityFieldset {...fieldsetProps} />
        <LocationFieldset {...fieldsetProps} />
        <EventFieldset {...fieldsetProps} />
        <TechnicalMetaFieldset {...fieldsetProps} />

        {/* 진행률 */}
        <div className="space-y-1">
          <div className="flex items-center justify-between text-sub text-gray-600">
            <span>진행률</span>
            <span data-testid="tus-progress-pct">{percent}%</span>
          </div>
          <div
            role="progressbar"
            aria-valuenow={percent}
            aria-valuemin={0}
            aria-valuemax={100}
            className="h-2 w-full overflow-hidden rounded-full bg-gray-200"
          >
            <div
              className="h-full bg-primary-500 transition-all"
              style={{ width: `${percent}%` }}
            />
          </div>
          <span className="text-sub text-gray-400">
            상태: {upload.status}
            {upload.totalBytes > 0 &&
              ` · ${(upload.uploadedBytes / (1024 * 1024)).toFixed(1)}MB / ${(upload.totalBytes / (1024 * 1024)).toFixed(1)}MB`}
          </span>
        </div>

        {upload.error && (
          <div
            role="alert"
            data-testid="tus-error"
            className="rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sub text-danger"
          >
            {upload.error}
          </div>
        )}

        {upload.status === 'completed' && (
          <div
            data-testid="tus-completed"
            className={
              upload.ingestStatus === 'PENDING_SCAN_DISABLED'
                ? 'rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sub text-danger'
                : 'rounded-md border border-success/30 bg-success/10 px-3 py-2 text-sub text-success'
            }
          >
            {upload.ingestStatus === 'PENDING_SCAN_DISABLED'
              ? '업로드 완료 — 인입 대기 중이나 인입 스캔이 꺼져 있어 적재되지 않습니다. 서버 설정(authoring.control.training-scan.enabled)을 확인하세요.'
              : '업로드 완료 — 인입 대기 중입니다. 인입 스캔이 픽업하면 영상이 등록됩니다.'}
          </div>
        )}

        <div className="flex items-center gap-2">
          {!isUploading && upload.status !== 'paused' && (
            <Button
              type="button"
              variant="primary"
              onClick={handleStart}
              disabled={!file}
            >
              업로드 시작
            </Button>
          )}
          {isUploading && (
            <Button type="button" variant="secondary" onClick={upload.pause}>
              일시정지
            </Button>
          )}
          {upload.status === 'paused' && (
            <Button type="button" variant="primary" onClick={handleResume} disabled={!file}>
              재개
            </Button>
          )}
          {(isUploading || upload.status === 'paused' || upload.status === 'error') && (
            <Button type="button" variant="secondary" onClick={handleCancel}>
              취소
            </Button>
          )}
        </div>
      </div>
    </Card>
  );
}

export default TusUploadPanel;
