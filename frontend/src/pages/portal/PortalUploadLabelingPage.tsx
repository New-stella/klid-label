// Phase 6 — 포털 업로드 자산 라벨링 화면 (SCR-PORTAL, PORTAL_USER 전용).
//
// 라벨링 코어(features/label/**)는 수정 없이 CanvasShell 을 props 로만 조립한다.
// 도구는 SELECT/PAN/BBOX/POLYGON 만 노출(ADR-013 — 포털 업로드는 SAM·키포인트·오토라벨 미제공, R2).
// 이미지 자산=단일 프레임, 영상 자산=프레임 네비게이션. 저장은 현재 프레임 전체교체 PUT 1회.

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import {
  ChevronLeft,
  ChevronRight,
  Download,
  FileDown,
  Hand,
  MousePointer2,
  Pentagon,
  Save,
  Square,
} from 'lucide-react';

import { Button } from '@/components/common/Button';
import { Spinner } from '@/components/common/Spinner';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { PORTAL_KEYS } from '@/lib/queryKeys';
import { isEditBlockedState, useIsEditBlocked, useLabelStore } from '@/stores/useLabelStore';
import { useUiStore } from '@/stores/useUiStore';
import { CanvasShell } from '@/features/label/canvas/CanvasShell';
import { BusyOverlay } from '@/features/label/components/BusyOverlay';
import { useLabelMasters } from '@/features/label/hooks/useLabelMasters';
import { resolveLabelDisplayName } from '@/features/label/utils/labelDisplayName';
import type { FrameSummary, ToolType } from '@/features/label/types';
import { ToolType as Tool } from '@/features/label/types';
import { getUpload, downloadUploadExport, downloadUploadFile } from '@/features/portal/uploads/api';
import { PortalUploadStatus } from '@/features/portal/uploads/types';
import { useUploadFrameImage } from '@/features/portal/uploads/hooks/useUploadFrameImage';
import { useUploadFrameLabels } from '@/features/portal/uploads/hooks/useUploadFrameLabels';
import { useSaveUploadLabels } from '@/features/portal/uploads/hooks/useSaveUploadLabels';

const CANVAS_FALLBACK_W = 960;
const CANVAS_FALLBACK_H = 600;

// 포털 업로드 라벨링에 노출하는 도구 — SAM·키포인트·오토라벨 제외.
const UPLOAD_TOOLS: { tool: ToolType; icon: typeof MousePointer2; label: string }[] = [
  { tool: Tool.SELECT, icon: MousePointer2, label: '선택' },
  { tool: Tool.PAN, icon: Hand, label: '이동' },
  { tool: Tool.BBOX, icon: Square, label: '바운딩 박스' },
  { tool: Tool.POLYGON, icon: Pentagon, label: '폴리곤' },
];

/** 컨테이너 폭을 측정(ResizeObserver 미의존)해 캔버스 크기를 산출. jsdom 등 미측정 시 fallback. */
function useMeasuredSize<T extends HTMLElement>() {
  const ref = useRef<T | null>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => {
      const rect = el.getBoundingClientRect();
      setSize({ width: Math.floor(rect.width), height: Math.floor(rect.height) });
    };
    update();
    window.addEventListener('resize', update);
    return () => window.removeEventListener('resize', update);
  }, []);
  return [ref, size] as const;
}

export function PortalUploadLabelingPage() {
  const { uldSn } = useParams<{ uldSn: string }>();
  const uldSnNum = Number(uldSn);
  const validUldSn = Number.isFinite(uldSnNum) && uldSnNum > 0;

  const pushToast = useUiStore((s) => s.pushToast);

  const detailQuery = useQuery({
    queryKey: PORTAL_KEYS.uploadDetail(uldSnNum),
    queryFn: () => getUpload(uldSnNum),
    enabled: validUldSn,
  });
  const detail = detailQuery.data;
  const frames = useMemo(() => detail?.frames ?? [], [detail]);
  const isReady = detail?.uldSttsCd === PortalUploadStatus.READY;

  const [index, setIndex] = useState(0);
  // 자산이 바뀌거나 프레임 수가 줄면 인덱스를 안전 범위로 클램프.
  useEffect(() => {
    setIndex((i) => (i < frames.length ? i : 0));
  }, [frames.length]);
  const currentFrame = frames[index];
  const uldFrmeSn = currentFrame?.uldFrmeSn;
  // 편집 차단 단일 판정원 — 저장이 도는 동안 캔버스·도구·프레임 이동을 모두 막는다.
  // 이 화면의 저장은 현재 프레임 **전체교체 PUT** 이라, 저장 중에 그린 라벨은 저장에도 담기지
  // 않고 저장 성공 후 재조회에 덮여 사라진다. 차단이 그 창 자체를 없앤다.
  const isEditBlocked = useIsEditBlocked(uldFrmeSn);
  // 진행 오버레이 배선 — 내부 라벨링(LabelingPage)과 **같은 컴포넌트·같은 파생 규칙**을 쓴다.
  // 차단만 있고 취소가 없으면 저장이 늘어질 때 5분 fail-safe 까지 화면이 잠긴 채로 남는다.
  // 프레임 스코프(isEditBlockedState)로 걸러 다른 프레임의 작업이 이 화면을 덮지 않게 한다.
  // ⚠ ADR-013 — 이 화면에는 AI 작업이 없어 실제 종류는 SAVE 뿐이다(문구는 busyPolicy 단일 소스).
  const busyKind = useLabelStore((s) =>
    isEditBlockedState(s, uldFrmeSn) ? (s.busy?.kind ?? null) : null,
  );
  const busyStartedAt = useLabelStore((s) =>
    isEditBlockedState(s, uldFrmeSn) ? (s.busy?.startedAt ?? undefined) : undefined,
  );
  const cancelBusy = useLabelStore((s) => s.cancelBusy);

  // 라벨 스토어 — 진입 시 초기화, 이탈 시 정리(다른 화면 잔존 방지).
  const reset = useLabelStore((s) => s.reset);
  const setStoreLabels = useLabelStore((s) => s.setLabels);
  const addLabel = useLabelStore((s) => s.addLabel);
  // 기존 작업본을 보존한 채 서버 라벨을 합칠 때 사용(중복 좌표는 스킵).
  const mergeLabels = useLabelStore((s) => s.mergeAutoLabels);
  // 저장 성공 시 미저장 표시 해제 — 재조회분이 작업본에 반영되는 조건이기도 하다.
  const clearDirty = useLabelStore((s) => s.clearDirty);
  const labels = useLabelStore((s) => s.labels);
  const activeTool = useLabelStore((s) => s.activeTool);
  const setActiveTool = useLabelStore((s) => s.setActiveTool);
  const activeLabelId = useLabelStore((s) => s.activeLabelId);
  const setActiveLabelId = useLabelStore((s) => s.setActiveLabelId);

  useEffect(() => {
    reset();
    return () => reset();
  }, [reset]);

  const { url: imageUrl, loading: imageLoading } = useUploadFrameImage(
    isReady ? uldFrmeSn : undefined,
  );
  const labelsQuery = useUploadFrameLabels(
    isReady ? uldFrmeSn : undefined,
    currentFrame?.frmeNo ?? 0,
  );

  // 이 프레임의 서버 라벨을 이미 받았는지(첫 로드 ↔ 재조회 구분).
  const loadedFrmeSnRef = useRef<number | undefined>(undefined);

  // 프레임 전환 시 이전 프레임 라벨을 즉시 비운다(잔존 방지). setLabels 가 dirty 도 초기화한다.
  useEffect(() => {
    loadedFrmeSnRef.current = undefined;
    setStoreLabels([]);
  }, [uldFrmeSn, setStoreLabels]);

  // 라벨 로드 완료 시 스토어 동기화.
  // ⚠ 미저장 편집(dirty>0)이 있으면 **덮어쓰지 않는다** — 사용자가 그리는 사이에 조회 응답이
  //   도착하면(초기 로드 지연·백그라운드 재조회) 방금 그린 라벨이 조용히 사라지고, 그 상태로
  //   저장하면 전체교체 PUT 이라 작업이 통째로 유실된다.
  //   · 첫 로드(로딩 중에 그린 경우): 서버 라벨을 작업본에 병합해 양쪽 다 보존한다.
  //   · 이후 재조회: 사용자의 편집을 유지한다.
  //   프레임 전환 시 위 effect 가 dirty 를 초기화하므로, dirty>0 은 곧 "이 프레임에서 편집했다"다.
  useEffect(() => {
    const loaded = labelsQuery.data;
    if (loaded === undefined) return;
    const firstLoad = loadedFrmeSnRef.current !== uldFrmeSn;
    loadedFrmeSnRef.current = uldFrmeSn;
    if (useLabelStore.getState().dirtyLabels.size === 0) {
      setStoreLabels(loaded);
      return;
    }
    if (firstLoad && loaded.length > 0) mergeLabels(loaded);
  }, [uldFrmeSn, labelsQuery.data, setStoreLabels, mergeLabels]);

  // 라벨 분류 선택(자유 라벨 아님 — 마스터 기반). 코어 훅을 소비만 한다.
  const { data: labelMasters } = useLabelMasters();
  const activeMasters = useMemo(
    () => (labelMasters ?? []).filter((m) => m.useYn === 'Y'),
    [labelMasters],
  );

  const saveMutation = useSaveUploadLabels(uldFrmeSn, {
    // 저장이 끝나면 dirty 를 비운다 — 비우지 않으면 위 "미저장 편집 보호" 가드가 계속 걸려
    // 저장 직후 재조회분이 반영되지 않고, 그 프레임은 서버 상태와 영영 재동기화되지 않는다.
    onSuccess: () => {
      clearDirty();
      pushToast({ variant: 'success', message: '라벨을 저장했습니다.' });
    },
    onError: () => pushToast({ variant: 'error', message: '라벨 저장에 실패했습니다.' }),
  });

  const [downloading, setDownloading] = useState<'export' | 'file' | null>(null);
  const handleExport = () => {
    if (!validUldSn || downloading) return;
    setDownloading('export');
    downloadUploadExport(uldSnNum)
      .catch(() => pushToast({ variant: 'error', message: '내보내기에 실패했습니다.' }))
      .finally(() => setDownloading(null));
  };
  const handleDownloadFile = () => {
    if (!validUldSn || downloading) return;
    setDownloading('file');
    downloadUploadFile(uldSnNum, detail?.orgnlFileNm ?? `upload-${uldSnNum}`)
      .catch(() => pushToast({ variant: 'error', message: '원본 다운로드에 실패했습니다.' }))
      .finally(() => setDownloading(null));
  };

  const [containerRef, measured] = useMeasuredSize<HTMLDivElement>();
  const canvasWidth = measured.width || CANVAS_FALLBACK_W;
  const canvasHeight = measured.height || CANVAS_FALLBACK_H;

  const frame: FrameSummary | null = useMemo(() => {
    if (!currentFrame) return null;
    return {
      // uldFrmeSn 을 srcSn 자리에 어댑팅(CanvasShell 계약 결합). 본 화면은 SAM 미노출이라
      // srcSn 기반 SAM2 훅은 발화하지 않는다.
      frameNo: currentFrame.frmeNo,
      srcSn: currentFrame.uldFrmeSn,
      thumbnailUrl: '',
      imageUrl: imageUrl ?? '',
    };
  }, [currentFrame, imageUrl]);

  // ── 렌더 분기 ──────────────────────────────────────────────
  if (!validUldSn) {
    return <Notice message="잘못된 자산 주소입니다." />;
  }
  if (detailQuery.isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <Spinner label="자산 불러오는 중" size="lg" />
      </div>
    );
  }
  if (detailQuery.isError || !detail) {
    return <Notice message="자산을 불러올 수 없습니다. 본인 자산인지 확인해 주세요." />;
  }
  if (!isReady) {
    return (
      <Notice
        message={
          detail.uldSttsCd === PortalUploadStatus.FAILED
            ? '처리에 실패한 자산입니다. 라벨링을 진행할 수 없습니다.'
            : '아직 준비 중인 자산입니다. 처리가 완료되면(READY) 라벨링할 수 있습니다.'
        }
      />
    );
  }

  const totalFrames = frames.length;
  const multiFrame = totalFrames > 1;

  return (
    <div className="flex flex-col gap-4">
      {/* 헤더 — 파일명/프레임 카운트. 파일명은 텍스트 노드로만 렌더(XSS 무해). */}
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="min-w-0">
          <h1 className="truncate text-section-title font-bold text-gray-900">
            {detail.orgnlFileNm}
          </h1>
          <p className="text-body text-gray-600">
            {multiFrame ? `프레임 ${index + 1} / ${totalFrames}` : '이미지 1장'}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={handleExport}
            disabled={downloading !== null}
            className={cn(
              'inline-flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 py-2 text-btn-label text-gray-700 hover:bg-gray-50 disabled:opacity-50',
              KRDS_FOCUS,
            )}
          >
            <FileDown className="h-4 w-4" aria-hidden="true" />
            내보내기(JSON)
          </button>
          <button
            type="button"
            onClick={handleDownloadFile}
            disabled={downloading !== null}
            className={cn(
              'inline-flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 py-2 text-btn-label text-gray-700 hover:bg-gray-50 disabled:opacity-50',
              KRDS_FOCUS,
            )}
          >
            <Download className="h-4 w-4" aria-hidden="true" />
            원본 다운로드
          </button>
        </div>
      </div>

      {/* 도구바 + 라벨 분류 + 저장 */}
      <div className="flex flex-wrap items-center gap-2">
        <div role="toolbar" aria-label="라벨링 도구" className="flex items-center gap-1">
          {UPLOAD_TOOLS.map(({ tool, icon: Icon, label }) => {
            const active = activeTool === tool;
            return (
              <button
                key={tool}
                type="button"
                onClick={() => setActiveTool(tool)}
                disabled={isEditBlocked}
                aria-label={label}
                aria-pressed={active}
                className={cn(
                  'inline-flex h-10 w-10 items-center justify-center rounded-md border',
                  active
                    ? 'border-primary-600 bg-primary-600 text-white'
                    : 'border-gray-300 bg-white text-gray-700 hover:bg-gray-50',
                  isEditBlocked && 'cursor-not-allowed opacity-50',
                  KRDS_FOCUS,
                )}
              >
                <Icon className="h-5 w-5" aria-hidden="true" />
              </button>
            );
          })}
        </div>

        {activeMasters.length > 0 && (
          <label className="flex items-center gap-1.5 text-body text-gray-700">
            <span>라벨 분류</span>
            <select
              disabled={isEditBlocked}
              value={activeLabelId ?? ''}
              onChange={(e) =>
                setActiveLabelId(e.target.value === '' ? null : Number(e.target.value))
              }
              className={cn(
                'rounded-md border border-gray-300 bg-white px-2 py-1.5 text-body text-gray-800',
                KRDS_FOCUS,
              )}
            >
              <option value="">자동(기본)</option>
              {/* 표시만 한글 우선(공용 함수) — 선택 값은 labelId 라 저장 payload 와 무관. */}
              {activeMasters.map((m) => (
                <option key={m.labelId} value={m.labelId}>
                  {resolveLabelDisplayName(m.name, m.dtctTypeCd)}
                </option>
              ))}
            </select>
          </label>
        )}

        <Button
          type="button"
          variant="primary"
          leftIcon={Save}
          className="ml-auto"
          loading={saveMutation.isPending}
          disabled={saveMutation.isPending}
          onClick={() => saveMutation.mutate(labels)}
        >
          {saveMutation.isPending ? '저장 중…' : '저장'}
        </Button>
      </div>

      {/* 캔버스 */}
      <div
        ref={containerRef}
        className="relative w-full overflow-hidden rounded-lg border border-gray-300 bg-gray-100"
        style={{ height: CANVAS_FALLBACK_H }}
      >
        {frame ? (
          <CanvasShell
            frame={frame}
            width={canvasWidth}
            height={canvasHeight}
            labels={labels}
            readOnly={isEditBlocked}
            onLabelAdd={(l) => addLabel({ ...l, frameNo: frame.frameNo })}
          />
        ) : (
          <div className="flex h-full items-center justify-center text-body text-gray-500">
            표시할 프레임이 없습니다.
          </div>
        )}
        {/* 진행 오버레이 — 무엇이 진행 중인지 캔버스 위에 보이고 거기서 바로 취소한다(AC4/AC5).
            300ms 지연 표시라 짧은 저장에는 깜빡이지 않는다(AC7). 취소는 클라이언트 결과 폐기이며
            서버 처리를 중단시키지 않는다. */}
        <BusyOverlay kind={busyKind} startedAt={busyStartedAt} onCancel={cancelBusy} />
        {/* 프레임 이미지 blob 로드 중 중앙 스피너 오버레이 — 로드 완료/실패 시 자동 해제. */}
        {frame && imageLoading && (
          <div
            data-testid="upload-canvas-image-spinner"
            role="status"
            className="absolute inset-0 z-10 flex items-center justify-center bg-black/20"
          >
            <Spinner size="lg" label="이미지 로딩 중" />
          </div>
        )}
      </div>

      {/* 프레임 네비게이션 (영상 자산만) */}
      {multiFrame && (
        <div className="flex items-center justify-center gap-3">
          <button
            type="button"
            onClick={() => setIndex((i) => Math.max(0, i - 1))}
            disabled={isEditBlocked || index === 0}
            aria-label="이전 프레임"
            className={cn(
              'inline-flex h-10 w-10 items-center justify-center rounded-md border border-gray-300 bg-white text-gray-700 hover:bg-gray-50 disabled:opacity-40',
              KRDS_FOCUS,
            )}
          >
            <ChevronLeft className="h-5 w-5" aria-hidden="true" />
          </button>
          <span className="text-body tabular-nums text-gray-700" aria-live="polite">
            {index + 1} / {totalFrames}
          </span>
          <button
            type="button"
            onClick={() => setIndex((i) => Math.min(totalFrames - 1, i + 1))}
            disabled={isEditBlocked || index >= totalFrames - 1}
            aria-label="다음 프레임"
            className={cn(
              'inline-flex h-10 w-10 items-center justify-center rounded-md border border-gray-300 bg-white text-gray-700 hover:bg-gray-50 disabled:opacity-40',
              KRDS_FOCUS,
            )}
          >
            <ChevronRight className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>
      )}
    </div>
  );
}

function Notice({ message }: { message: string }) {
  return (
    <div
      role="status"
      className="rounded-lg border border-gray-200 bg-white p-6 text-body text-gray-700 shadow-sm"
    >
      {message}
    </div>
  );
}
