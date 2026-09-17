// 포털 업로드 화면 (PORTAL_USER, ADR-013 예외 = 포털 자체 업로드 자산).
//
// ★ 신규 접수는 **영상뿐**이다. 이미지 접수 자리는 폐기됐다(되살리지 말 것) — 자산 종류 값역에
//   이미지가 남아 있는 것은 이미 적재된 행을 읽기 위해서이지 접수 수단이 있다는 뜻이 아니다.
//
// <h3>모양 — 부모 포털의 저작도구 「내 업로드」 탭을 그대로 입혔다 (2026-09-16)</h3>
// 부모 포털(KLID_Portal)이 이 화면을 **자기 부품으로 다시 그려** 「저작도구 쪽에 넘기는 기준」으로
// 삼았고(`pages/workspace/authoring/AuthoringUploadsView.tsx`), 그 짜임을 여기에 옮겼다.
//   · 판·블록·줄 카드 = `klid-authoring-*` (styles/portal/authoring-layout.css).
//     ⚠ 화면이 그 CSS 를 스스로 import 하지 않는다 — 포털 채널 스타일 로드의 단일 지점은
//     `styles/portalLook.ts` 이고, 화면마다 import 를 흩으면 채널별로 스타일이 갈린다
//     (회귀 가드 `styles/__tests__/bootstrapSingleSource`).
//   · 부품 = 포털 킷(`components/portal/kit`) + 저작도구 조각(`components/portal/authoring`)
//     + KRDS 킷(`krds-react` — 파일 받침·배지·버튼·말풍선)
//   · ⚠ 2026-09-16 — **업로드 받침이 파선 드롭존에서 KRDS 파일 받침으로 바뀌었다.** 구 부품
//     (`UploadDropzone`)과 그 파선 관례 서술은 이 화면에서 폐기다. 파선이 뜻하던 「아직 내용이
//     놓이지 않은 자리」는 받침이 제 빈 상태로 말한다.
//   · ⚠ 2026-09-16 — **삭제 확인이 브라우저 기본 창에서 포털 확인 창으로 바뀌었다**(시안 결정).
//     지우는 대상·되돌릴 수 없다는 사실이 화면 안에서 같은 글꼴·같은 색으로 읽힌다.
//   · ⚠ 2026-09-16 — **내려받기 둘과 삭제가 아이콘 버튼에서 카드 맨 아래 줄 글자 버튼으로**
//     내려왔다. 구 서술 *"한 줄이 조작을 다섯까지 담아 아이콘만 둔다"* 는 걸음 줄이 제 줄로
//     떨어져 나오면서 근거가 사라졌다.
//
// ⚠ 관제 공통 부품(`components/common/*`)을 쓰지 않는다 — 관제 화면 여럿이 함께 쓰므로 포털 모양을
//   넣으면 관제 화면이 같이 바뀐다(사용자 확정 구속: **관제향 화면·컴포넌트 불변**).
//
// ★ **자기 페이지 제목(`h1`)을 두지 않는다** — Host 머리 영역이 서비스 이름을, 본문 상단 이동
//   탭의 활성 항목이 화면 이름을 이미 말한다(SHELL-002).
//
// ★★ 시안이 잃은 접근성은 되살린다 — 잠긴 걸음은 `disabled` 가 아니라 `aria-disabled` 로 두어
//   초점을 남기고(WCAG 2.1.1), 못 누르는 사유를 말풍선(눈)과 화면 밖 글(귀) 두 벌로 둔다.
//
// 보안: 사용자 파일명은 JSX 텍스트 노드로만 렌더(자동 escape, XSS 방어). URL 은 apiClient baseURL.
//
// @design SCREEN-033
// @design DS-002

import { useMemo, useRef, useState, type MouseEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Badge, Button, FileUpload, Tooltip, type BadgeProps, type FileItem } from 'krds-react';
import { ArrowUpRight, CircleAlert, Download, FileBraces, Inbox, RotateCcw, Trash2, Upload } from 'lucide-react';

import {
  Alert,
  Dialog,
  EmptyState,
  PageNav,
  ProgressBar,
  RecordRow,
  ResultCount,
  StepHeading,
} from '@/components/portal/kit';
import { ConditionChips, NoteList } from '@/components/portal/authoring';
import { formatPortalDateTime } from '@/features/portal/formatDateTime';
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
/**
 * 포털 영상 허용 확장자·용량(BE 와 동일: mp4/mov/avi · 5GB). 받침의 1차 가드일 뿐 **최종 검증은
 * 서버**다.
 * ⚠ 용량을 반드시 넘긴다 — KRDS 받침의 기본 상한은 20MB 라, 빼면 정상 영상이 전부 입구에서
 *   거절된다(우리 상한의 0.4%).
 */
const VIDEO_EXTENSIONS = ['mp4', 'mov', 'avi'];
const MAX_VIDEO_BYTES = 5 * 1024 * 1024 * 1024;
const UPLOAD_HINT = 'mp4·mov·avi 파일을 5GB까지 올릴 수 있습니다. 연결이 끊겨도 이어서 올립니다.';
/** 내려받기 버튼이 잠겼을 때의 설명 — 한 자산에서 한 번에 하나만 받는다. */
const DOWNLOAD_LOCKED_TIP = '다른 내려받기가 끝난 뒤에 받을 수 있습니다';
/** 삭제 버튼이 잠겼을 때의 설명 — 추출 중 자산은 서버도 409 로 거부한다. */
const DELETE_LOCKED_TIP =
  '프레임을 뽑는 중에는 지울 수 없습니다. 준비가 끝나거나 실패로 마무리되면 지울 수 있습니다.';
/** 증강 현황으로 건너뛰는 자리 — 요청을 거는 자리는 이 화면의 자산별 걸음이다. */
const AUGMENT_STATUS_PATH = '/portal/augment';
const AUGMENT_STATUS_LABEL = '증강 요청 현황·결과';

/**
 * 상태값 → 배지 색. 문구(`STATUS_LABEL`)와 한 자리에 둔다 — 상태값 자체는 서버 계약이라
 * 바꾸지 않고 표기만 정한다.
 *
 * ★`UPLOADED` 는 「마킹 대기」다 — 마킹을 마쳐야 그 지점으로 프레임이 추출되므로, 이 자리에서
 *  알려야 할 것은 업로드가 끝났다는 사실이 아니라 다음에 무엇을 해야 하는가이다.
 */
const STATUS_LABEL: Record<string, string> = {
  [PortalUploadStatus.UPLOADED]: '마킹 대기',
  [PortalUploadStatus.PROCESSING]: '처리중',
  [PortalUploadStatus.READY]: '준비 완료',
  [PortalUploadStatus.FAILED]: '실패',
};
const STATUS_COLOR: Record<string, NonNullable<BadgeProps['color']>> = {
  [PortalUploadStatus.UPLOADED]: 'gray',
  [PortalUploadStatus.PROCESSING]: 'warning',
  [PortalUploadStatus.READY]: 'success',
  [PortalUploadStatus.FAILED]: 'danger',
};
/** 파일 이름 아래 한 줄 — 지금 이 자산이 어디쯤인지. 할 말이 없는 상태(준비 완료)에는 두지 않는다. */
const STATUS_HINT: Record<string, string> = {
  [PortalUploadStatus.UPLOADED]: '마킹을 마치면 그 지점으로 프레임을 뽑습니다',
  [PortalUploadStatus.PROCESSING]: '프레임을 뽑는 중입니다',
  [PortalUploadStatus.FAILED]: '지운 뒤 다시 올려 주세요',
};

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
  const { downloading, exportLabels, downloadFile, cancelFileDownload } = useUploadDownloads();

  const uploads: PortalUpload[] = useMemo(
    () => uploadsQuery.data?.content ?? [],
    [uploadsQuery.data],
  );
  const totalPages = uploadsQuery.data?.totalPages ?? 0;
  const totalElements = uploadsQuery.data?.totalElements ?? uploads.length;
  const hasRows = !uploadsQuery.isLoading && !uploadsQuery.isError && uploads.length > 0;
  const isEmpty = !uploadsQuery.isLoading && !uploadsQuery.isError && uploads.length === 0;

  // ── 영상 TUS 업로드(엔진 재사용, 포털 endpoint 주입) ──
  const tus = useTusUpload({ endpointBase: PORTAL_TUS_ENDPOINT });
  const [videoFile, setVideoFile] = useState<File | null>(null);
  /** 받침이 입구에서 되돌린 사유(형식·용량). 받침의 제 목록을 우리가 쥐고 있어 여기서 알린다. */
  const [pickError, setPickError] = useState<string | null>(null);
  const videoPercent = Math.round(tus.progress * 100);
  const uploading = tus.status === 'uploading';

  /*
    고른 파일 줄 — 받침이 제 목록을 그리게 두지 않고 **우리가 쥔다.** 한 번에 한 편이라 줄은
    하나뿐이고, 그 줄의 상태는 받침이 아는 「고름·완료」가 아니라 **전송이 어디까지 갔는가**여서
    받침이 스스로 판정할 수 없다(TUS 진행은 우리 훅이 안다).
  */
  const pickedFiles = useMemo<FileItem[]>(() => {
    if (videoFile === null) return [];
    return [
      {
        id: 'portal-upload-video',
        name: videoFile.name,
        size: videoFile.size,
        type: videoFile.name.split('.').pop()?.toLowerCase() ?? '',
        status:
          tus.error !== null
            ? 'error'
            : uploading
              ? 'uploading'
              : tus.status === 'completed'
                ? 'completed'
                : 'ready',
        errorMessage: tus.error ?? undefined,
        // 올리는 중에는 지울 수 없다. 끊겼거나 끝났으면 다시 지우고 고를 수 있다.
        deletable: !uploading,
      },
    ];
  }, [videoFile, uploading, tus.error, tus.status]);

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
  //
  // ⚠ 2026-09-16 — 브라우저 기본 확인 창(`window.confirm`)이 아니라 **포털 확인 창**이다(시안 결정).
  //   되돌릴 수 없는 걸음이라 한 번 더 묻는다는 사실은 그대로이고, 묻는 자리만 화면 안으로 들어왔다.
  const [deleteTarget, setDeleteTarget] = useState<PortalUpload | null>(null);

  const runDelete = (uld: PortalUpload) => {
    void deleteUpload.deleteAsync(uld.uldSn).catch(() => {
      // BE 409(처리중) 등 — mutation 에러로 목록 위 안내 띠에 노출.
    });
  };

  return (
    <section className="klid-authoring-pane" aria-labelledby="portal-upload-title">
      {/*
        ★머리 구성은 형제 화면(내 작업 · 증강)과 같다[SCREEN-033] — 페이지 제목(h1)을 따로 두지
          않고 구역마다 구역 머리(제목 + 한 줄 설명)를 세운다. 화면 이름은 이동 탭이, 서비스
          이름은 Host 머리 영역이 이미 말한다(SHELL-002).
        빵부스러기·'뒤로가기' 조작은 두지 않는다(목록 성격 화면이고 깊이가 얕다).
      */}
      <div className="klid-authoring-block">
        <StepHeading
          size="md"
          id="portal-upload-title"
          title="포털 업로드"
          desc="본인이 가진 영상을 올리고, 프레임 준비가 끝나면 직접 라벨링합니다."
        />
        {/*
          "이 경로가 무엇이 아닌지" 를 먼저 알린다. 골격 purpose 의 절반이 **하지 않는 것**인데
          화면에 그 사실이 없으면 이용자는 «올려두면 알아서 라벨이 붙겠지» 로 기다린다.
          ★코발트(primary)다 — 읽고 넘어가야 하는 이 화면의 사정이라 회색으로 물러나게 두지 않는다.
          ⚠ `live` 를 끈다 — 늘 서 있는 안내라 진입할 때마다 보조기술에 끼어들면 안 된다.
        */}
        <Alert tone="primary" live="none" title="여기에 올린 자산은 본인만 볼 수 있습니다.">
          <Alert.Line>
            자동 라벨링·검수·버전 관리를 거치지 않고, 다른 학습데이터와도 섞이지 않습니다.
          </Alert.Line>
          <Alert.Line>
            영상은 마킹을 마쳐야 그 지점으로 프레임을 뽑고, 준비가 끝나야 라벨링할 수 있습니다.
          </Alert.Line>
        </Alert>
      </div>

      {/* ── 영상 업로드 (TUS) ───────────────────────────────────────────── */}
      <section className="klid-authoring-block" aria-labelledby="portal-upload-new">
        <StepHeading size="sm" id="portal-upload-new" title="영상 업로드" />

        {/* 끊김 안내는 구획 제목 바로 아래 — 이 화면의 다른 안내 띠와 같은 자리다. 막대가 멈춘
            자리에서 위험 색이 되는 것은 카드 안 막대가 그대로 말한다. */}
        {tus.error !== null && (
          <Alert tone="danger" title="전송이 끊겼습니다">
            보낸 만큼은 남아 있습니다. 같은 파일을 다시 골라 이어서 올려 주세요.
          </Alert>
        )}

        {/* 받침이 입구에서 되돌린 건 — 형식·용량이 맞지 않으면 전송을 시작하지도 못한다. */}
        {pickError !== null && <Alert tone="danger" title={pickError} />}

        <div className="klid-section-card">
          {/*
            폼 안 업로드 칸이라 `.klid-file-inline` 변형을 쓴다. 래퍼는 받침이 아니라 **감싸는
            요소**에 건다 — 테마 CSS 가 `.klid-file-inline .krds-file-upload` 로 조준한다.
            ⚠ 받침의 `uploadText` 는 문자열 자리라 타입만 넓혀 조각을 꽂는다(스킨은 테마 CSS).
          */}
          <div className="klid-file-inline">
            <FileUpload
              title="영상 파일"
              uploadText={
                (
                  <>
                    영상을 끌어다 놓거나 파일선택 버튼을 눌러 주세요
                    <span className="klid-file-upload-hint">{UPLOAD_HINT}</span>
                  </>
                ) as unknown as string
              }
              maxFiles={1}
              maxFileSize={MAX_VIDEO_BYTES}
              acceptedFileTypes={VIDEO_EXTENSIONS}
              disabled={uploading}
              files={pickedFiles}
              /* 받침이 실제 파일을 넘겨주는 자리는 여기뿐이다 — 목록 변경 콜백은 표시용 항목만
                 나른다(원본 `File` 이 없다). 형식·용량이 맞지 않으면 아예 불리지 않는다. */
              onFileUpload={async (file) => {
                setPickError(null);
                setVideoFile(file);
              }}
              /* 되돌린 건을 잡는다 — 우리가 목록을 쥐고 있어 받침이 제 자리에서 사유를 못 보인다. */
              onFilesChange={(items) => {
                const rejected = items.find((it) => it.status === 'error' && it.id !== 'portal-upload-video');
                if (rejected) setPickError(rejected.errorMessage ?? '이 파일은 올릴 수 없습니다.');
              }}
              onFileDelete={() => {
                setVideoFile(null);
                setPickError(null);
              }}
              onAllFilesDelete={() => {
                setVideoFile(null);
                setPickError(null);
              }}
            />
          </div>

          {/* 보낸 만큼 — 한 번이라도 올리기를 시작한 뒤에만. 끊기면 막대가 멈춘 자리에서 위험 색이 된다. */}
          {tus.totalBytes > 0 && (
            <ProgressBar
              className="klid-authoring-upload-progress"
              label="영상 업로드 진행률"
              value={videoPercent}
              tone={tus.error !== null ? 'danger' : 'default'}
              caption={`${formatSize(tus.totalBytes * tus.progress)} / ${formatSize(tus.totalBytes)} 보냈습니다`}
              showValue
              hint={
                uploading
                  ? '올리는 중에는 멈출 수 없습니다. 다만 보낸 만큼은 남아 있어, 연결이 끊겨도 같은 파일을 다시 고르면 이어서 올립니다.'
                  : undefined
              }
            />
          )}

          {/* 고른 영상이 없거나 올리는 중이면 누를 것이 없다 — 잠가 둔다(사유가 따로 없는 잠김이라
              네이티브 잠금으로 족하다. 못 누르는 까닭은 받침의 빈 상태·막대가 이미 말한다).
              올리는 중에는 버튼 글이 진행률을 말한다 — 막대가 스크롤로 가려져도 읽힌다. */}
          <div className="klid-authoring-upload-actions">
            <Button size="medium" disabled={!videoFile || uploading} onClick={onVideoStart}>
              <Upload aria-hidden />
              {uploading ? (
                <>
                  업로드 중 <span className="tabular-nums">{videoPercent}%</span>
                </>
              ) : (
                '영상 업로드'
              )}
            </Button>
          </div>

          {/* 올리는 규칙과 올린 뒤에 일어나는 일은 카드 맨 아래 안내 사항으로 모은다 —
              누르고 나서야 목록이 어디에 생기는지 찾게 하지 않는다. */}
          <NoteList
            className="klid-authoring-upload-notes"
            items={['한 번에 한 편씩 올립니다.', '다 올리면 아래 목록에 「마킹 대기」로 나타납니다.']}
          />
        </div>
      </section>

      {/* ── 업로드 자산 목록 ─────────────────────────────────────────────── */}
      <section className="klid-authoring-block" aria-labelledby="portal-upload-list">
        {/*
          「증강 요청 현황·결과」는 조작이 아니라 증강 화면으로 건너뛰는 길이라 바로가기 모양이다.
          목록이 비었거나 못 불러와도 **늘 선다** — 목록이 있으면 건수와 한 줄 오른쪽 끝에,
          없으면(불러오는 중 · 오류 · 0건) 제목 줄 오른쪽에. 둘 중 **한 자리에만** 서야 같은
          접근 이름의 링크가 한 화면에 둘이 되지 않는다. [@design SCREEN-033] [@design SCREEN-044]
        */}
        <StepHeading
          size="sm"
          id="portal-upload-list"
          title="업로드 자산"
          aside={!hasRows ? <AugmentStatusLink /> : undefined}
        />

        {/* 삭제가 거부되면 목록 위에 남는다(다음 삭제를 시도할 때까지). 목록 갈래 밖에 두어
            목록이 비었거나 못 불러온 상태에서도 사유가 사라지지 않게 한다. */}
        {deleteUpload.error != null && (
          <Alert tone="danger" title={deleteErrorMessage(deleteUpload.error)} />
        )}

        {/* 불러오는 동안 목록이 올 자리를 그대로 지킨다 — 비워 두면 실패한 것으로 읽힌다. */}
        {uploadsQuery.isLoading && <EmptyState busy title="업로드 자산을 불러오고 있습니다." />}

        {/*
         * 조회 실패는 빈 상태와 반드시 구분한다. React Query 는 실패 시 data 를 undefined 로
         * 두므로 목록이 [] 가 되는데, 그것을 "0건" 으로 그리면 사용자는 **서버 오류를 자기
         * 자산이 사라진 것으로 오해**한다. 문구가 그 오해를 직접 부정한다.
         * ★싸개가 `role="alert"` 를 갖는다 — 킷 빈 판은 `busy` 일 때만 읽어 주므로, 그대로 두면
         *   조회가 실패했다는 사실이 보조기술에 한 마디도 가지 않는다.
         */}
        {uploadsQuery.isError && (
          <div role="alert">
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
          </div>
        )}

        {/* ★싸개가 `role="status"` 를 갖는다 — 조회가 끝나고 0건이라는 것은 방금 일어난 일이라
            알린다(`alert` 가 아니다 — 끼어들 만큼 급한 소식이 아니다). */}
        {isEmpty && (
          <div role="status">
            <EmptyState
              icon={Inbox}
              title="아직 올린 자산이 없습니다"
              desc="위에서 영상을 올리면 여기에 쌓입니다."
            />
          </div>
        )}

        {hasRows && (
          /*
            ★★ **표가 아니라 행 카드다 (2026-09-08 반전).** 여덟 열이 요구하는 최소 폭이 본문
              최대 폭(1,200px)을 넘어, 열을 어떻게 나눠도 파일명·상태 부제가 반드시 접히거나
              잘렸다. 시안(SD-026)도 이 목록을 **행**으로 그렸다.
            ⚠ **말줄임으로 되돌리지 말 것.** 시안이 이 화면에서 그것을 명시적으로 거부한다 —
              *"잘리는 꼬리는 확장자다 … 왜 거부됐는지 화면에서 사라진다"*, 그리고 `title` 보완도
              *"터치 환경이라 hover 툴팁이 뜨지 않고, 게시본 정리기가 지우는 속성 계열"* 이라 쓰지
              않는다. 행 카드는 줄바꿈이 손해가 아니라 자를 이유 자체가 없다.
          */
          <div className="klid-authoring-assets">
            {/* 건수는 제목 옆이 아니라 목록 바로 위다 — 형제 화면(증강)과 같은 자리. */}
            <div className="klid-result-head">
              <ResultCount total={totalElements} />
              <AugmentStatusLink />
            </div>

            <ul
              className="klid-authoring-records"
              aria-label="업로드 자산 목록"
              data-testid="portal-upload-list"
            >
              {uploads.map((u) => {
                const isReady = u.uldSttsCd === PortalUploadStatus.READY;
                const isFailed = u.uldSttsCd === PortalUploadStatus.FAILED;
                // 처리 중 자산은 BE 가 삭제를 409 로 거부하므로 삭제를 잠근다(무반응 방지).
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
                const locked = busy !== undefined;
                const name = u.orgnlFileNm;
                const statusLabel = STATUS_LABEL[u.uldSttsCd] ?? u.uldSttsCd;

                return (
                  <li key={u.uldSn} data-testid={`portal-upload-item-${u.uldSn}`}>
                    <RecordRow
                      /* 줄 짜임은 형제 화면(증강)과 같다 — 왼쪽은 타이틀 → 상태 안내 → 자산 정보
                         → 일시 한 줄, 맨 아래가 걸음 줄(왼쪽 삭제·받기 · 오른쪽 끝 걸음). */
                      titleSize="large"
                      factsInline
                      factsBelow
                      actionRow
                      /* 사용자 파일명 — 텍스트 노드(자동 escape). 자르지 않고 줄바꿈해 **확장자까지**
                         보인다(거부 사유가 «지원하지 않는 형식» 이라 꼬리가 곧 근거다). */
                      title={name}
                      badge={
                        <Badge
                          variant="light"
                          color={STATUS_COLOR[u.uldSttsCd] ?? 'gray'}
                          className="klid-badge-tint"
                        >
                          {statusLabel}
                        </Badge>
                      }
                      /* 상태가 알리는 「다음에 무슨 일이 일어나는가」 — 배지 옆이 아니라 아래에
                         둔다. 배지 줄이 길어지면 파일명과 자리를 다툰다. */
                      hint={STATUS_HINT[u.uldSttsCd]}
                      /* 실패 사유는 전문을 보인다 — 서버 사유는 텍스트 노드(자동 escape)이고
                         경고 표식은 줄 카드가 붙인다. */
                      actionNote={
                        isFailed
                          ? (u.failRsnCn ?? '처리에 실패했습니다. 다시 업로드해 주세요.')
                          : undefined
                      }
                      facts={[
                        <>
                          올린 일시 <span>{formatPortalDateTime(u.regDt)}</span>
                        </>,
                        /*
                          만료 예정일 — 날짜까지만 적는다(사양). 값이 비는 것은 **처리 중** 하나이며
                          그때는 자리를 비운다. `-`·`없음` 을 지어내면 만료가 정해졌는데 표기만 빈
                          것으로 읽힌다.
                          ⚠ 표식(`data-testid`)을 **값이 있을 때만** 붙인다 — 빈 자리에까지 표식이
                            남으면 «표기가 있다» 와 «자리가 비었다» 를 가릴 수 없다.
                        */
                        ...(expiresOn !== null
                          ? [
                              <span data-testid={`portal-upload-expiry-${u.uldSn}`}>
                                만료: {expiresOn}
                              </span>,
                            ]
                          : []),
                      ]}
                      /*
                        삭제·받기는 맨 아래 줄 **왼쪽**에 글자 버튼으로 꺼내 둔다(삭제가 맨 왼쪽).
                        ⚠ 2026-09-16 — 구 처리(아이콘만) 폐기. 걸음 줄이 제 줄로 떨어져 나오면서
                          «한 줄이 조작을 다섯까지 담는다» 는 근거가 사라졌고, 글자 버튼이 무엇을
                          받는지 화면에서 바로 읽힌다.
                        ★ 보이는 글은 접근 이름의 **부분집합**이다(WCAG 2.5.3) — 접근 이름에는
                          파일명이 앞에 붙는다. 줄이 여럿이라 이름 없이 두면 같은 이름의 버튼이
                          자산 수만큼 생겨 보조기술 사용자가 어느 자산인지 가릴 수 없다.
                      */
                      actionStart={
                        <>
                          {isProcessing ? (
                            <LockedStep
                              reason={DELETE_LOCKED_TIP}
                              reasonId={`portal-upload-delete-locked-${u.uldSn}`}
                              label={`${name} 삭제`}
                              tone="danger"
                            >
                              <Trash2 aria-hidden />
                              삭제
                            </LockedStep>
                          ) : (
                            <Button
                              size="small"
                              variant="text"
                              className="klid-btn-danger-text"
                              aria-label={`${name} 삭제`}
                              disabled={deleteUpload.isPending}
                              onClick={() => setDeleteTarget(u)}
                            >
                              <Trash2 aria-hidden />
                              삭제
                            </Button>
                          )}

                          {isReady &&
                            (locked ? (
                              <LockedStep
                                reason={DOWNLOAD_LOCKED_TIP}
                                reasonId={`portal-upload-export-locked-${u.uldSn}`}
                                label={`${name} 내보내기(JSON)`}
                                busy={busy === 'export'}
                              >
                                <FileBraces aria-hidden />
                                내보내기(JSON)
                              </LockedStep>
                            ) : (
                              <Button
                                size="small"
                                variant="text"
                                aria-label={`${name} 내보내기(JSON)`}
                                onClick={() => exportLabels(u)}
                              >
                                <FileBraces aria-hidden />
                                내보내기(JSON)
                              </Button>
                            ))}

                          {isReady &&
                            (locked ? (
                              <LockedStep
                                reason={DOWNLOAD_LOCKED_TIP}
                                reasonId={`portal-upload-file-locked-${u.uldSn}`}
                                label={`${name} 원본 다운로드`}
                                /* 진행 사실은 보조기술에도 전달한다 — 원본은 최대 5GB 라 오래
                                   걸릴 수 있어 «눌렸는데 아무 일도 없다» 로 보이면 안 된다. */
                                busy={busy === 'file'}
                              >
                                <Download aria-hidden />
                                원본 다운로드
                              </LockedStep>
                            ) : (
                              <Button
                                size="small"
                                variant="text"
                                aria-label={`${name} 원본 다운로드`}
                                onClick={() => downloadFile(u)}
                              >
                                <Download aria-hidden />
                                원본 다운로드
                              </Button>
                            ))}

                          {/*
                            취소는 **원본을 내려받는 동안에만** 나타나고 상호 잠금 대상에서
                            제외된다 — 취소는 눌러야 동작한다.
                            ⚠ 다운로드 버튼을 치우고 그 자리에 넣지 않는다. 자리를 바꾸면
                              커서 아래에서 버튼이 갈려 «한 번 더» 누르려던 손이 취소를 누른다.
                          */}
                          {busy === 'file' && (
                            <Button
                              size="small"
                              variant="text"
                              className="klid-btn-danger-text"
                              aria-label={`${name} 원본 다운로드 취소`}
                              onClick={() => cancelFileDownload(u.uldSn)}
                            >
                              취소
                            </Button>
                          )}
                        </>
                      }
                      /* 걸음 버튼은 같은 줄 오른쪽 끝. AI 증강은 창을 여는 보조 걸음이라
                         라벨링 앞에 선다(시안). */
                      action={
                        canMark || canRequestAugment || isReady ? (
                          <div className="klid-authoring-row-actions" data-align="end">
                            {canMark && (
                              <Button
                                as={Link}
                                /* 마킹 화면으로 들어가는 자리는 이 목록뿐이다. 주소 조립은
                                   `markingPath` 한 곳이 한다(문자열을 여기 흩지 않는다). */
                                to={buildPortalUploadMarkingPath(u.uldSn)}
                                /* ⚠ 킷 버튼은 무엇으로 서든 `role="button"` 을 박는다 — 주소가
                                   있는 이동이므로 역할을 링크로 되돌린다(회귀 가드 보유). */
                                role="link"
                                size="small"
                                aria-label={`${name} 마킹`}
                              >
                                마킹
                              </Button>
                            )}

                            {canRequestAugment && (
                              <Button
                                size="small"
                                variant="secondary"
                                aria-label={`${name} AI 증강 요청`}
                                onClick={() => openAugmentForm(u)}
                              >
                                AI 증강
                              </Button>
                            )}

                            {isReady && (
                              <Button
                                as={Link}
                                /* 통합 라벨링 화면으로 보낸다 — 업로드 자산 전용 라벨링 화면은
                                   폐기됐다. 주소 조립은 `labelingEntry` 한 곳이 한다. */
                                to={buildPortalUploadLabelPath(u.uldSn)}
                                role="link"
                                size="small"
                                aria-label={`${name} 라벨링`}
                              >
                                라벨링
                              </Button>
                            )}
                          </div>
                        ) : undefined
                      }
                    >
                      {/* 자산 정보 — 이름은 흐리게 · 값은 진하게, 사이는 세로선. 프레임 수는 아직
                          없으면 **칩 자체를 두지 않는다**(표와 달리 열이 없어 빈 자리가 «밀렸나»
                          로 읽히지 않는다). */}
                      <ConditionChips
                        look="text"
                        label="자산 정보"
                        items={[
                          { label: '유형', value: isVideo ? '영상' : u.uldTypeCd },
                          { label: '크기', value: formatSize(u.fileSz) },
                          ...(u.frmeCnt !== null && u.frmeCnt !== undefined
                            ? [{ label: '프레임', value: String(u.frmeCnt) }]
                            : []),
                        ]}
                      />
                    </RecordRow>
                  </li>
                );
              })}
            </ul>

            {/* 전체가 한 페이지에 들어오면 페이저를 그리지 않는다. */}
            {totalPages > 1 && (
              <PageNav
                totalPages={totalPages}
                /* 킷 페이저는 1부터 센다 — 우리 창구는 0부터라 경계에서 한 번만 옮긴다. */
                currentPage={page + 1}
                onChange={(oneBased) => setPage(Math.max(0, oneBased - 1))}
              />
            )}
          </div>
        )}
      </section>

      {/* 삭제 확인 — 되돌릴 수 없는 걸음이라 포털 확인 창으로 한 번 더 묻는다(메인은 위험 색).
          ⚠ 닫혀 있을 때는 **아예 세우지 않는다** — 킷 창은 닫힌 상태에서도 `role="dialog"` 를
            문서에 남겨, 늘 매달아 두면 화면에 창이 둘 있는 것으로 읽힌다(실측). */}
      {deleteTarget !== null && (
        <Dialog
          open
          onOpenChange={(open) => {
            if (!open) setDeleteTarget(null);
          }}
          title="자산을 삭제할까요?"
          /* 사용자 파일명 — 텍스트 노드(자동 escape). */
          desc={`"${deleteTarget.orgnlFileNm}" 자산을 삭제합니다. 되돌릴 수 없습니다.`}
          sub={{ label: '취소', close: true }}
          main={{
            label: '삭제',
            tone: 'danger',
            close: true,
            onClick: () => runDelete(deleteTarget),
          }}
        />
      )}

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
    </section>
  );
}

/**
 * 증강 현황으로 건너뛰는 바로가기.
 *
 * ★킷 `MoreLink` 를 쓰지 않고 같은 모양을 라우터 링크로 세운다 — 그 부품은 `<a href>` 를 직접
 *  그리는데, 포털 채널 산출물은 **Host 마운트 경로를 라우터 basename 으로** 가지므로 앱 경로를
 *  그대로 실은 `href` 는 그 경로를 잃고 엉뚱한 주소로 나간다. `Link` 는 basename 을 자동으로
 *  붙이고 화면 안에서 옮긴다. 표식은 시안 그대로 **건너뛰기(대각선 화살표)** 다.
 */
function AugmentStatusLink() {
  return (
    <Link to={AUGMENT_STATUS_PATH} className="klid-more-link">
      {AUGMENT_STATUS_LABEL}
      <ArrowUpRight aria-hidden />
    </Link>
  );
}

/**
 * 잠긴 걸음 — 버튼 모양은 그대로 두고 누르기만 막는다.
 *
 * ★ **속성으로 잠그지 않는다**(`disabled` 미사용). WCAG 2.1.1 — native `disabled` 는 Tab 순서에서
 *   빠져 **왜 못 누르는지 알 길이 사라진다.** `aria-disabled` 로 초점은 남기고 활성화만 막는다
 *   (실제 차단은 눌림 처리 쪽의 «받는 중이면 아무것도 하지 않는다»·«삭제를 열지 않는다» 가 한다).
 * ★★ **말풍선만으로 끝내지 않는다.** 킷 말풍선 본문은 `aria-hidden` 이라 보조기술에 닿지 않고,
 *   손가락 입력에서는 뜨지도 않는다. 그래서 말풍선(눈)과 화면 밖 글 + `aria-describedby`(귀)를
 *   **함께** 둔다 — 같은 문구가 문서에 두 번 나오는 것은 의도다. 형제 화면(증강)과 같은 처리다.
 * ⚠ 말풍선은 **감싸는 조각**에 건다 — 킷 말풍선은 자식에 `aria-labelledby` 를 박아, 버튼에
 *   직접 걸면 그 버튼의 접근 이름이 사유 문장으로 덮인다.
 */
function LockedStep({
  reason,
  reasonId,
  label,
  tone,
  busy,
  children,
}: {
  reason: string;
  reasonId: string;
  label: string;
  tone?: 'danger';
  /** 지금 받는 중인 걸음인가 — 그 사실을 보조기술에도 알린다(원본은 오래 걸릴 수 있다). */
  busy?: boolean;
  children: ReactNode;
}) {
  return (
    <Tooltip text={reason}>
      <span>
        <Button
          size="small"
          variant="text"
          className={tone === 'danger' ? 'klid-btn-danger-text disabled' : 'disabled'}
          aria-disabled
          aria-busy={busy || undefined}
          aria-label={label}
          aria-describedby={reasonId}
          onClick={(e: MouseEvent) => e.preventDefault()}
        >
          {children}
        </Button>
        <span id={reasonId} className="sr-only">
          {reason}
        </span>
      </span>
    </Tooltip>
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
