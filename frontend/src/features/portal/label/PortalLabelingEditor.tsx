// 포털 라벨링 편집기 — 편집 면 한 판(머리 줄 · 도구 줄 · 도구 칸 · 캔버스 · 속성 칸 · 프레임 줄 · 재생 줄)과 창들.
// 짜임 · 원본과 달라진 자리는 `PortalLabelingView` 머리말에 있다. [@design SCREEN-029]

import { Suspense, lazy, useLayoutEffect, useRef, useState } from 'react';
import { Button, Tab, TabList, TabTrigger } from 'krds-react';
import { ChevronLeft, CircleDot, LoaderCircle } from 'lucide-react';

import { EditorBar, EditorLayout, EmptyState } from '@portal/components/custom';
import { CanvasNotice } from '@portal/pages/workspace/authoring/CanvasNotice';
import { StatusText } from '@portal/pages/workspace/authoring/StatusText';
import { TOOL_DISPLAY_NAME } from '@/features/label/types';

import '@portal/pages/workspace/authoring/AuthoringLabelingView.css';
// 캔버스 그릇 모양(칸 채움 · 안쪽 여백)만 빌린다 — 그림 캔버스 조각 자체는 쓰지 않는다(캔버스는 원본이다)
import '@portal/pages/workspace/authoring/AnnotatedFrame.css';
import './PortalLabelingView.css';

import { PortalEventAnnotation } from './components/PortalEventAnnotation';
import { PortalFramePlayback } from './components/PortalFramePlayback';
import { PortalFrameStrip } from './components/PortalFrameStrip';
import { PortalLabelPicker } from './components/PortalLabelPicker';
import { PortalLabelingDialogs } from './components/PortalLabelingDialogs';
import { PortalLabelingRail } from './components/PortalLabelingRail';
import { PortalLabelingToolbar } from './components/PortalLabelingToolbar';
import { PortalMetaSections } from './components/PortalMetaSections';
import { PortalObjectPanel } from './components/PortalObjectPanel';
import { PortalShortcutHelp } from './components/PortalShortcutHelp';
import type { PortalLabelingViewProps, RightTab } from './types';

// konva 는 브라우저 전용 — 원본 화면과 같은 방식으로 따로 불러온다.
const CanvasShell = lazy(() =>
  import('@/features/label/canvas/CanvasShell').then((m) => ({ default: m.CanvasShell })),
);

/** 캔버스 칸 크기를 계속 잰다 — 칸이 늘고 줄 때마다 캔버스를 다시 맞춘다. */
function useStageSize() {
  const ref = useRef<HTMLDivElement | null>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => {
      const rect = el.getBoundingClientRect();
      setSize({ width: Math.floor(rect.width), height: Math.floor(rect.height) });
    };
    const observer = new ResizeObserver(update);
    observer.observe(el);
    update();
    return () => observer.disconnect();
  }, []);
  return [ref, size] as const;
}

const PANEL_TABS: { value: Exclude<RightTab, 'issues'>; label: string }[] = [
  { value: 'objects', label: '객체' },
  { value: 'meta', label: '메타' },
];

export function PortalLabelingEditor({
  header,
  frames,
  edit,
  view,
  canvas,
  busy,
  panel,
  labelPicker,
  dialogs,
}: PortalLabelingViewProps) {
  const [stageRef, stageSize] = useStageSize();
  const current = frames.current;
  const rotated = view.rotation !== 0;

  return (
    <>
      <EditorLayout
        className="klid-labeling"
        label="라벨링 편집기"
        railLabel="그리기 도구와 보기"
        panelLabel="객체와 메타"
        header={
          <EditorBar
            title={header.title ?? `프레임 ${frames.index + 1}`}
            center={
              // 진행 중 > 저장 안 함 > 저장됨 — 저장은 저장 안 한 상태에서 시작하므로 진행을 먼저 본다(원본 규칙)
              header.saving ? (
                <StatusText tone="info" icon={LoaderCircle}>
                  저장 중
                </StatusText>
              ) : header.dirty ? (
                <StatusText tone="warning" icon={CircleDot}>
                  편집 중
                </StatusText>
              ) : (
                <StatusText tone="success">저장됨</StatusText>
              )
            }
            end={
              /* 닫기는 저장 중에도 누를 수 있다 — 저장 안 한 변경이 있으면 확인 창이 먼저 뜬다(원본 규칙) */
              <Button size="small" variant="text" aria-label="뒤로가기" onClick={header.onClose}>
                <ChevronLeft aria-hidden />
                뒤로
              </Button>
            }
          />
        }
        toolbar={
          <PortalLabelingToolbar
            frameIndex={frames.index}
            frameCount={frames.list.length}
            onRequestGoTo={frames.onRequestGoTo}
            srcSn={current?.srcSn}
            locked={edit.isLocked}
            saving={header.saving}
            onRequestSave={edit.onSave}
          />
        }
        rail={
          <PortalLabelingRail
            rotation={view.rotation}
            onRotate={view.onRotate}
            zoomAreaMode={view.zoomAreaMode}
            onToggleZoomArea={view.onToggleZoomArea}
            showGrid={view.showGrid}
            onToggleGrid={view.onToggleGrid}
            onSelectTool={view.onSelectTool}
            onOpenShortcuts={() => dialogs.shortcuts.onOpenChange(true)}
          />
        }
        stage={
          <>
            {/* 캔버스 그릇 — 칸을 다 채우고 안쪽 여백 24 를 둔다(포털 라벨 캔버스 그릇). 캔버스는 그 안쪽 크기로 선다 */}
            <div className="klid-annotated-frame">
              {/* 칸 안쪽을 다 채우는 자리 — 캔버스 크기를 재는 대상이다. 백분율 채움이라 척도 값이 아니다 */}
              <div ref={stageRef} style={{ width: '100%', height: '100%' }}>
                {current && stageSize.width > 0 ? (
                  <Suspense fallback={<CanvasNotice kind="loading" label="캔버스를 불러오는 중" />}>
                    <CanvasShell
                      ref={canvas.handleRef}
                      frame={current}
                      width={stageSize.width}
                      height={stageSize.height}
                      labels={edit.labels}
                      readOnly={edit.isLocked || edit.isEditBlocked || edit.isDiscarded}
                      onLabelAdd={(l) => canvas.onLabelAdd({ ...l, frameNo: current.frameNo })}
                      onKeypointPlacingChange={canvas.onKeypointPlacingChange}
                      onImageSize={canvas.onImageSize}
                      immediateSegment={canvas.immediateSegment}
                      segmentSimplifyTolerance={canvas.segmentSimplifyTolerance}
                      rotation={view.rotation}
                      showGrid={view.showGrid}
                      zoomAreaMode={view.zoomAreaMode}
                    />
                  </Suspense>
                ) : !current ? (
                  <EmptyState size="xs" title="프레임 없음" />
                ) : null}
              </div>
            </div>
            {canvas.imageLoading && <CanvasNotice kind="loading" label="프레임 이미지를 불러오는 중" />}
            {/* 이미지 실패 — 캔버스는 그대로 두고 실패 사실만 겹쳐 알린다(백지로 보이지 않게) */}
            {canvas.imageError && !canvas.imageLoading && (
              <CanvasNotice kind="alert" placement="top" tone="danger" title="프레임 이미지를 불러오지 못했습니다.">
                {canvas.imageErrorHint}
              </CanvasNotice>
            )}
            {rotated && (
              <CanvasNotice kind="tag" placement="top-end">
                회전 보기 {view.rotation}° · 편집이 잠깁니다
              </CanvasNotice>
            )}
            {view.zoomAreaMode && (
              <CanvasNotice kind="tag" placement="top">
                영역 확대 · 확대할 영역을 드래그해 주세요 (되돌리기: 화면 맞춤)
              </CanvasNotice>
            )}
            {/* 캔버스가 읽기 전용이 된 이유 — 모르면 도구가 왜 안 먹는지 알 수 없다(원본의 가로 띠 둘) */}
            {edit.isLocked ? (
              <CanvasNotice kind="alert" placement="bottom" tone="warning">
                비식별 재처리 중인 영상입니다. 처리가 완료될 때까지 라벨 수정·저장이 제한됩니다.
              </CanvasNotice>
            ) : edit.isDiscarded ? (
              <CanvasNotice kind="alert" placement="bottom" tone="warning">
                폐기한 프레임입니다. 학습데이터 산출물에서 빠지며 복원하기 전까지 라벨을 고칠 수 없습니다.
                라벨과 이미지는 지우지 않고 그대로 보관합니다.
                {edit.discardPending && ' 아직 저장하지 않았습니다. 저장해야 확정됩니다.'}
              </CanvasNotice>
            ) : null}
          </>
        }
        panelHead={
          /* 객체 · 메타 전환 — 칸 폭을 둘로 나눠 채우고, 몸통이 스크롤해도 제자리에 남는다 */
          <Tab
            size="full"
            value={panel.tab === 'meta' ? 'meta' : 'objects'}
            onValueChange={(v: string) => panel.onTabChange(v === 'meta' ? 'meta' : 'objects')}
            aria-label="객체와 메타"
          >
            <TabList>
              {PANEL_TABS.map((t) => (
                <TabTrigger key={t.value} value={t.value} data-testid={`right-tab-${t.value}`}>
                  {t.label}
                </TabTrigger>
              ))}
            </TabList>
          </Tab>
        }
        panel={
          panel.tab === 'meta' ? (
            <div className="klid-labeling-panel-body" data-testid="portal-work-meta-tab">
              <PortalMetaSections srcSn={panel.srcSn} />
              <PortalEventAnnotation rawSn={panel.rawSn} />
            </div>
          ) : (
            <PortalObjectPanel
              labels={edit.labels}
              imageWidth={canvas.frameNaturalSize?.width}
              imageHeight={canvas.frameNaturalSize?.height}
            />
          )
        }
        strip={
          <PortalFrameStrip
            frames={frames.list}
            currentIndex={frames.index}
            onSelect={frames.onRequestGoTo}
            savedSrcSns={frames.savedSrcSns}
            discardedSrcSns={frames.discardedSrcSns}
            uploadSource={frames.uploadSource}
          />
        }
        footer={
          <PortalFramePlayback
            currentIndex={frames.index}
            totalFrames={frames.list.length}
            onSelect={frames.onRequestGoTo}
            dirtyGuard={edit.dirtyCount > 0}
            disabled={edit.isEditBlocked}
          />
        }
      />

      {/* 창은 열릴 때만 세운다 — 닫힌 창이 남아 있으면 단축키가 「창이 열려 있다」로 읽고 전부 멈춘다
          (자세한 사정은 PortalLabelingDialogs 머리말) */}
      {dialogs.shortcuts.open && (
        <PortalShortcutHelp open onOpenChange={dialogs.shortcuts.onOpenChange} />
      )}

      {labelPicker.open && (
        <PortalLabelPicker
          open
          toolName={labelPicker.pendingTool ? TOOL_DISPLAY_NAME[labelPicker.pendingTool] : undefined}
          onSelect={labelPicker.confirm}
          onCancel={labelPicker.cancel}
        />
      )}

      <PortalLabelingDialogs close={dialogs.close} nav={dialogs.nav} conflict={dialogs.conflict} busy={busy} />
    </>
  );
}
