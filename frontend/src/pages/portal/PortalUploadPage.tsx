// 포털 업로드 화면 (PORTAL_USER, ADR-013 예외 = 포털 자체 업로드 자산).
//
// ★ 신규 접수는 **영상뿐**이다. 이미지 접수 자리는 폐기됐다(되살리지 말 것) — 자산 종류 값역에
//   이미지가 남아 있는 것은 이미 적재된 행을 읽기 위해서이지 접수 수단이 있다는 뜻이 아니다.
//
// ★★ 이 화면은 **포털 채널 디자인 시스템(DS-002)** 으로 그린다. 쓰는 클래스 이름은 관제와 같고
//    값은 산출 시점에 채널이 정한다(`design-tokens/channel.js`). 부품은 `components/portal/ui/*`
//    포털 전용 계층을 쓴다 — 관제 공통 컴포넌트는 145개 관제 화면이 함께 써서 손댈 수 없다.
//
// ⚠ 시안(SD-026)은 골격 **v11** 기준이라 마킹 단계(2026-09-02 신설) 이전이다. 시각 언어는 시안을
//   따르되 **기능·문구는 골격 v38 이 진실원**이다 — 상태 표기(「마킹 대기」)·마킹 진입·증강 요청
//   폼이 그 차이다. 시안으로 되돌리면 확정 사양이 뒤집힌다.
//
// 보안: 사용자 파일명은 JSX 텍스트 노드로만 렌더(자동 escape, XSS 방어). URL 은 apiClient baseURL.
//
// @design SCREEN-033
// @design DS-002

import { useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { CircleAlert, Download, FileBraces, Inbox, Trash2, Upload, X } from 'lucide-react';

import { Pagination } from '@/components/common/Pagination';
import { PortalAlert } from '@/components/portal/ui/PortalAlert';
import { PortalCard } from '@/components/portal/ui/PortalCard';
import { PortalIconButton } from '@/components/portal/ui/PortalIconButton';
import { formatPortalDateTime } from '@/features/portal/formatDateTime';
import { PortalListSkeleton } from '@/components/portal/ui/PortalListSkeleton';
import {
  PortalFactChip,
  PortalRecordList,
  PortalRecordRow,
} from '@/components/portal/ui/PortalRecordRow';
import { PortalProgress } from '@/components/portal/ui/PortalProgress';
import {
  PortalUploadStatusBadge,
  PortalUploadStatusNote,
} from '@/components/portal/ui/PortalUploadStatusBadge';
import { UploadDropzone } from '@/components/portal/ui/UploadDropzone';
import {
  PORTAL_TILE,
  portalButton,
  portalButtonSm,
} from '@/components/portal/ui/portalControl';
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
/** 포털 영상 허용 확장자(BE 와 동일: mp4/mov/avi). accept 1차 가드 — 최종 검증은 서버. */
const VIDEO_ACCEPT = 'video/mp4,video/quicktime,video/x-msvideo,.mp4,.mov,.avi';
const UPLOAD_HINT = 'mp4 · mov · avi / 최대 5GB / 연결이 끊겨도 이어서 올립니다';
/** 내려받기 버튼이 잠겼을 때의 설명 — 한 자산에서 한 번에 하나만 받는다. */
const DOWNLOAD_LOCKED_TIP = '다른 내려받기가 끝난 뒤에 받을 수 있습니다';
/** 삭제 버튼이 잠겼을 때의 설명 — 추출 중 자산은 서버도 409 로 거부한다. */
const DELETE_LOCKED_TIP =
  '프레임을 뽑는 중에는 지울 수 없습니다. 준비가 끝나거나 실패로 마무리되면 지울 수 있습니다.';

/**
 * 파일 크기 표기 — 영상은 최대 5GB 라 GB 단계까지 올린다.
 * 구 구현은 MB 에서 멈춰 `3358.7MB` 처럼 읽기 어려운 값이 나왔다.
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
 * 삭제 실패 사유를 사용자 문구로 매핑. PROCESSING 자산은 BE 가 409(CONFLICT)로 거부하므로
 * 그 경우만 전용 안내, 그 외는 일반 문구(내부 메시지 미노출 — CWE-209).
 */
function deleteErrorMessage(error: unknown): string {
  if (error instanceof ApiError && (error.status === 409 || error.errorCode === ErrorCode.CONFLICT)) {
    return '처리 중 자산은 삭제할 수 없습니다.';
  }
  return '삭제에 실패했습니다. 잠시 후 다시 시도해 주세요.';
}

/**
 * 접수 창구가 돌려보낸 사유를 창 안 안내 문구로 옮긴다.
 *
 * 이 창구의 오류 메시지는 계약이 **사용자 메시지**로 규정한 값이라 그대로 보인다(내부 예외
 * 클래스명·경로가 아니다). 서버 메시지가 없을 때만 일반 문구로 대신한다 — 지어내지 않는다.
 */
function augmentRequestErrorMessage(error: unknown): string | null {
  if (error == null) return null;
  if (error instanceof ApiError) return error.userMessage;
  return '증강 요청에 실패했습니다. 잠시 후 다시 시도해 주세요.';
}

export function PortalUploadPage() {
  // 목록 페이지는 화면 안에서만 쓰인다(이 화면은 주소로 상태를 나르지 않는다 — 검색·필터가 없다).
  const [page, setPage] = useState(0);
  const uploadsQuery = usePortalUploads({ page, size: PAGE_SIZE });
  const deleteUpload = useDeleteUpload();
  const { downloading, exportLabels, downloadFile, cancelFileDownload } =
    useUploadDownloads();

  const uploads: PortalUpload[] = useMemo(
    () => uploadsQuery.data?.content ?? [],
    [uploadsQuery.data],
  );
  const totalPages = uploadsQuery.data?.totalPages ?? 0;
  const totalElements = uploadsQuery.data?.totalElements ?? uploads.length;

  // ── 영상 TUS 업로드(엔진 재사용, 포털 endpoint 주입) ──
  const tus = useTusUpload({ endpointBase: PORTAL_TUS_ENDPOINT });
  const [videoFile, setVideoFile] = useState<File | null>(null);
  const videoPercent = Math.round(tus.progress * 100);
  const uploading = tus.status === 'uploading';

  const onVideoStart = () => {
    if (!videoFile) return;
    void tus.start(videoFile, { filename: videoFile.name }).catch(() => undefined);
  };

  // ── 증강 요청 ──
  //
  // ★ 버튼 하나로 끝나지 않는다 — 누르면 생성 조건을 입력하는 요청 폼이 **화면 안 창**으로
  //   열리고, 다섯 항목을 모두 고른 뒤에야 요청이 나간다. [@design SCREEN-033] [@design API-231]
  const pushToast = useUiStore((s) => s.pushToast);
  const [augmentTarget, setAugmentTarget] = useState<PortalUpload | null>(null);
  const requestAugment = useRequestUploadAugment();

  const openAugmentForm = (uld: PortalUpload) => {
    // 앞선 시도의 거부 사유가 다음 창에 남지 않게 한다.
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
        // 응답은 **접수 사실이지 결과가 아니다** — 어디서 결과를 보는지 함께 알린다.
        pushToast({
          variant: 'success',
          message: '증강 요청을 접수했습니다. 진행 상태는 「증강 요청 현황·결과」에서 확인하세요.',
        });
      })
      // 거부 사유는 창 안 안내 자리에 뜬다(mutation error). 창은 닫지 않는다 — 고쳐서 다시
      // 보낼 수 있어야 하고, 닫으면 무엇이 잘못됐는지와 함께 입력이 통째로 사라진다.
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

  const rangeFrom = page * PAGE_SIZE + 1;
  const rangeTo = page * PAGE_SIZE + uploads.length;

  return (
    <div className="flex w-full flex-col gap-card-gap tracking-body">
      {/*
        포털 채널 셸에는 좌측 주 메뉴가 없고 화면 깊이가 얕다 — 빵부스러기를 두지 않는다.
        목록 성격 화면이라 '뒤로가기' 조작도 두지 않는다.
      */}
      <header className="flex flex-col gap-tight">
        <h1 className="text-title-lg text-balance text-gray-900">포털 업로드</h1>
        <p className="text-body-sm text-pretty text-gray-600">
          본인이 가진 영상을 올리고, 프레임 준비가 끝나면 직접 라벨링합니다.
        </p>
      </header>

      {/*
        "이 경로가 무엇이 아닌지" 를 맨 위에서 먼저 알린다. 골격 purpose 의 절반이 **하지 않는
        것**인데 화면에 그 사실이 없으면 이용자는 «올려두면 알아서 라벨이 붙겠지» 로 기다린다.
        ⚠ `live` 를 켜지 않는다 — 늘 서 있는 안내라 진입할 때마다 보조기술에 끼어들면 안 된다.
      */}
      <PortalAlert
        tone="info"
        title="여기에 올린 자산은 본인만 볼 수 있습니다"
        description="자동 라벨링·검수·버전 관리를 거치지 않고, 다른 학습데이터와도 섞이지 않습니다. 영상은 마킹을 마쳐야 그 지점으로 프레임을 뽑고, 준비가 끝나야 라벨링할 수 있습니다."
      />

      {/* ── 영상 업로드 (TUS) ───────────────────────────────────────────── */}
      <PortalCard
        ariaLabel="영상 업로드"
        title="영상 업로드"
        count="한 번에 한 편씩 올립니다"
        bodyClassName="flex flex-col gap-block"
      >
        {videoFile === null ? (
          /*
            고르기 전 — 파선 받침. 파선은 «아직 내용이 놓이지 않은 자리» 를 뜻하는 확정 관례다.
            올리는 중에는 파선을 흐리는 대신 **실선 파일 표시로 자리를 바꾼다**(아래 분기).
          */
          <UploadDropzone
            label="영상 파일"
            accept={VIDEO_ACCEPT}
            hint={UPLOAD_HINT}
            lead="여기로 영상을 끌어다 놓거나 눌러서 고르세요"
            disabled={uploading}
            onFiles={(files) => setVideoFile(files[0] ?? null)}
          />
        ) : (
          <div className="flex flex-col gap-label-gap">
            <span className="text-label text-gray-800">영상 파일</span>
            <div className={cn(PORTAL_TILE, 'flex flex-wrap items-center gap-inline p-dense')}>
              {/* 사용자 파일명 — 텍스트 노드(자동 escape). 좁은 폭에서는 잘리지 않고 줄바꿈한다. */}
              <span className="min-w-0 flex-1 break-all text-body-sm font-medium text-gray-900">
                {videoFile.name}
              </span>
              <span className="text-caption tabular-nums text-gray-600">
                {formatSize(videoFile.size)}
              </span>
              {uploading ? (
                <span className="rounded-pill bg-info-50 px-2.5 py-0.5 text-caption font-medium text-info-700">
                  올리는 중
                </span>
              ) : (
                <button
                  type="button"
                  onClick={() => setVideoFile(null)}
                  aria-label="고른 영상 취소"
                  className={portalButtonSm('ghost', 'px-2')}
                >
                  <X className="size-4" strokeWidth={2} aria-hidden />
                </button>
              )}
            </div>
            <p className="text-caption text-gray-600">{UPLOAD_HINT}</p>
          </div>
        )}

        {tus.totalBytes > 0 && (
          <PortalProgress
            label="영상 업로드 진행률"
            percent={videoPercent}
            failed={tus.error != null}
            caption={`${formatSize(tus.totalBytes * tus.progress)} / ${formatSize(tus.totalBytes)} 보냈습니다`}
            note={
              uploading
                ? '올리는 중에는 멈출 수 없습니다. 다만 보낸 만큼은 남아 있어, 연결이 끊겨도 같은 파일을 다시 고르면 이어서 올립니다.'
                : undefined
            }
          />
        )}

        {tus.error != null && (
          <PortalAlert
            live
            tone="error"
            title="전송이 끊겼습니다"
            description="보낸 만큼은 남아 있습니다. 같은 파일을 다시 골라 이어서 올려 주세요."
          />
        )}

        <div className="flex flex-wrap items-center gap-inline">
          <button
            type="button"
            onClick={onVideoStart}
            disabled={!videoFile || uploading}
            className={portalButton('primary')}
          >
            <Upload className="size-4" strokeWidth={2} aria-hidden />
            {/* 조작 자리에도 진행률을 싣는다 — 스크롤로 막대가 가려져도 상태를 읽을 수 있게. */}
            {uploading ? (
              <>
                업로드 중 <span className="tabular-nums">{videoPercent}%</span>
              </>
            ) : (
              '영상 업로드'
            )}
          </button>
          <p className="text-caption text-pretty text-gray-600">
            다 올리면 아래 목록에 「마킹 대기」로 나타납니다.
          </p>
        </div>
      </PortalCard>

      {/* ── 업로드 자산 목록 ─────────────────────────────────────────────── */}
      <PortalCard
        ariaLabel="업로드 자산 목록"
        title="업로드 자산"
        count={
          uploads.length > 0
            ? `${totalElements}건 중 ${rangeFrom}-${rangeTo} · 페이지당 ${PAGE_SIZE}건`
            : undefined
        }
        action={
          /*
            증강 요청 현황·결과로 가는 진입 — **목록에 한 번만** 둔다(자산별 액션이 아니다).
            요청 이후의 현황·결과 확인·후속 작업·내려받기는 전부 그 화면이 담당한다.
            [@design SCREEN-033] [@design SCREEN-044]
          */
          <Link to="/portal/augment" className={portalButtonSm('secondary')}>
            증강 요청 현황·결과
          </Link>
        }
        bodyClassName="p-0"
      >
        {deleteUpload.error != null && (
          <div className="p-in-component pb-0">
            <PortalAlert live tone="error" title={deleteErrorMessage(deleteUpload.error)} />
          </div>
        )}

        {uploadsQuery.isLoading ? (
          <PortalListSkeleton className="p-in-component" />
        ) : uploadsQuery.isError ? (
          /*
           * 조회 실패는 빈 상태와 반드시 구분한다. React Query 는 실패 시 data 를 undefined 로
           * 두므로 목록이 [] 가 되는데, 그것을 "0건" 으로 그리면 사용자는 **서버 오류를 자기
           * 자산이 사라진 것으로 오해**한다. 문구가 그 오해를 직접 부정한다.
           */
          <div className="p-in-component">
            <PortalAlert
              live
              tone="error"
              title="목록을 불러올 수 없습니다"
              description="잠시 후 다시 시도해 주세요. 올린 자산이 사라진 것은 아닙니다."
              action={
                <button
                  type="button"
                  onClick={() => void uploadsQuery.refetch()}
                  className={portalButtonSm('secondary')}
                >
                  다시 시도
                </button>
              }
            />
          </div>
        ) : uploads.length === 0 ? (
          <div
            role="status"
            className="flex flex-col items-center gap-inline px-in-component py-section text-center"
          >
            <Inbox className="size-10 text-gray-400" strokeWidth={1.5} aria-hidden />
            <p className="text-body-sm font-medium text-gray-800">아직 올린 자산이 없습니다</p>
            <p className="text-caption text-gray-600">위에서 영상을 올리면 여기에 쌓입니다.</p>
          </div>
        ) : (
          /*
            ★★ **표가 아니라 행 카드다 (2026-09-08 반전).** 여덟 열이 요구하는 최소 폭이 본문
              최대 폭(1,200px)을 넘어, 열을 어떻게 나눠도 파일명·상태 부제가 반드시 접히거나
              잘렸다. 시안(SD-026)도 이 목록을 **행**으로 그렸다 — 파일명·크기·상태 표식이
              `flex-wrap` 으로 흐르는 짜임이다. 표는 그 시안에서 이탈한 형태였다.
            ⚠ **말줄임으로 되돌리지 말 것.** 시안이 이 화면에서 그것을 명시적으로 거부한다 —
              *"잘리는 꼬리는 확장자다 … 왜 거부됐는지 화면에서 사라진다"*, 그리고 `title` 보완도
              *"터치 환경이라 hover 툴팁이 뜨지 않고, 게시본 정리기가 지우는 속성 계열"* 이라 쓰지
              않는다. 행 카드는 줄바꿈이 손해가 아니라 자를 이유 자체가 없다.
          */
          <PortalRecordList aria-label="업로드 자산 목록" data-testid="portal-upload-list">
            {uploads.map((u) => {
              const isReady = u.uldSttsCd === PortalUploadStatus.READY;
              const isFailed = u.uldSttsCd === PortalUploadStatus.FAILED;
              // 처리 중 자산은 BE 가 삭제를 409 로 거부하므로 버튼 자체를 비활성화(무반응 방지).
              const isProcessing = u.uldSttsCd === PortalUploadStatus.PROCESSING;
              const isVideo = u.uldTypeCd === PortalUploadType.VIDEO;
              /*
               * 마킹 진입 — 마킹 대기 상태인 **영상** 자산 행에만 둔다.
               * [@design SCREEN-033] [@design SCREEN-045]
               *
               * ★ 노출 규칙은 라벨링 링크와 같다: 그 자산에서 할 수 없는 액션은 비활성으로
               *   두지 않고 아예 노출하지 않는다. 처리중·준비 완료·실패 행에 두면 눌러 봐야
               *   거절되는 자리가 되어 회복 경로를 잘못 안내한다 — 이미 마킹한 자산의 재마킹은
               *   제공하지 않고, 다시 마킹하려면 지우고 다시 올려야 한다.
               * ★ 영상이 아닌 자산에는 두지 않는다 — 이벤트 구간이라는 개념이 없다.
               */
              const canMark = u.uldSttsCd === PortalUploadStatus.UPLOADED && isVideo;
              /*
               * 증강 요청 — **준비 완료된 영상** 자산 행에만 둔다.
               * [@design SCREEN-033] [@design API-231]
               *
               * ⚠ 증강 결과물 행에는 두지 않아야 하는데, 목록 응답에 파생 여부를 가릴 값이
               *   없다. 없는 필드를 지어내지 않는다 — 잘못 눌린 요청은 서버가 판정한다.
               */
              const canRequestAugment = isReady && isVideo;
              const expiresOn = formatExpiryDate(u.expiresAt);
              const busy = downloading[u.uldSn];

              return (
                <li key={u.uldSn}>
                  <PortalRecordRow
                    data-testid={`portal-upload-item-${u.uldSn}`}
                    /* 사용자 파일명 — 텍스트 노드(자동 escape). 자르지 않고 줄바꿈해 **확장자까지**
                       보인다(거부 사유가 «지원하지 않는 형식» 이라 꼬리가 곧 근거다). */
                    title={u.orgnlFileNm}
                    titleAside={<PortalUploadStatusBadge status={u.uldSttsCd} />}
                    body={
                      <>
                        {/* 상태가 알리는 「다음에 무슨 일이 일어나는가」 — 배지 옆이 아니라 아래에
                            둔다. 배지 줄이 길어지면 파일명과 자리를 다툰다. */}
                        <PortalUploadStatusNote status={u.uldSttsCd} />

                        {isFailed && (
                          <span className="flex items-start gap-tight text-body-sm text-pretty text-danger-700">
                            <CircleAlert className="mt-0.5 size-4 shrink-0" strokeWidth={2} aria-hidden />
                            {u.failRsnCn ?? '처리에 실패했습니다. 다시 업로드해 주세요.'}
                          </span>
                        )}

                        {/* 사실 조각은 칩으로 흩는다 — 값마다 따로 읽히고 줄바꿈이 자연스럽다.
                            프레임 수는 아직 없으면 **칩 자체를 두지 않는다**(표와 달리 열이 없어
                            빈 자리가 «밀렸나» 로 읽히지 않는다). */}
                        <span className="flex flex-wrap items-center gap-tight">
                          <PortalFactChip label="유형" value={isVideo ? '영상' : u.uldTypeCd} />
                          <PortalFactChip label="크기" value={formatSize(u.fileSz)} />
                          {u.frmeCnt !== null && u.frmeCnt !== undefined && (
                            <PortalFactChip label="프레임" value={`${u.frmeCnt}`} />
                          )}
                        </span>
                      </>
                    }
                    meta={
                      <>
                        <span>
                          올린 일시{' '}
                          <span className="text-gray-600">{formatPortalDateTime(u.regDt)}</span>
                        </span>
                        {/*
                          만료 예정일 — 날짜까지만 적는다(사양). 값이 비는 것은 **처리 중** 하나이며
                          그때는 자리를 비운다. `-`·`없음` 을 지어내면 만료가 정해졌는데 표기만 빈
                          것으로 읽힌다.
                          ⚠ 표식(`data-testid`)을 **값이 있을 때만** 붙인다 — 빈 자리에까지 표식이
                            남으면 «표기가 있다» 와 «자리가 비었다» 를 가릴 수 없다.
                        */}
                        {expiresOn !== null && (
                          <span data-testid={`portal-upload-expiry-${u.uldSn}`}>
                            만료: {expiresOn}
                          </span>
                        )}
                      </>
                    }
                    actions={
                      <>
                        {canMark && (
                          <Link
                            /* 마킹 화면으로 들어가는 자리는 이 목록뿐이다. 주소 조립은
                               `markingPath` 한 곳이 한다(문자열을 여기 흩지 않는다). */
                            to={buildPortalUploadMarkingPath(u.uldSn)}
                            aria-label={`${u.orgnlFileNm} 마킹`}
                            className={portalButtonSm('primary')}
                          >
                            마킹
                          </Link>
                        )}

                        {isReady && (
                          <Link
                            /* 통합 라벨링 화면으로 보낸다 — 업로드 자산 전용 라벨링 화면은
                               폐기됐다. 주소 조립은 `labelingEntry` 한 곳이 한다. */
                            to={buildPortalUploadLabelPath(u.uldSn)}
                            aria-label={`${u.orgnlFileNm} 라벨링`}
                            className={portalButtonSm('primary')}
                          >
                            라벨링
                          </Link>
                        )}

                        {/*
                          내려받기 둘은 **아이콘만** 둔다 — 한 줄이 조작을 다섯까지 담아 전부 글자로
                          두면 조작 덩어리가 본문을 밀어낸다. 대신 **모양을 가르고**(라벨 JSON = 중괄호
                          문서 · 원본 = 내려받기) 마우스를 올리면 짧은 설명이 뜬다[SCREEN-033].
                          ⚠ 줄이 여럿이라 접근 이름에 파일명을 붙인다 — 이름 없이 두면 같은 이름의
                            버튼이 자산 수만큼 생겨 어느 자산인지 가릴 수 없다.
                        */}
                        {isReady && (
                          <>
                            <PortalIconButton
                              onClick={() => exportLabels(u)}
                              disabled={busy !== undefined}
                              aria-label={`${u.orgnlFileNm} 내보내기(JSON)`}
                              tooltip={busy !== undefined ? DOWNLOAD_LOCKED_TIP : '라벨 JSON 내보내기'}
                            >
                              <FileBraces className="size-4" strokeWidth={2} aria-hidden />
                            </PortalIconButton>
                            <PortalIconButton
                              onClick={() => downloadFile(u)}
                              disabled={busy !== undefined}
                              aria-label={`${u.orgnlFileNm} 원본 다운로드`}
                              /* 진행 사실은 보조기술에도 전달한다. 원본은 최대 5GB 라 오래
                                 걸릴 수 있어 «눌렸는데 아무 일도 없다» 로 보이면 안 된다. */
                              aria-busy={busy === 'file' || undefined}
                              tooltip={busy !== undefined ? DOWNLOAD_LOCKED_TIP : '원본 파일 내려받기'}
                            >
                              <Download className="size-4" strokeWidth={2} aria-hidden />
                            </PortalIconButton>
                            {/*
                              취소는 **원본을 내려받는 동안에만** 나타나고 상호 비활성 대상에서
                              제외된다 — 취소는 눌러야 동작한다.
                              ⚠ 다운로드 버튼을 치우고 그 자리에 넣지 않는다. 자리를 바꾸면
                                커서 아래에서 버튼이 갈려 «한 번 더» 누르려던 손이 취소를 누른다.
                            */}
                            {busy === 'file' && (
                              <PortalIconButton
                                onClick={() => cancelFileDownload(u.uldSn)}
                                aria-label={`${u.orgnlFileNm} 원본 다운로드 취소`}
                                tooltip="내려받기 취소"
                              >
                                <X className="size-4" strokeWidth={2} aria-hidden />
                              </PortalIconButton>
                            )}
                          </>
                        )}

                        {canRequestAugment && (
                          <button
                            type="button"
                            onClick={() => openAugmentForm(u)}
                            aria-label={`${u.orgnlFileNm} AI 증강 요청`}
                            className={portalButtonSm('secondary')}
                          >
                            AI 증강
                          </button>
                        )}

                        {/*
                          잠겼을 때는 설명이 **왜 못 지우는지**를 말한다. 비활성 버튼은 마우스
                          이벤트를 받지 않으므로 감싸는 요소가 설명을 띄운다(`PortalIconButton`).
                          문구는 마크업에 실려 있어 스크린리더도 읽는다.
                        */}
                        <PortalIconButton
                          onClick={() => void onDelete(u)}
                          disabled={deleteUpload.isPending || isProcessing}
                          aria-label={`${u.orgnlFileNm} 삭제`}
                          tone="danger"
                          tooltip={isProcessing ? DELETE_LOCKED_TIP : '삭제'}
                        >
                          <Trash2 className="size-4" strokeWidth={2} aria-hidden />
                        </PortalIconButton>
                      </>
                    }
                  />
                </li>
              );
            })}
          </PortalRecordList>
        )}

        {/* 전체가 한 페이지에 들어오면 페이저를 그리지 않는다. */}
        {totalPages > 1 && (
          <div className="border-t border-gray-200 p-in-component">
            <Pagination page={page} totalPages={totalPages} onChange={setPage} />
          </div>
        )}
      </PortalCard>

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
    </div>
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
function useUploadDownloads() {
  const pushToast = useUiStore((s) => s.pushToast);
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
      .catch(() => pushToast({ variant: 'error', message: '내보내기에 실패했습니다.' }))
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
        pushToast({ variant: 'error', message: '원본 다운로드에 실패했습니다.' });
      })
      .finally(() => {
        if (abortRef.current.get(upload.uldSn) === controller) abortRef.current.delete(upload.uldSn);
        mark(upload.uldSn, undefined);
      });
  };

  const cancelFileDownload = (sn: number) => abortRef.current.get(sn)?.abort();

  return { downloading, exportLabels, downloadFile, cancelFileDownload };
}
