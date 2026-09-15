// 포털 업로드 화면 (PORTAL_USER, ADR-013 예외 = 포털 자체 업로드 자산).
//
// ★ 신규 접수는 **영상뿐**이다. 이미지 접수 자리는 폐기됐다(되살리지 말 것) — 자산 종류 값역에
//   이미지가 남아 있는 것은 이미 적재된 행을 읽기 위해서이지 접수 수단이 있다는 뜻이 아니다.
//
// ★★ 모양은 **포털 저작도구 화면이 정본**이다 — 포털 저장소의 부품(`@portal/components/custom` ·
//    KRDS 킷)으로 짓고, 짜임·문구는 포털 화면 스토리북 「워크스페이스 / 저작도구 / 내 업로드」
//    (KLID_Portal `AuthoringUploadsView`)와 같게 둔다. 올리기 · 목록 · 삭제 · 받기 · 증강 요청 흐름은
//    이 화면의 것 그대로다.
//    · 목록은 줄 카드다 — 삭제 · 받기는 카드 맨 아래 줄 왼쪽, 걸음 버튼은 같은 줄 오른쪽 끝
//    · 줄에서 할 수 없는 걸음은 세우지 않는다(처리 중 줄의 삭제만 잠근다)
//    · 삭제 확인은 브라우저 기본 창이 아니라 포털 확인 창이다
//    · 「증강 요청 현황·결과」 바로가기는 목록이 비었거나 못 불러와도 늘 선다
//
// 보안: 사용자 파일명은 JSX 텍스트 노드로만 렌더(자동 escape, XSS 방어). URL 은 apiClient baseURL.
//
// @design SCREEN-033
// @design DS-002

import { useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Badge, Button, FileUpload } from 'krds-react';
import { CircleAlert, Download, FileJson, Inbox, RotateCcw, Trash2, Upload, X } from 'lucide-react';

import {
  Alert,
  Dialog,
  EmptyState,
  MoreLink,
  PageNav,
  ProgressBar,
  RecordRow,
  ResultCount,
  StepHeading,
  Toaster,
  useToasts,
} from '@portal/components/custom';
import { ConditionChips } from '@portal/pages/workspace/authoring/ConditionChips';
import { NoteList } from '@portal/pages/workspace/authoring/NoteList';
import { formatPortalDateTime } from '@/features/portal/formatDateTime';
import { ApiError } from '@/lib/api/errors';
import { ErrorCode } from '@/lib/api/types';
import { useTusUpload } from '@/features/upload/hooks/useTusUpload';
import { buildPortalUploadLabelPath } from '@/features/portal/labelingEntry';
import { buildPortalUploadMarkingPath } from '@/features/portal/uploads/markingPath';
import { formatExpiryDate } from '@/features/portal/expiry';
import { downloadUploadExport, downloadUploadFile } from '@/features/portal/uploads/api';
import { usePortalUploads } from '@/features/portal/uploads/hooks/usePortalUploads';
import { useDeleteUpload } from '@/features/portal/uploads/hooks/useDeleteUpload';
import { useRequestUploadAugment } from '@/features/portal/uploads/hooks/useRequestUploadAugment';
import { AugmentRequestModal } from '@/features/portal/uploads/components/AugmentRequestModal';
import type { RequestUploadAugmentBody } from '@/features/portal/uploads/api';
import {
  PortalUploadStatus,
  PortalUploadType,
  type PortalUpload,
} from '@/features/portal/uploads/types';

const PORTAL_TUS_ENDPOINT = '/portal/uploads/tus';

/** 목록 한 페이지 건수. */
const PAGE_SIZE = 20;

/** 포털 영상 허용 확장자(BE 와 동일: mp4/mov/avi). 1차 가드 — 최종 검증은 서버. */
const VIDEO_EXTENSIONS = ['mp4', 'mov', 'avi'];

/** 영상 한 편 상한 5GB — 업로드 칸의 기본 상한(20MB)을 이 값으로 연다. 최종 검증은 서버. */
const VIDEO_MAX_BYTES = 5 * 1024 * 1024 * 1024;

/** 상태 배지 이름 · 색 — 끝난 것 초록 · 아직 도는 것 주황 · 기다리는 것 회색 · 실패 빨강 */
const STATUS_BADGE: Record<string, { label: string; color: 'success' | 'warning' | 'gray' | 'danger' }> = {
  [PortalUploadStatus.UPLOADED]: { label: '마킹 대기', color: 'gray' },
  [PortalUploadStatus.PROCESSING]: { label: '처리 중', color: 'warning' },
  [PortalUploadStatus.READY]: { label: '준비 완료', color: 'success' },
  [PortalUploadStatus.FAILED]: { label: '실패', color: 'danger' },
};

/** 파일 이름 아래 한 줄 — 지금 이 자산이 어디쯤인지 (준비 완료에는 없다) */
const STATUS_HINT: Record<string, string> = {
  [PortalUploadStatus.UPLOADED]: '마킹을 마치면 그 지점으로 프레임을 뽑습니다',
  [PortalUploadStatus.PROCESSING]: '프레임을 뽑는 중입니다',
  [PortalUploadStatus.FAILED]: '지운 뒤 다시 올려 주세요',
};

/**
 * 바이트 → 사람이 읽는 크기. KB 미만은 1 KB 로 올린다(0 KB 는 «빈 파일» 로 오독된다).
 */
function formatSize(bytes: number): string {
  const KB = 1024;
  const MB = KB * 1024;
  const GB = MB * 1024;
  if (bytes >= GB) return `${(bytes / GB).toFixed(2)} GB`;
  if (bytes >= MB) return `${(bytes / MB).toFixed(1)} MB`;
  return `${Math.max(1, Math.round(bytes / KB))} KB`;
}

/**
 * 삭제 실패 사유. 처리 중 자산은 서버가 409 로 막는다 — 그 사유는 따로 말한다.
 */
function deleteErrorMessage(error: unknown): string {
  if (error instanceof ApiError && (error.status === 409 || error.errorCode === ErrorCode.CONFLICT)) {
    return '처리 중 자산은 삭제할 수 없습니다.';
  }
  return '삭제에 실패했습니다. 잠시 후 다시 시도해 주세요.';
}

/**
 * 증강 요청 실패 사유 — 서버가 준 사용자 문구를 우선한다(목록에 없는 값 등).
 */
function augmentRequestErrorMessage(error: unknown): string | null {
  if (error == null) return null;
  if (error instanceof ApiError) return error.userMessage;
  return '증강 요청에 실패했습니다. 잠시 후 다시 시도해 주세요.';
}

export function PortalUploadPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const uploadsQuery = usePortalUploads({ page, size: PAGE_SIZE });
  const deleteUpload = useDeleteUpload();
  const toasts = useToasts();
  const { downloading, exportLabels, downloadFile, cancelFileDownload } = useUploadDownloads(
    (message) => toasts.push({ tone: 'danger', message }),
  );

  const uploads: PortalUpload[] = useMemo(
    () => uploadsQuery.data?.content ?? [],
    [uploadsQuery.data],
  );
  const totalPages = uploadsQuery.data?.totalPages ?? 0;
  const totalElements = uploadsQuery.data?.totalElements ?? uploads.length;
  const listReady = !uploadsQuery.isLoading && !uploadsQuery.isError;

  // ── 영상 업로드 (TUS — 끊겨도 같은 파일을 다시 고르면 이어서 올린다) ──
  const tus = useTusUpload({ endpointBase: PORTAL_TUS_ENDPOINT });
  const [videoFile, setVideoFile] = useState<File | null>(null);
  const videoPercent = Math.round(tus.progress * 100);
  const uploading = tus.status === 'uploading';
  const interrupted = tus.error != null;

  const onVideoStart = () => {
    if (!videoFile) return;
    void tus.start(videoFile, { filename: videoFile.name }).catch(() => undefined);
  };

  /* 고른 파일은 킷의 파일 줄이 받는다 — 한 번에 한 편이라 줄은 하나뿐이다.
     올리는 중에는 지울 수 없다(×가 서지 않는다). 끊겼거나 끝났으면 다시 지우고 고를 수 있다 */
  const fileStatus = uploading
    ? 'uploading'
    : interrupted
      ? 'error'
      : tus.status === 'completed'
        ? 'completed'
        : 'ready';

  // ── AI 증강 요청 ──
  const [augmentTarget, setAugmentTarget] = useState<PortalUpload | null>(null);
  const requestAugment = useRequestUploadAugment();

  const openAugmentForm = (uld: PortalUpload) => {
    requestAugment.reset();
    setAugmentTarget(uld);
  };
  const closeAugmentForm = () => {
    setAugmentTarget(null);
    requestAugment.reset();
  };
  const submitAugment = (body: RequestUploadAugmentBody) => {
    const target = augmentTarget;
    if (target === null) return;
    void requestAugment
      .requestAsync({ uldSn: target.uldSn, body })
      .then(() => {
        setAugmentTarget(null);
        toasts.push({
          tone: 'success',
          message: '증강 요청을 접수했습니다. 진행 상태는 「증강 요청 현황·결과」에서 확인하세요.',
        });
      })
      // 거부되면 창을 닫지 않는다 — 사유가 창 안 안내 띠에 뜨고 입력이 남는다.
      .catch(() => undefined);
  };

  // ── 삭제 — 되돌릴 수 없는 걸음이라 포털 확인 창으로 한 번 더 묻는다 ──
  const [deleting, setDeleting] = useState<PortalUpload | null>(null);
  const confirmDelete = () => {
    const target = deleting;
    setDeleting(null);
    if (target === null) return;
    // 실패는 목록 위 띠가 말한다(`deleteUpload.error`).
    void deleteUpload.deleteAsync(target.uldSn).catch(() => undefined);
  };

  const openAugmentTab = () => navigate('/portal/augment');

  return (
    <section className="klid-authoring-pane" aria-labelledby="portal-uploads-title">
      <div className="klid-authoring-block">
        <StepHeading
          size="md"
          id="portal-uploads-title"
          title="포털 업로드"
          desc="본인이 가진 영상을 올리고, 프레임 준비가 끝나면 직접 라벨링합니다."
        />
        {/* "이 경로가 무엇이 아닌지" 를 올리기 전에 먼저 알린다 — 읽고 넘어가야 하는 사정이라 코발트다.
            늘 서 있는 안내라 읽어 주지 않는다 */}
        <Alert tone="primary" live="none" title="여기에 올린 자산은 본인만 볼 수 있습니다.">
          <Alert.Line>
            자동 라벨링·검수·버전 관리를 거치지 않고, 다른 학습데이터와도 섞이지 않습니다.
          </Alert.Line>
          <Alert.Line>
            영상은 마킹을 마쳐야 그 지점으로 프레임을 뽑고, 준비가 끝나야 라벨링할 수 있습니다.
          </Alert.Line>
        </Alert>
      </div>

      {/* ── 영상 업로드 ── */}
      <section className="klid-authoring-block" aria-labelledby="portal-upload-title">
        <StepHeading size="sm" id="portal-upload-title" title="영상 업로드" />
        {/* 끊김 안내는 구획 제목 바로 아래 — 막대가 멈춘 자리에서 위험 색이 되는 것은 카드 안 막대가 말한다 */}
        {interrupted && (
          <Alert tone="danger" title="전송이 끊겼습니다">
            보낸 만큼은 남아 있습니다. 같은 파일을 다시 골라 이어서 올려 주세요.
          </Alert>
        )}
        <div className="klid-section-card">
          {/* 폼 안 업로드 칸이라 `.klid-file-inline` 변형을 쓴다(포털 문의하기 첨부와 같은 짜임).
              킷 uploadText 는 문자열 자리라 타입만 넓혀 꽂는다 */}
          <div className="klid-file-inline">
            <FileUpload
              title="영상 파일"
              uploadText={
                (
                  <>
                    영상을 끌어다 놓거나 파일선택 버튼을 눌러 주세요
                    <span className="klid-file-upload-hint">
                      mp4·mov·avi 파일을 5GB까지 올릴 수 있습니다. 연결이 끊겨도 이어서 올립니다.
                    </span>
                  </>
                ) as unknown as string
              }
              maxFiles={1}
              maxFileSize={VIDEO_MAX_BYTES}
              acceptedFileTypes={VIDEO_EXTENSIONS}
              disabled={uploading}
              files={
                videoFile
                  ? [
                      {
                        id: 'portal-upload-video',
                        name: videoFile.name,
                        size: videoFile.size,
                        type: videoFile.type || (videoFile.name.split('.').pop()?.toLowerCase() ?? ''),
                        status: fileStatus,
                        deletable: !uploading,
                      },
                    ]
                  : []
              }
              /* 고르는 순간 보내지 않는다 — 올리기는 아래 버튼이 시작한다. 킷은 이 함수를 «고른 파일
                 넘겨받기» 로만 쓴다 */
              onFileUpload={async (file: File) => {
                setVideoFile(file);
              }}
              onFileDelete={() => setVideoFile(null)}
              onAllFilesDelete={() => setVideoFile(null)}
            />
          </div>

          {/* 보낸 만큼 — 한 번이라도 올리기를 시작한 뒤에만. 끊기면 막대가 멈춘 자리에서 위험 색이 된다 */}
          {tus.totalBytes > 0 && (
            <ProgressBar
              className="klid-authoring-upload-progress"
              value={videoPercent}
              tone={interrupted ? 'danger' : 'default'}
              caption={`${formatSize(tus.totalBytes * tus.progress)} / ${formatSize(tus.totalBytes)} 보냈습니다`}
              showValue
              label="영상 올리는 중"
              hint={
                uploading
                  ? '올리는 중에는 멈출 수 없습니다. 다만 보낸 만큼은 남아 있어, 연결이 끊겨도 같은 파일을 다시 고르면 이어서 올립니다.'
                  : undefined
              }
            />
          )}

          {/* 고른 영상이 없거나 올리는 중이면 누를 것이 없다 — 잠가 둔다.
              올리는 중에는 버튼 글이 진행률을 말한다(막대가 스크롤로 가려져도 읽힌다) */}
          <div className="klid-authoring-upload-actions">
            <Button size="medium" disabled={!videoFile || uploading} onClick={onVideoStart}>
              <Upload aria-hidden />
              {uploading ? `업로드 중 ${videoPercent}%` : '영상 업로드'}
            </Button>
          </div>
          <NoteList
            className="klid-authoring-upload-notes"
            items={['한 번에 한 편씩 올립니다.', '다 올리면 아래 목록에 「마킹 대기」로 나타납니다.']}
          />
        </div>
      </section>

      {/* ── 업로드 자산 ── */}
      <section className="klid-authoring-block" aria-labelledby="portal-assets-title">
        {/* 「증강 요청 현황·결과」 는 증강 탭으로 건너뛰는 길이라 바로가기 모양이다. 목록이 있으면 건수와
            한 줄 오른쪽 끝에, 없으면(불러오는 중 · 오류 · 0건) 제목 줄 오른쪽에 선다 */}
        <StepHeading
          size="sm"
          id="portal-assets-title"
          title="업로드 자산"
          aside={
            !(listReady && uploads.length > 0) && (
              <MoreLink label="증강 요청 현황·결과" jump onClick={openAugmentTab} />
            )
          }
        />

        {uploadsQuery.isLoading && <EmptyState busy title="업로드 자산을 불러오고 있습니다." />}

        {/* 못 불러온 것은 0건과 다르게 보여야 한다 — 「내 자산이 사라졌다」로 읽히지 않게 */}
        {uploadsQuery.isError && (
          <EmptyState
            icon={CircleAlert}
            title="목록을 불러올 수 없습니다"
            desc="잠시 후 다시 시도해 주세요. 올린 자산이 사라진 것은 아닙니다."
            action={
              <Button variant="secondary" size="medium" onClick={() => void uploadsQuery.refetch()}>
                <RotateCcw aria-hidden />
                다시 시도
              </Button>
            }
          />
        )}

        {listReady && uploads.length === 0 && (
          <EmptyState
            icon={Inbox}
            title="아직 올린 자산이 없습니다."
            desc="위에서 영상을 올리면 여기에 쌓입니다."
          />
        )}

        {listReady && uploads.length > 0 && (
          <div className="klid-authoring-assets">
            <div className="klid-result-head">
              <ResultCount total={totalElements} />
              <MoreLink label="증강 요청 현황·결과" jump onClick={openAugmentTab} />
            </div>

            {/* 삭제가 거부되면 목록 위에 남는다 (다음 삭제를 시도할 때까지) */}
            {deleteUpload.error != null && (
              <Alert tone="danger" title={deleteErrorMessage(deleteUpload.error)} />
            )}

            <ul className="klid-authoring-records" aria-label="업로드 자산 목록" data-testid="portal-upload-list">
              {uploads.map((u) => {
                const isReady = u.uldSttsCd === PortalUploadStatus.READY;
                const isFailed = u.uldSttsCd === PortalUploadStatus.FAILED;
                const isProcessing = u.uldSttsCd === PortalUploadStatus.PROCESSING;
                const isVideo = u.uldTypeCd === PortalUploadType.VIDEO;
                const canMark = u.uldSttsCd === PortalUploadStatus.UPLOADED && isVideo;
                const canRequestAugment = isReady && isVideo;
                const expiresOn = formatExpiryDate(u.expiresAt);
                const busy = downloading[u.uldSn];
                const badge = STATUS_BADGE[u.uldSttsCd] ?? { label: u.uldSttsCd, color: 'gray' as const };

                return (
                  <li key={u.uldSn} data-testid={`portal-upload-item-${u.uldSn}`}>
                    <RecordRow
                      title={u.orgnlFileNm}
                      titleSize="large"
                      factsInline
                      factsBelow
                      actionRow
                      /* 실패 사유는 오른쪽 위 — 서버가 준 사유를 먼저 쓴다 */
                      actionNote={
                        isFailed && (u.failRsnCn ?? '처리에 실패했습니다. 다시 업로드해 주세요.')
                      }
                      hint={STATUS_HINT[u.uldSttsCd]}
                      badge={
                        <Badge variant="light" color={badge.color} className="klid-badge-tint">
                          {badge.label}
                        </Badge>
                      }
                      facts={[
                        `올린 일시 ${formatPortalDateTime(u.regDt)}`,
                        /* 처리 중에는 만료일이 아직 없다 — 「-」로 채우지 않고 줄을 비운다 */
                        ...(expiresOn !== null ? [`만료 ${expiresOn}`] : []),
                      ]}
                      action={
                        /* 줄에서 할 수 없는 걸음은 세우지 않는다. AI 증강은 창을 여는 보조 걸음이라 라벨링 앞에 선다 */
                        (canMark || isReady) && (
                          <div className="klid-authoring-row-actions" data-align="end">
                            {canMark && (
                              <Button
                                size="small"
                                aria-label={`${u.orgnlFileNm} 마킹`}
                                onClick={() => navigate(buildPortalUploadMarkingPath(u.uldSn))}
                              >
                                마킹
                              </Button>
                            )}
                            {canRequestAugment && (
                              <Button
                                size="small"
                                variant="secondary"
                                aria-label={`${u.orgnlFileNm} AI 증강 요청`}
                                onClick={() => openAugmentForm(u)}
                              >
                                AI 증강
                              </Button>
                            )}
                            {isReady && (
                              <Button
                                size="small"
                                aria-label={`${u.orgnlFileNm} 라벨링`}
                                onClick={() => navigate(buildPortalUploadLabelPath(u.uldSn))}
                              >
                                라벨링
                              </Button>
                            )}
                          </div>
                        )
                      }
                      /* 삭제 · 받기는 맨 아래 줄 왼쪽 — 삭제가 맨 왼쪽. 원본은 오래 걸릴 수 있어
                         받는 동안은 같은 자리가 「원본 다운로드 취소」로 바뀐다 */
                      actionStart={
                        <>
                          {/* 프레임을 뽑는 중에는 지울 수 없다 — 준비가 끝나거나 실패로 마무리되면 지운다 */}
                          <Button
                            size="small"
                            variant="text"
                            className="klid-btn-danger-text"
                            disabled={isProcessing || deleteUpload.isPending}
                            aria-label={`${u.orgnlFileNm} 삭제`}
                            onClick={() => setDeleting(u)}
                          >
                            <Trash2 aria-hidden />
                            삭제
                          </Button>
                          {isReady && (
                            <>
                              <Button
                                size="small"
                                variant="text"
                                disabled={busy !== undefined}
                                aria-label={`${u.orgnlFileNm} 라벨 내보내기`}
                                onClick={() => exportLabels(u)}
                              >
                                <FileJson aria-hidden />
                                라벨 내보내기
                              </Button>
                              {busy === 'file' ? (
                                <Button
                                  size="small"
                                  variant="text"
                                  className="klid-btn-danger-text"
                                  aria-label={`${u.orgnlFileNm} 원본 다운로드 취소`}
                                  onClick={() => cancelFileDownload(u.uldSn)}
                                >
                                  <X aria-hidden />
                                  원본 다운로드 취소
                                </Button>
                              ) : (
                                <Button
                                  size="small"
                                  variant="text"
                                  disabled={busy !== undefined}
                                  aria-label={`${u.orgnlFileNm} 원본 다운로드`}
                                  onClick={() => downloadFile(u)}
                                >
                                  <Download aria-hidden />
                                  원본 다운로드
                                </Button>
                              )}
                            </>
                          )}
                        </>
                      }
                    >
                      {/* 자산 정보 — 이름은 흐리게 · 값은 굵게, 사이는 세로선. 프레임은 뽑은 뒤에만 */}
                      <ConditionChips
                        look="text"
                        label="자산 정보"
                        items={[
                          { label: '유형', value: isVideo ? '영상' : u.uldTypeCd },
                          { label: '크기', value: formatSize(u.fileSz) },
                          ...(u.frmeCnt != null && u.frmeCnt > 0
                            ? [{ label: '프레임', value: String(u.frmeCnt) }]
                            : []),
                        ]}
                      />
                    </RecordRow>
                  </li>
                );
              })}
            </ul>
            {/* 전체가 한 쪽에 들어오면 쪽 넘김을 그리지 않는다. 쪽은 0부터, 쪽 넘김 줄은 1부터 센다 */}
            {totalPages > 1 && (
              <PageNav
                totalPages={totalPages}
                currentPage={page + 1}
                onChange={(next: number) => setPage(next - 1)}
              />
            )}
          </div>
        )}
      </section>

      {augmentTarget !== null && (
        <AugmentRequestModal
          open
          targetName={augmentTarget.orgnlFileNm}
          errorMessage={augmentRequestErrorMessage(requestAugment.error)}
          submitting={requestAugment.isPending}
          onClose={closeAugmentForm}
          onSubmit={submitAugment}
        />
      )}

      {/* 삭제 확인 — 메인은 위험 색 */}
      <Dialog
        open={deleting !== null}
        onOpenChange={(open: boolean) => !open && setDeleting(null)}
        title="자산을 삭제할까요?"
        desc={deleting ? `"${deleting.orgnlFileNm}" 자산을 삭제합니다. 되돌릴 수 없습니다.` : undefined}
        sub={{ label: '취소', close: true }}
        main={{ label: '삭제', tone: 'danger', onClick: confirmDelete }}
      />

      <Toaster items={toasts.items} onDismiss={toasts.dismiss} />
    </section>
  );
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
function useUploadDownloads(notifyError: (message: string) => void) {
  /**
   * 자산별 진행 상태. **한 덩이 상태가 아니라 자산 단위 지도**다 — 페이지로 올리면서도 «A 를
   * 받는 동안 B 도 받을 수 있다» 는 성질을 그대로 지킨다. 하나로 합치면 자산이 서로를 막는다.
   */
  const [downloading, setDownloading] = useState<Record<number, 'export' | 'file' | undefined>>({});
  // 진행 중인 원본 다운로드의 중단 컨트롤러(자산별). 취소 버튼이 이것을 통해 전송을 끊는다.
  const abortRef = useRef<Map<number, AbortController>>(new Map());

  const mark = (sn: number, v: 'export' | 'file' | undefined) =>
    setDownloading((prev) => ({ ...prev, [sn]: v }));

  const exportLabels = (upload: PortalUpload) => {
    if (downloading[upload.uldSn]) return;
    mark(upload.uldSn, 'export');
    downloadUploadExport(upload.uldSn)
      .catch(() => notifyError('내보내기에 실패했습니다.'))
      .finally(() => mark(upload.uldSn, undefined));
  };

  const downloadFile = (upload: PortalUpload) => {
    if (downloading[upload.uldSn]) return;
    const controller = new AbortController();
    abortRef.current.set(upload.uldSn, controller);
    mark(upload.uldSn, 'file');
    // 지도가 아니라 지역 변수를 닫아 쓴다 — 다음 요청이 항목을 덮어써도 이 catch 는 자기 요청의
    // 중단 여부를 본다.
    downloadUploadFile(upload.uldSn, upload.orgnlFileNm, controller.signal)
      .catch(() => {
        if (controller.signal.aborted) return; // 사용자가 스스로 멈춘 것 — 정상 종료
        notifyError('원본 다운로드에 실패했습니다.');
      })
      .finally(() => {
        if (abortRef.current.get(upload.uldSn) === controller) abortRef.current.delete(upload.uldSn);
        mark(upload.uldSn, undefined);
      });
  };

  const cancelFileDownload = (sn: number) => abortRef.current.get(sn)?.abort();

  return { downloading, exportLabels, downloadFile, cancelFileDownload };
}
