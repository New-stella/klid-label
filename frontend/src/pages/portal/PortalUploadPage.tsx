// Phase 5 — 포털 업로드 화면 (PORTAL_USER, ADR-013 예외 = 포털 자체 업로드 자산).
// - 영상: 기존 TUS 엔진 재사용(포털 endpoint 주입) — 재개 가능 청크 업로드
//   ★ 신규 접수는 **영상뿐**이다. 이미지 접수 자리는 폐기됐다(되살리지 말 것) — 자산 종류 값역에
//     이미지가 남아 있는 것은 이미 적재된 행을 읽기 위해서이지 접수 수단이 있다는 뜻이 아니다.
// - 목록: 타입/상태 배지 + 페이징, PROCESSING 자산은 폴링, READY 자산에 라벨링 진입
// - 삭제: 확인 후 요청 (PROCESSING 이면 BE 가 409)
// - 자산별 내려받기: 라벨 내보내기(JSON) + 원본 파일 — **라벨링 화면이 아니라 여기가 갖는다**
//   (확정 사양). 자산 단위 조작이라 자산이 늘어놓인 이 자리가 제 위치다.
// - 보존기간: 각 자산에 만료 예정일 병기(날짜까지만) — 만료가 없는 상태면 자리를 비운다. @design SCREEN-033
//
// 보안: 사용자 파일명은 JSX 텍스트 노드로만 렌더(자동 escape, XSS 방어). URL 은 apiClient baseURL.

import { useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Download, Trash2, Upload, X } from 'lucide-react';

import { ErrorState } from '@/components/common/ErrorState';
import { Pagination } from '@/components/common/Pagination';
import { StatusBadge } from '@/components/common/StatusBadge';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { cn } from '@/lib/cn';
import { ApiError } from '@/lib/api/errors';
import { ErrorCode } from '@/lib/api/types';
import { useTusUpload } from '@/features/upload/hooks/useTusUpload';
import { buildPortalUploadLabelPath } from '@/features/portal/labelingEntry';
import { buildPortalUploadMarkingPath } from '@/features/portal/uploads/markingPath';
import { formatExpiryDate } from '@/features/portal/expiry';
import { downloadUploadExport, downloadUploadFile } from '@/features/portal/uploads/api';
import { useUiStore } from '@/stores/useUiStore';
import { usePortalUploads } from '@/features/portal/uploads/hooks/usePortalUploads';
import { useDeleteUpload } from '@/features/portal/uploads/hooks/useDeleteUpload';
import {
  PortalUploadStatus,
  PortalUploadType,
  type PortalUpload,
} from '@/features/portal/uploads/types';

const STATUS_LABELS: Record<string, string> = {
  // ★「업로드됨」이 아니라 「마킹 대기」다 — 마킹을 마쳐야 프레임이 추출되므로, 이 자리에서
  //   알려야 할 것은 업로드가 끝났다는 사실이 아니라 다음에 무엇을 해야 하는가이다.
  //   표기만 그렇게 하고 상태값 자체는 바뀌지 않는다. [@design SCREEN-033]
  UPLOADED: '마킹 대기',
  PROCESSING: '처리중',
  READY: '준비 완료',
  FAILED: '실패',
};

const PORTAL_TUS_ENDPOINT = '/portal/uploads/tus';
/** 목록 한 페이지 건수. */
const PAGE_SIZE = 20;
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
  // 목록 페이지는 화면 안에서만 쓰인다(이 화면은 주소로 상태를 나르지 않는다 — 검색·필터가 없다).
  const [page, setPage] = useState(0);
  const uploadsQuery = usePortalUploads({ page, size: PAGE_SIZE });
  const deleteUpload = useDeleteUpload();

  const uploads: PortalUpload[] = useMemo(
    () => uploadsQuery.data?.content ?? [],
    [uploadsQuery.data],
  );
  const totalPages = uploadsQuery.data?.totalPages ?? 0;

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
        <p className="text-sub text-gray-600">영상을 업로드하고 라벨링을 진행하세요.</p>
      </header>

      {/* 영상 업로드 (TUS) */}
      <section
        aria-label="영상 업로드"
        className="flex flex-col gap-3 rounded-lg border border-gray-200 bg-white p-5 shadow-sm"
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
          <span className="text-sub text-gray-600">mp4/mov/avi · 최대 5GB (재개 가능 업로드)</span>
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
            <Upload className="h-4 w-4" aria-hidden />
            {tus.status === 'uploading' ? `업로드 중… ${videoPercent}%` : '영상 업로드'}
          </button>
        </div>
      </section>

      {/* 자산 목록 */}
      <section aria-label="업로드 자산 목록" className="flex flex-col gap-3">
        <h2 className="text-sub font-semibold uppercase tracking-wide text-gray-600">업로드 자산</h2>
        {deleteUpload.error != null && (
          <p role="alert" className="text-sub text-danger">
            {deleteErrorMessage(deleteUpload.error)}
          </p>
        )}
        {uploadsQuery.isLoading ? (
          <p className="text-sub text-gray-600">목록을 불러오는 중…</p>
        ) : uploadsQuery.isError ? (
          /*
           * 조회 실패는 빈 상태와 반드시 구분한다. React Query 는 실패 시 data 를 undefined 로
           * 두므로 목록이 [] 가 되는데, 그것을 "0건" 으로 그리면 사용자는 **서버 오류를 자기
           * 자산이 사라진 것으로 오해**한다. 문구가 그 오해를 직접 부정한다.
           */
          <ErrorState
            title="목록을 불러올 수 없습니다"
            message="잠시 후 다시 시도해 주세요. 올린 자산이 사라진 것은 아닙니다."
            onRetry={() => void uploadsQuery.refetch()}
          />
        ) : uploads.length === 0 ? (
          <p className="text-sub text-gray-600">업로드한 자산이 없습니다.</p>
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
        {/* 전체가 한 페이지에 들어오면 페이저를 그리지 않는다. */}
        {totalPages > 1 && (
          <Pagination page={page} totalPages={totalPages} onChange={setPage} />
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

/**
 * 자산 하나의 내려받기 두 갈래(라벨 JSON · 원본 파일).
 *
 * ★**취소는 원본 파일에만 둔다** — 원본은 최대 5GB 라 한 번 시작하면 오래 붙잡히지만, 라벨
 *   내보내기(JSON)는 작아서 취소 버튼이 뜨기도 전에 끝난다(사양).
 *
 * ★★**사용자 취소는 오류가 아니라 정상 종료다 — 실패 안내를 띄우지 않는다.** 중단하면 응답이
 *   오지 않아 **일반 실패와 같은 모양**으로 올라오므로, 갈라 놓지 않으면 스스로 멈춘 사용자에게
 *   «원본 다운로드에 실패했습니다» 가 뜬다.
 *   ⚠ 판정 근거로 오류 객체를 쓰지 않는다 — 공용 클라이언트가 취소 표식을 남기지 않아 오류만
 *     봐서는 취소와 회선 단절이 구분되지 않는다. 반면 화면은 자기가 중단을 걸었는지 알고 있으므로
 *     그 사실(`controller.signal.aborted`)로 판정한다.
 *   ⚠ 취소하지 **않은** 실패는 종전대로 안내한다 — 삼키면 진짜 장애가 아무 표시 없이 사라진다.
 *
 * ⚠ 이 조작들은 라벨링 화면에서 이 목록으로 **옮겨 온 것**이다. 라벨링 화면에 되살리면 같은
 *   조작의 진입점이 둘이 된다.
 */
function useUploadDownloads(upload: PortalUpload) {
  const pushToast = useUiStore((s) => s.pushToast);
  const [downloading, setDownloading] = useState<'export' | 'file' | null>(null);
  // 진행 중인 원본 다운로드의 중단 컨트롤러. 취소 버튼이 이것을 통해 전송을 끊는다.
  const fileAbortRef = useRef<AbortController | null>(null);

  const exportLabels = () => {
    if (downloading) return;
    setDownloading('export');
    downloadUploadExport(upload.uldSn)
      .catch(() => pushToast({ variant: 'error', message: '내보내기에 실패했습니다.' }))
      .finally(() => setDownloading(null));
  };

  const downloadFile = () => {
    if (downloading) return;
    const controller = new AbortController();
    fileAbortRef.current = controller;
    setDownloading('file');
    // ref 가 아니라 지역 변수를 닫아 쓴다 — 다음 요청이 ref 를 덮어써도 이 catch 는 자기 요청의
    // 중단 여부를 본다.
    downloadUploadFile(upload.uldSn, upload.orgnlFileNm, controller.signal)
      .catch(() => {
        if (controller.signal.aborted) return; // 사용자가 스스로 멈춘 것 — 정상 종료
        pushToast({ variant: 'error', message: '원본 다운로드에 실패했습니다.' });
      })
      .finally(() => {
        if (fileAbortRef.current === controller) fileAbortRef.current = null;
        setDownloading(null);
      });
  };

  const cancelFileDownload = () => fileAbortRef.current?.abort();

  return { downloading, exportLabels, downloadFile, cancelFileDownload };
}

function UploadItem({ upload, onDelete, deleting }: UploadItemProps) {
  const { downloading, exportLabels, downloadFile, cancelFileDownload } =
    useUploadDownloads(upload);
  const isReady = upload.uldSttsCd === PortalUploadStatus.READY;
  const isFailed = upload.uldSttsCd === PortalUploadStatus.FAILED;
  /*
   * 마킹 진입 — 마킹 대기 상태인 **영상** 자산 행에만 둔다. [@design SCREEN-033] [@design SCREEN-045]
   *
   * ★ 노출 규칙은 라벨링 링크와 같다: 그 자산에서 할 수 없는 액션은 비활성으로 두지 않고 아예
   *   노출하지 않는다. 처리중·준비 완료·실패 행에 두면 눌러 봐야 거절되는 자리가 되어 회복
   *   경로를 잘못 안내한다 — 이미 마킹한 자산의 재마킹은 제공하지 않고, 다시 마킹하려면 지우고
   *   다시 올려야 한다(그 안내는 마킹 화면이 담당한다).
   * ★ 영상이 아닌 자산에는 두지 않는다 — 이벤트 구간이라는 개념이 없다.
   */
  const canMark =
    upload.uldSttsCd === PortalUploadStatus.UPLOADED &&
    upload.uldTypeCd === PortalUploadType.VIDEO;
  // 처리 중 자산은 BE 가 삭제를 409 로 거부하므로 버튼 자체를 비활성화(무반응 방지).
  const isProcessing = upload.uldSttsCd === PortalUploadStatus.PROCESSING;
  const expiresOn = formatExpiryDate(upload.expiresAt);

  return (
    <li
      data-testid={`portal-upload-item-${upload.uldSn}`}
      className="flex flex-col gap-2 rounded-lg border border-gray-200 bg-white p-4 shadow-sm md:flex-row md:items-center md:justify-between"
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
        {/*
         * 보존기간 만료 예정일 — 날짜까지만 적는다(사양 SCREEN-033).
         * 처리 중 자산은 삭제 대상이 아니라 만료가 **없다**. 그때는 자리를 비운다 — `-`·`없음` 같은
         * 문구를 지어내면 만료가 정해졌는데 표기만 빈 것으로 읽힌다.
         */}
        {expiresOn !== null && (
          <span data-testid={`portal-upload-expiry-${upload.uldSn}`} className="text-sub text-gray-500">
            만료: {expiresOn}
          </span>
        )}
        {isFailed && (
          <span className="text-sub text-danger">
            {upload.failRsnCn ?? '처리에 실패했습니다. 다시 업로드해 주세요.'}
          </span>
        )}
      </div>

      <div className="flex shrink-0 flex-wrap items-center gap-2">
        {/*
          자산 단위 내려받기 — 준비 완료 자산에만 둔다. 그 전 상태에는 내보낼 라벨도, 라벨링을
          거친 결과도 없다(옮겨 오기 전 라벨링 화면도 준비 완료 자산에서만 열렸다).
          행이 여럿이라 **접근 이름에 파일명을 붙인다** — 이름 없이 «내보내기» 만 두면 같은 이름의
          버튼이 자산 수만큼 생겨 보조기술 사용자가 어느 자산인지 가릴 수 없다(삭제 버튼과 같은 관례).
        */}
        {isReady && (
          <>
            <button
              type="button"
              onClick={exportLabels}
              disabled={downloading !== null}
              aria-label={`${upload.orgnlFileNm} 내보내기(JSON)`}
              className={cn(
                'inline-flex items-center gap-1 rounded-lg border border-gray-300 px-3 py-1.5 text-sub font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-50',
                KRDS_FOCUS,
              )}
            >
              {/* 두 버튼 모두 '내려받기'라 같은 아이콘을 쓴다 — 구분은 라벨이 한다. */}
              <Download className="h-3.5 w-3.5" aria-hidden />
              내보내기(JSON)
            </button>
            <button
              type="button"
              onClick={downloadFile}
              disabled={downloading !== null}
              aria-label={`${upload.orgnlFileNm} 원본 다운로드`}
              /* 진행 사실은 보조기술에도 전달한다. 원본은 최대 5GB 라 오래 걸릴 수 있어
                 «눌렸는데 아무 일도 없다» 로 보이면 안 된다. */
              aria-busy={downloading === 'file' || undefined}
              className={cn(
                'inline-flex items-center gap-1 rounded-lg border border-gray-300 px-3 py-1.5 text-sub font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-50',
                KRDS_FOCUS,
              )}
            >
              <Download className="h-3.5 w-3.5" aria-hidden />
              원본 다운로드
            </button>
            {/* 취소는 **원본을 내려받는 동안에만** 나타난다. 내보내기(JSON)에는 두지 않는다 —
                작아서 이 버튼이 뜨기 전에 끝난다.
                ⚠ 진행 중 상호 비활성 대상에서 제외된다 — 취소는 눌러야 동작한다. */}
            {downloading === 'file' && (
              <button
                type="button"
                onClick={cancelFileDownload}
                aria-label={`${upload.orgnlFileNm} 원본 다운로드 취소`}
                className={cn(
                  'inline-flex items-center gap-1 rounded-lg border border-gray-300 px-3 py-1.5 text-sub font-medium text-gray-700 transition-colors hover:bg-gray-50',
                  KRDS_FOCUS,
                )}
              >
                <X className="h-3.5 w-3.5" aria-hidden />
                원본 다운로드 취소
              </button>
            )}
          </>
        )}
        {canMark && (
          <Link
            /* 마킹 화면으로 들어가는 자리는 이 목록뿐이다 — 라벨링 화면에는 두지 않는다.
               주소 조립은 `markingPath` 한 곳이 한다(문자열을 여기 흩지 않는다). */
            to={buildPortalUploadMarkingPath(upload.uldSn)}
            aria-label={`${upload.orgnlFileNm} 마킹`}
            className={cn(
              'inline-flex items-center rounded-lg bg-primary-600 px-3 py-1.5 text-sub font-medium text-white transition-colors hover:bg-primary-700',
              KRDS_FOCUS,
            )}
          >
            마킹
          </Link>
        )}
        {isReady && (
          <Link
            /* 통합 라벨링 화면으로 보낸다 — 업로드 자산 전용 라벨링 화면은 폐기됐다.
               주소 조립은 `labelingEntry` 한 곳이 한다(문자열을 여기 흩지 않는다). */
            to={buildPortalUploadLabelPath(upload.uldSn)}
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
