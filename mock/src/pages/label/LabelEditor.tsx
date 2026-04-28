import { useEffect, useRef, useCallback, useState } from 'react';
import { useParams, useSearchParams, useNavigate } from 'react-router-dom';
import { Save, X, GitBranch, Bot } from 'lucide-react';
import { useFetch } from '../../api/queries';
import { api } from '../../api/client';
import { useLabelStore } from '../../store/labelStore';
import { useLabelShortcuts } from '../../hooks/useLabelShortcuts';
import { useToast } from '../../components/common/Toast';
import { LabelCanvas } from '../../components/label/canvas/LabelCanvas';
import { Toolbar } from '../../components/label/Toolbar';
import { ObjectTree } from '../../components/label/ObjectTree';
import { AttributePanel } from '../../components/label/AttributePanel';
import { FrameStrip } from '../../components/label/FrameStrip';
import { FrameSlider } from '../../components/label/FrameSlider';
import { ProgressBar } from '../../components/ui/ProgressBar';
import type { VideoDto, FrameLabels, LabelObject } from '../../api/types';

interface LabelEditorProps {
  portalMode?: boolean;
}

export function LabelEditor({ portalMode = false }: LabelEditorProps) {
  const { id: videoId = '' } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const { showToast } = useToast();

  // Portal: auto-label progress state
  const [autoLabelProgress, setAutoLabelProgress] = useState(0);
  const [autoLabelRunning, setAutoLabelRunning] = useState(false);

  const setVideo = useLabelStore((s) => s.setVideo);
  const setFrame = useLabelStore((s) => s.setFrame);
  const setFrameLabels = useLabelStore((s) => s.setFrameLabels);
  const markClean = useLabelStore((s) => s.markClean);
  const dirty = useLabelStore((s) => s.dirty);
  const currentFrame = useLabelStore((s) => s.currentFrame);
  const frames = useLabelStore((s) => s.frames);
  const totalFrames = useLabelStore((s) => s.totalFrames);

  // Track loaded frames to avoid re-fetching
  const loadedFramesRef = useRef<Set<number>>(new Set());

  // Load video info
  const { data: video, isLoading, error } = useFetch<VideoDto>(`/videos/${videoId}`);

  // Initialize store when video loads
  useEffect(() => {
    if (!video) return;
    loadedFramesRef.current = new Set();
    setVideo(video.id, 60);
    const initialFrame = parseInt(searchParams.get('frame') ?? '0', 10);
    setFrame(isNaN(initialFrame) ? 0 : initialFrame);
  }, [video, setVideo, setFrame, searchParams]);

  // Load frame labels whenever currentFrame changes
  useEffect(() => {
    if (!videoId) return;
    if (loadedFramesRef.current.has(currentFrame)) return;
    // If already in store (manually added), don't reload
    if (frames[currentFrame] !== undefined) {
      loadedFramesRef.current.add(currentFrame);
      return;
    }

    loadedFramesRef.current.add(currentFrame);
    api
      .get<FrameLabels>(`/videos/${videoId}/frames/${currentFrame}/labels`)
      .then((data) => {
        setFrameLabels(currentFrame, data.objects);
      })
      .catch(() => {
        setFrameLabels(currentFrame, []);
      });
  }, [videoId, currentFrame, frames, setFrameLabels]);

  const handleSave = useCallback(async () => {
    const objects = frames[currentFrame] ?? [];
    try {
      await api.put<FrameLabels>(`/videos/${videoId}/frames/${currentFrame}/labels`, { objects });
      markClean();
      let commitOk = true;
      try {
        await api.post(`/history/videos/${videoId}/commit`, {
          frame: currentFrame,
          objectsCount: objects.length,
        });
      } catch (err) {
        commitOk = false;
        console.warn('[LabelEditor] version commit failed', err);
      }

      // SFR-08 — 데이터마트 라벨 동기화 영향 확인
      let martCount = 0;
      try {
        const impact = await api.get<{ datasets: { id: string; name: string; version: string }[] }>(
          `/videos/${videoId}/mart-impact`,
        );
        martCount = impact.datasets.length;
      } catch (err) {
        console.warn('[LabelEditor] mart-impact lookup failed', err);
      }

      if (martCount > 0) {
        showToast(
          `저장됨 · 데이터마트 ${martCount}건 동기화 대상 (확인하세요)`,
          'success',
        );
      } else {
        showToast(commitOk ? '저장됨 · 버전 기록됨' : '저장됨', 'success');
      }
    } catch {
      showToast('저장 실패', 'error');
    }
  }, [videoId, currentFrame, frames, markClean, showToast]);

  useLabelShortcuts({ onSave: handleSave });

  const handleAutoLabel = useCallback(async () => {
    if (autoLabelRunning) return;
    setAutoLabelRunning(true);
    setAutoLabelProgress(0);

    // Simulate progress over 5 seconds
    const intervalMs = 100;
    const totalMs = 5000;
    const steps = totalMs / intervalMs;
    let step = 0;
    const timer = setInterval(() => {
      step++;
      setAutoLabelProgress(Math.min(Math.round((step / steps) * 100), 99));
    }, intervalMs);

    try {
      const result = await api.post<{ videoId: string; objects: LabelObject[] }>(
        `/portal/auto-label/${videoId}`,
        {},
      );
      clearInterval(timer);
      setAutoLabelProgress(100);

      // Add returned objects to current frame
      const addObject = useLabelStore.getState().addObject;
      result.objects.forEach((obj) => addObject(obj));
      showToast(`오토라벨링 완료: ${result.objects.length}개 객체 추가`, 'success');
    } catch {
      clearInterval(timer);
      showToast('오토라벨링 실패', 'error');
    } finally {
      setTimeout(() => {
        setAutoLabelRunning(false);
        setAutoLabelProgress(0);
      }, 800);
    }
  }, [videoId, autoLabelRunning, showToast]);

  if (isLoading) {
    return (
      <div className="fixed inset-0 bg-gray-900 flex items-center justify-center text-white">
        <div className="flex flex-col items-center gap-3">
          <div className="w-8 h-8 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-sm text-gray-300">영상 정보 로드 중...</p>
        </div>
      </div>
    );
  }

  if (error || !video) {
    return (
      <div className="fixed inset-0 bg-gray-900 flex items-center justify-center text-white">
        <div className="text-center">
          <p className="text-lg font-semibold mb-2">영상을 찾을 수 없습니다</p>
          <p className="text-sm text-gray-400 mb-4">ID: {videoId}</p>
          <button
            onClick={() => navigate(-1)}
            className="px-4 py-2 bg-blue-600 rounded-lg text-sm hover:bg-blue-700 transition-colors"
          >
            뒤로 가기
          </button>
        </div>
      </div>
    );
  }

  const objectCount = (frames[currentFrame] ?? []).length;

  return (
    <div className="fixed inset-0 bg-gray-900 flex flex-col overflow-hidden" style={{ zIndex: 50 }}>
      {/* Top bar (56px) */}
      <header className="flex items-center gap-3 px-4 bg-gray-800 border-b border-gray-700 shrink-0" style={{ height: 56 }}>
        <button
          onClick={() => navigate(-1)}
          className="p-1.5 rounded text-gray-400 hover:text-white hover:bg-gray-700 transition-colors"
          aria-label="뒤로가기"
        >
          <X size={18} />
        </button>
        <div className="flex-1 min-w-0">
          {portalMode ? (
            <p className="text-sm font-semibold text-orange-300">포털 — 라벨링</p>
          ) : (
            <p className="text-sm font-semibold text-white truncate">{video.cctvName}</p>
          )}
          <p className="text-xs text-gray-400">{video.eventType}</p>
        </div>
        {/* Center status */}
        <div className="text-center">
          {portalMode ? (
            <p className="text-sm font-medium text-white">
              라벨링 진행: {objectCount} / {totalFrames} 프레임
            </p>
          ) : (
            <p className="text-sm font-medium text-white">
              Frame {currentFrame + 1} / {totalFrames}
            </p>
          )}
          <p className={['text-xs', dirty ? 'text-yellow-400' : 'text-green-400'].join(' ')}>
            {dirty ? '● 편집 중' : '✓ 저장됨'}
          </p>
        </div>
        <div className="flex-1 flex justify-end items-center gap-2">
          <span className="text-xs text-gray-400">{objectCount}개 객체</span>
          {portalMode ? (
            <div className="flex items-center gap-2">
              {autoLabelRunning && (
                <div className="w-32">
                  <ProgressBar value={autoLabelProgress} size="sm" tone="primary" />
                </div>
              )}
              <button
                onClick={handleAutoLabel}
                disabled={autoLabelRunning}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs text-white bg-orange-600 hover:bg-orange-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              >
                <Bot size={14} />
                {autoLabelRunning ? '처리 중...' : '오토라벨링 실행'}
              </button>
            </div>
          ) : (
            <button
              onClick={() => navigate(`/history/${videoId}`)}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs text-gray-300 hover:bg-gray-700 transition-colors border border-gray-600"
            >
              <GitBranch size={14} />
              히스토리
            </button>
          )}
          <button
            onClick={handleSave}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs text-white bg-blue-600 hover:bg-blue-700 transition-colors"
          >
            <Save size={14} />
            저장
          </button>
        </div>
      </header>

      {/* Main content */}
      <div className="flex flex-1 overflow-hidden">
        {/* Toolbar (56px wide) */}
        <Toolbar onSave={handleSave} />

        {/* Canvas area */}
        <div className="flex-1 relative overflow-hidden">
          <LabelCanvas videoId={videoId} frameNo={currentFrame} />
        </div>

        {/* Right panel (280px) */}
        <div className="w-72 flex flex-col bg-gray-800 border-l border-gray-700 overflow-hidden">
          {/* Object Tree (top half) */}
          <div className="flex-1 flex flex-col overflow-hidden border-b border-gray-700">
            <div className="px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wide border-b border-gray-700 shrink-0">
              객체 목록
            </div>
            <ObjectTree />
          </div>

          {/* Attribute Panel (bottom half) */}
          <div className="flex-1 flex flex-col overflow-hidden">
            <div className="px-3 py-2 text-xs font-semibold text-gray-400 uppercase tracking-wide border-b border-gray-700 shrink-0">
              속성
            </div>
            <AttributePanel />
          </div>
        </div>
      </div>

      {/* Bottom bar (120px: strip + slider) */}
      <div className="shrink-0 flex flex-col border-t border-gray-700" style={{ height: 120 }}>
        {/* Frame strip (60px) */}
        <div style={{ height: 60 }}>
          <FrameStrip videoId={videoId} />
        </div>
        {/* Frame slider (40px) */}
        <div style={{ height: 40 }}>
          <FrameSlider />
        </div>
      </div>
    </div>
  );
}
