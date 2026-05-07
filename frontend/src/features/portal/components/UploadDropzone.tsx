// 영상 파일 선택 — 5GB / mp4·mov·avi allowlist + MIME 검증.
// V1.5 보안: FE 사전 차단 + BE 재검증 (이중 방어).

import { type ChangeEvent } from 'react';

import {
  ALLOWED_EXTENSIONS,
  ALLOWED_MIME_TYPES,
  MAX_UPLOAD_SIZE_BYTES,
  validateUpload,
} from '../upload/validateUpload';
import type { UploadValidationError } from '../types';

interface UploadDropzoneProps {
  onAccept: (file: File) => void;
  onReject: (reason: UploadValidationError) => void;
}

const ACCEPT_ATTR = ALLOWED_MIME_TYPES.join(',');
const MAX_GB = MAX_UPLOAD_SIZE_BYTES / (1024 * 1024 * 1024);

export function UploadDropzone({ onAccept, onReject }: UploadDropzoneProps) {
  function handleChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    const result = validateUpload(file);
    if (!result.valid && result.error) {
      onReject(result.error);
    } else {
      onAccept(file);
    }
    // 동일 파일 재선택 가능하도록 input 값 reset
    e.target.value = '';
  }

  return (
    <div
      className="flex flex-col items-center gap-3 rounded border-2 border-dashed border-border bg-white p-6"
      data-testid="upload-dropzone"
    >
      <p className="text-body text-neutral">
        최대 {MAX_GB}GB · 허용 확장자: {ALLOWED_EXTENSIONS.join(', ')}
      </p>
      <label
        className="cursor-pointer rounded bg-primary px-4 py-2 text-btn-label text-white hover:opacity-90"
        htmlFor="portal-upload-input"
      >
        영상 파일 선택
      </label>
      <input
        id="portal-upload-input"
        type="file"
        accept={ACCEPT_ATTR}
        className="sr-only"
        onChange={handleChange}
        aria-label="영상 파일 선택"
      />
    </div>
  );
}
