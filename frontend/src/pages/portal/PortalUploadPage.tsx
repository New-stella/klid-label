// Phase 5 — 포털 업로드 화면 (PORTAL_USER, ADR-013 예외 = 포털 자체 업로드 자산).
// - 이미지: 다중 선택 + 클라이언트 사전검증(확장자/개수/크기) 후 multipart 업로드
// - 영상: 기존 TUS 엔진 재사용(포털 endpoint 주입) — 재개 가능 청크 업로드
// - 목록: 타입/상태 배지 + 페이징, PROCESSING 자산은 폴링, READY 자산에 라벨링 진입
// - 삭제: 확인 후 요청 (PROCESSING 이면 BE 가 409)
//
// 보안: 사용자 파일명은 JSX 텍스트 노드로만 렌더(자동 escape, XSS 방어). URL 은 apiClient baseURL.

import { useMemo, useRef, useState, type ChangeEvent } from 'react';
import { Link } from 'react-router-dom';
import { Trash2, UploadCloud } from 'lucide-react';

import { StatusBadge } from '@/components/common/StatusBadge';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { cn } from '@/lib/cn';
import { ApiError } from '@/lib/api/errors';
import { ErrorCode } from '@/lib/api/types';
import { useTusUpload } from '@/features/upload/hooks/useTusUpload';
import {
  IMAGE_POLICY_TEXT,
  validateImageFiles,
} from '@/features/portal/uploads/validation';
import { usePortalUploads } from '@/features/portal/uploads/hooks/usePortalUploads';
import { useUploadImages } from '@/features/portal/uploads/hooks/useUploadImages';
import { useDeleteUpload } from '@/features/portal/uploads/hooks/useDeleteUpload';
import { PortalUploadStatus, type PortalUpload } from '@/features/portal/uploads/types';

const STATUS_LABELS: Record<string, string> = {
  UPLOADED: '업로드됨',
  PROCESSING: '처리중',
  READY: '준비 완료',
  FAILED: '실패',
};

const PORTAL_TUS_ENDPOINT = '/portal/uploads/tus';
/** 포털 영상 허용 확장자(BE 와 동일: mp4/mov/avi). accept 1차 가드 — 최종 검증은 서버. */
const VIDEO_ACCEPT = 'video/mp4,video/quicktime,video/x-msvideo,.mp4,.mov,.avi';

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
  return `${Math.max(1, Math.round(bytes / 1024))}KB`;
}

/**
 * 삭제 실패 사유를 사용자 문구로 매핑. PROCESSING 자산은 BE 가 409(CONFLICT)로 거부하므로
 * 그 경우만 전용 안내, 그 외는 일반 문구(내부 메시지 미노출 — CWE-209).
 */
function deleteErrorMessage(error: unknown): string {
  if (error instanceof ApiError && (error.status === 409 || error.errorCode === ErrorCode.CONFLICT)) {
    return '처리 중 자산은 삭제할 수 없습니다.';
  }
  return '삭제에 실패했습니다. 잠시 후 다시 시도해 주세요.';
}

export function PortalUploadPage() {
  const uploadsQuery = usePortalUploads({ page: 0, size: 20 });
  const uploadImages = useUploadImages();
  const deleteUpload = useDeleteUpload();

  const uploads: PortalUpload[] = useMemo(
    () => uploadsQuery.data?.content ?? [],
    [uploadsQuery.data],
  );

  // ── 이미지 선택/검증 상태 ──
  const [selected, setSelected] = useState<File[]>([]);
  const [validationErrors, setValidationErrors] = useState<string[]>([]);
  const imageInputRef = useRef<HTMLInputElement | null>(null);

  const onImageSelect = (e: ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(e.target.files ?? []);
    const { valid, errors } = validateImageFiles(picked);
    setSelected(valid);
    setValidationErrors(errors);
  };

  const onUploadImages = async () => {
    if (selected.length === 0) return;
    try {
      await uploadImages.uploadAsync(selected);
      setSelected([]);
      setValidationErrors([]);
      if (imageInputRef.current) imageInputRef.current.value = '';
    } catch {
      // 서버 검증 실패 등은 하단 mutation 에러 영역으로 노출된다.
    }
  };

  // ── 영상 TUS 업로드(엔진 재사용, 포털 endpoint 주입) ──
  const tus = useTusUpload({ endpointBase: PORTAL_TUS_ENDPOINT });
  const [videoFile, setVideoFile] = useState<File | null>(null);
  const videoInputRef = useRef<HTMLInputElement | null>(null);
  const videoPercent = Math.round(tus.progress * 100);

  const onVideoStart = () => {
    if (!videoFile) return;
    void tus
      .start(videoFile, { filename: videoFile.name })
      .catch(() => undefined);
  };

  // ── 삭제 ──
  const onDelete = async (uld: PortalUpload) => {
    const ok = window.confirm(`"${uld.orgnlFileNm}" 자산을 삭제할까요? 되돌릴 수 없습니다.`);
    if (!ok) return;
    try {
      await deleteUpload.deleteAsync(uld.uldSn);
    } catch {
      // BE 409(처리중) 등 — mutation 에러로 노출.
    }
  };

  return (
    <div className="mx-auto flex w-full max-w-4xl flex-col gap-6">
      <header>
        <h1 className="text-page-title text-gray-900">내 업로드</h1>
        <p className="text-sub text-gray-500">이미지·영상을 업로드하고 라벨링을 진행하세요.</p>
      </header>

      {/* 이미지 업로드 */}
      <section
        aria-label="이미지 업로드"
        className="flex flex-col gap-3 rounded-xl border border-gray-200 bg-white p-5 shadow-sm"
      >
        <h2 className="text-section-title text-gray-800">이미지 업로드</h2>
        <div className="flex flex-col gap-1">
          <label htmlFor="portal-image-input" className="text-body font-medium text-gray-700">
            이미지 파일 (다중 선택)
          </label>
          <input
            ref={imageInputRef}
            id="portal-image-input"
            type="file"
            accept="image/jpeg,image/png,.jpg,.jpeg,.png"
            multiple
            onChange={onImageSelect}
            className="text-sm text-gray-700 file:mr-3 file:rounded-md file:border-0 file:bg-primary-50 file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-primary-700 hover:file:bg-primary-100"
          />
          <span className="text-sub text-gray-400">{IMAGE_POLICY_TEXT}</span>
        </div>

        {validationErrors.length > 0 && (
          <ul role="alert" className="flex flex-col gap-1 rounded-md border border-danger/30 bg-danger/10 px-3 py-2 text-sub text-danger">
            {validationErrors.map((msg, i) => (
              <li key={i}>{msg}</li>
            ))}
          </ul>
        )}

        {selected.length > 0 && (
          <p className="text-sub text-gray-600">선택된 이미지 {selected.length}장</p>
        )}

        {uploadImages.error != null && (
          <p role="alert" className="text-sub text-danger">
            업로드에 실패했습니다. 파일 형식·크기를 확인해 주세요.
          </p>
        )}

        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={onUploadImages}
            disabled={selected.length === 0 || uploadImages.isPending}
            className={cn(
              'inline-flex items-center gap-1.5 rounded-lg bg-primary-600 px-4 py-2 text-sub font-medium text-white transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-50',
              KRDS_FOCUS,
            )}
          >
            <UploadCloud className="h-4 w-4" aria-hidden />
            {uploadImages.isPending ? '업로드 중…' : '이미지 업로드'}
          </button>
          {uploadImages.isPending && (
            <span className="text-sub text-gray-500">{Math.round(uploadImages.progress * 100)}%</span>
          )}
        </div>
      </section>

      {/* 영상 업로드 (TUS) */}
      <section
        aria-label="영상 업로드"
        className="flex flex-col gap-3 rounded-xl border border-gray-200 bg-white p-5 shadow-sm"
      >
        <h2 className="text-section-title text-gray-800">영상 업로드</h2>
        <div className="flex flex-col gap-1">
          <label htmlFor="portal-video-input" className="text-body font-medium text-gray-700">
            영상 파일
          </label>
          <input
            ref={videoInputRef}
            id="portal-video-input"
            type="file"
            accept={VIDEO_ACCEPT}
            onChange={(e) => setVideoFile(e.target.files?.[0] ?? null)}
            disabled={tus.status === 'uploading'}
            className="text-sm text-gray-700 file:mr-3 file:rounded-md file:border-0 file:bg-primary-50 file:px-3 file:py-1.5 file:text-sm file:font-medium file:text-primary-700 hover:file:bg-primary-100"
          />
          <span className="text-sub text-gray-400">mp4/mov/avi · 최대 5GB (재개 가능 업로드)</span>
        </div>

        {tus.totalBytes > 0 && (
          <div
            role="progressbar"
            aria-valuenow={videoPercent}
            aria-valuemin={0}
            aria-valuemax={100}
            className="h-2 w-full overflow-hidden rounded-full bg-gray-200"
          >
            <div className="h-full bg-primary-500 transition-all" style={{ width: `${videoPercent}%` }} />
          </div>
        )}
        {tus.error && (
          <p role="alert" className="text-sub text-danger">{tus.error}</p>
        )}

        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={onVideoStart}
            disabled={!videoFile || tus.status === 'uploading'}
            className={cn(
              'inline-flex items-center gap-1.5 rounded-lg bg-primary-600 px-4 py-2 text-sub font-medium text-white transition-colors hover:bg-primary-700 disabled:cursor-not-allowed disabled:opacity-50',
              KRDS_FOCUS,
            )}
          >
            <UploadCloud className="h-4 w-4" aria-hidden />
            {tus.status === 'uploading' ? `업로드 중… ${videoPercent}%` : '영상 업로드'}
          </button>
        </div>
      </section>

      {/* 자산 목록 */}
      <section aria-label="업로드 자산 목록" className="flex flex-col gap-3">
        <h2 className="text-sub font-semibold uppercase tracking-wide text-gray-500">업로드 자산</h2>
        {deleteUpload.error != null && (
          <p role="alert" className="text-sub text-danger">
            {deleteErrorMessage(deleteUpload.error)}
          </p>
        )}
        {uploadsQuery.isLoading ? (
          <p className="text-sub text-gray-500">목록을 불러오는 중…</p>
        ) : uploads.length === 0 ? (
          <p className="text-sub text-gray-500">업로드한 자산이 없습니다.</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {uploads.map((u) => (
              <UploadItem
                key={u.uldSn}
                upload={u}
                onDelete={() => onDelete(u)}
                deleting={deleteUpload.isPending}
              />
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

interface UploadItemProps {
  upload: PortalUpload;
  onDelete: () => void;
  deleting: boolean;
}

function UploadItem({ upload, onDelete, deleting }: UploadItemProps) {
  const isReady = upload.uldSttsCd === PortalUploadStatus.READY;
  const isFailed = upload.uldSttsCd === PortalUploadStatus.FAILED;
  // 처리 중 자산은 BE 가 삭제를 409 로 거부하므로 버튼 자체를 비활성화(무반응 방지).
  const isProcessing = upload.uldSttsCd === PortalUploadStatus.PROCESSING;

  return (
    <li
      data-testid={`portal-upload-item-${upload.uldSn}`}
      className="flex flex-col gap-2 rounded-xl border border-gray-200 bg-white p-4 shadow-sm sm:flex-row sm:items-center sm:justify-between"
    >
      <div className="flex min-w-0 flex-col gap-1">
        <div className="flex items-center gap-2">
          {/* 사용자 파일명 — 텍스트 노드(자동 escape). */}
          <span className="truncate text-body font-medium text-gray-800">{upload.orgnlFileNm}</span>
          <StatusBadge status={upload.uldSttsCd} label={STATUS_LABELS[upload.uldSttsCd]} />
        </div>
        <span className="text-sub text-gray-500">
          {upload.uldTypeCd} · {formatSize(upload.fileSz)}
          {upload.frmeCnt != null ? ` · 프레임 ${upload.frmeCnt}건` : ''}
        </span>
        {isFailed && (
          <span className="text-sub text-danger">
            {upload.failRsnCn ?? '처리에 실패했습니다. 다시 업로드해 주세요.'}
          </span>
        )}
      </div>

      <div className="flex shrink-0 items-center gap-2">
        {isReady && (
          <Link
            to={`/portal/uploads/${upload.uldSn}/label`}
            className={cn(
              'inline-flex items-center rounded-lg bg-primary-600 px-3 py-1.5 text-sub font-medium text-white transition-colors hover:bg-primary-700',
              KRDS_FOCUS,
            )}
          >
            라벨링
          </Link>
        )}
        <button
          type="button"
          onClick={onDelete}
          disabled={deleting || isProcessing}
          title={isProcessing ? '처리 중 자산은 삭제할 수 없습니다.' : undefined}
          aria-label={`${upload.orgnlFileNm} 삭제`}
          className={cn(
            'inline-flex items-center gap-1 rounded-lg border border-gray-300 px-3 py-1.5 text-sub font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-50',
            KRDS_FOCUS,
          )}
        >
          <Trash2 className="h-3.5 w-3.5" aria-hidden />
          삭제
        </button>
      </div>
    </li>
  );
}
