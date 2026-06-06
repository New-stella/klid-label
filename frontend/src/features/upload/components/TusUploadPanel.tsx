import { useMemo, useRef, useState, type ChangeEvent } from 'react';

import { Button } from '@/components/common/Button';
import { Card } from '@/components/common/Card';
import { Input } from '@/components/common/Input';
import { useTusUpload } from '@/features/upload/hooks/useTusUpload';
import type { TusMetadata } from '@/features/upload/api/tusClient';

// 허용 확장자 (BE 와 동일) — `accept` 속성으로 1차 가드. BE 가 매직바이트로 본 검증.
const ACCEPT_MIME =
  'video/mp4,video/webm,video/quicktime,video/x-msvideo,.mp4,.webm,.mov,.avi';

/** ISO-8601 변환 — datetime-local(timezone 미포함) → UTC Instant. */
function toIsoInstant(localDateTime: string): string {
  if (!localDateTime) return '';
  const d = new Date(localDateTime);
  return Number.isNaN(d.getTime()) ? '' : d.toISOString();
}

function nowLocalDateTime(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

interface TusFormState {
  vmsClipId: string;
  cctvId: string;
  eventTypeCd: string;
  localGovCd: string;
  prvcTypeCd: string;
  capturedAtLocal: string;
}

function initialForm(): TusFormState {
  return {
    vmsClipId: `tus-${Date.now()}`,
    cctvId: 'CCTV-001',
    eventTypeCd: 'EVT_FALL',
    localGovCd: '11680',
    prvcTypeCd: 'ANONY',
    capturedAtLocal: nowLocalDateTime(),
  };
}

/**
 * TUS 1.0 재개 가능 업로드 패널 (관리 화면 대용량 영상 적재).
 *
 * <p>기존 multipart 업로드(DevAutolabelTestPage 폼)는 fallback 으로 유지하고, 본 패널은
 * 대용량/네트워크 불안정 환경을 위한 청크 업로드 + 진행률 + 일시정지/재개를 제공한다.
 *
 * 보안:
 * - filename 은 base64(Upload-Metadata)로 표시용만 전송 — 저장명은 BE 가 UUID 강제(CWE-22).
 * - 에러 메시지는 BE 가 내려준 텍스트를 JSX 자동 이스케이프로 표시(XSS 방어).
 */
export function TusUploadPanel() {
  const [file, setFile] = useState<File | null>(null);
  const [form, setForm] = useState<TusFormState>(initialForm);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const upload = useTusUpload();

  const meta: TusMetadata = useMemo(
    () => ({
      filename: file?.name,
      vmsClipId: form.vmsClipId.trim(),
      cctvId: form.cctvId.trim(),
      eventTypeCd: form.eventTypeCd,
      localGovCd: form.localGovCd.trim(),
      prvcTypeCd: form.prvcTypeCd,
      capturedAt: toIsoInstant(form.capturedAtLocal),
    }),
    [file, form],
  );

  const isUploading = upload.status === 'uploading';
  const percent = Math.round(upload.progress * 100);

  const handleFileChange = (e: ChangeEvent<HTMLInputElement>) => {
    setFile(e.target.files?.[0] ?? null);
  };

  const handleStart = () => {
    if (!file) return;
    void upload.start(file, meta).catch(() => undefined);
  };

  const handleResume = () => {
    if (!file) return;
    void upload.resume(file, meta).catch(() => undefined);
  };

  const handleCancel = () => {
    void upload.cancel();
    setFile(null);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  return (
    <Card title="TUS 재개 가능 업로드 (대용량)" padding="lg">
      <div className="space-y-4">
        <p className="text-sub text-gray-500">
          청크 단위 업로드로 네트워크 중단 시 이어받기를 지원합니다. 기존 일반 업로드는 위 폼을
          사용하세요.
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

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Input
            label="vmsClipId"
            value={form.vmsClipId}
            onChange={(e) => setForm((s) => ({ ...s, vmsClipId: e.target.value }))}
            disabled={isUploading}
            autoComplete="off"
          />
          <Input
            label="cctvId"
            value={form.cctvId}
            onChange={(e) => setForm((s) => ({ ...s, cctvId: e.target.value }))}
            disabled={isUploading}
            autoComplete="off"
          />
        </div>

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
            className="rounded-md border border-danger/30 bg-red-50 px-3 py-2 text-sub text-danger"
          >
            {upload.error}
          </div>
        )}

        {upload.status === 'completed' && (
          <div
            data-testid="tus-completed"
            className="rounded-md border border-green-300 bg-green-50 px-3 py-2 text-sub text-green-700"
          >
            업로드 완료 — 영상 등록(LS_DATA_RAW)이 생성되었습니다.
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
