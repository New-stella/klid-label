import { useState, useEffect } from 'react';
import {
  Settings,
  Wifi,
  WifiOff,
  AlertTriangle,
  Save,
  Eye,
  Clock,
} from 'lucide-react';
import { useFetch, useMutation } from '../../api/queries';
import { api } from '../../api/client';
import { Button } from '../../components/ui/Button';
import { Skeleton } from '../../components/ui/Skeleton';
import { useToast } from '../../components/common/Toast';

// ── 도메인 타입 ─────────────────────────────────────────────────────────────

interface FfmpegConfig {
  threads: number;
  outputFps: number;
}

interface BatchConfig {
  interval: number;
  concurrency: number;
}

interface ExternalSystemStatus {
  name: string;
  url: string;
  status: 'CONNECTED' | 'DISCONNECTED';
  latencyMs: number;
}

interface ManageSettings {
  ffmpegConfig: FfmpegConfig;
  batchConfig: BatchConfig;
  /** @deprecated 하위호환용 */
  batchInterval: number;
  externalSystems: ExternalSystemStatus[];
}

// ── 컴포넌트 ────────────────────────────────────────────────────────────────

export function SystemSettings() {
  const { showToast } = useToast();

  const { data: settings, isLoading } = useFetch<ManageSettings>('/manage/settings');

  // FFmpeg 폼 상태
  const [threads, setThreads] = useState(4);
  const [outputFps, setOutputFps] = useState(1);
  const [ffmpegDirty, setFfmpegDirty] = useState(false);

  // 배치 폼 상태
  const [batchInterval, setBatchInterval] = useState(60);
  const [concurrency, setConcurrency] = useState(2);
  const [batchDirty, setBatchDirty] = useState(false);

  const { mutate: saveFfmpeg, isLoading: savingFfmpeg } = useMutation<FfmpegConfig, FfmpegConfig>(
    (body) => api.put<FfmpegConfig>('/manage/settings/ffmpeg', body),
  );

  const { mutate: saveBatch, isLoading: savingBatch } = useMutation<BatchConfig, BatchConfig>(
    (body) => api.put<BatchConfig>('/manage/settings/batch', body),
  );

  // 서버 데이터 → 폼 초기화
  useEffect(() => {
    if (!settings) return;
    setThreads(settings.ffmpegConfig.threads);
    setOutputFps(settings.ffmpegConfig.outputFps);
    setBatchInterval(settings.batchConfig?.interval ?? settings.batchInterval ?? 60);
    setConcurrency(settings.batchConfig?.concurrency ?? 2);
    // 초기화 후 dirty 초기화
    setFfmpegDirty(false);
    setBatchDirty(false);
  }, [settings]);

  // ── 저장 핸들러 ────────────────────────────────────────────────────────────

  const handleSaveFfmpeg = async () => {
    await saveFfmpeg({ threads, outputFps });
    showToast('FFmpeg 설정이 저장되었습니다.', 'success');
    setFfmpegDirty(false);
  };

  const handleSaveBatch = async () => {
    await saveBatch({ interval: batchInterval, concurrency });
    showToast('배치 처리 설정이 저장되었습니다.', 'success');
    setBatchDirty(false);
  };

  // ── 위험 액션 핸들러 ────────────────────────────────────────────────────────

  const handleSystemReset = () => {
    if (!window.confirm('시스템을 초기화하시겠습니까? 이 작업은 데모 동작만 수행합니다.')) return;
    showToast('위험 작업: 시스템 초기화는 개발 환경에서만 지원됩니다.', 'error');
  };

  const handleBatchQueueReset = () => {
    if (!window.confirm('배치 큐를 초기화하시겠습니까? 이 작업은 데모 동작만 수행합니다.')) return;
    showToast('배치 큐 초기화 요청이 전달되었습니다.', 'info');
  };

  const handleCacheClear = () => {
    if (!window.confirm('캐시를 삭제하시겠습니까? 이 작업은 데모 동작만 수행합니다.')) return;
    showToast('캐시 삭제가 완료되었습니다.', 'success');
  };

  // ── 로딩 스켈레톤 ──────────────────────────────────────────────────────────

  if (isLoading) {
    return (
      <div className="p-6 space-y-6">
        <Skeleton height="2rem" width="30%" />
        <div className="grid grid-cols-2 gap-6">
          <Skeleton height="24rem" />
          <Skeleton height="24rem" />
        </div>
        <Skeleton height="10rem" />
        <Skeleton height="8rem" />
      </div>
    );
  }

  const externalSystems = settings?.externalSystems ?? [];

  // ── 렌더링 ─────────────────────────────────────────────────────────────────

  return (
    <div className="p-6 space-y-8">
      {/* 페이지 헤더 */}
      <div className="flex items-center gap-3">
        <div className="flex items-center justify-center w-9 h-9 rounded-lg bg-gray-100">
          <Settings size={20} className="text-gray-600" />
        </div>
        <h1 className="text-xl font-bold text-gray-900">시스템 설정</h1>
      </div>

      {/* ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
          섹션 1: 편집 가능 영역 (DB 영속화)
         ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ */}
      <section className="space-y-4">
        <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide">
          편집 가능 — DB 영속화
        </h2>

        <div className="grid grid-cols-1 md:grid-cols-2 gap-6 items-start">
          {/* FFmpeg 설정 카드 */}
          <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-5">
            <div className="flex items-center justify-between border-b border-gray-100 pb-3">
              <h3 className="text-sm font-semibold text-gray-700">FFmpeg 설정</h3>
              <Button
                variant="primary"
                size="sm"
                leftIcon={Save}
                loading={savingFfmpeg}
                disabled={!ffmpegDirty}
                onClick={() => { void handleSaveFfmpeg(); }}
              >
                저장
              </Button>
            </div>

            {/* FFmpeg 스레드 수 */}
            <div className="space-y-2">
              <label className="flex items-center justify-between text-sm" htmlFor="ffmpeg-threads">
                <span className="font-medium text-gray-700">스레드 수</span>
                <span className="text-primary-600 font-semibold tabular-nums">{threads}</span>
              </label>
              <input
                id="ffmpeg-threads"
                type="range"
                min={1}
                max={16}
                step={1}
                value={threads}
                onChange={(e) => { setThreads(Number(e.target.value)); setFfmpegDirty(true); }}
                className="w-full h-2 bg-gray-200 rounded-full appearance-none cursor-pointer accent-primary-600"
              />
              <div className="flex justify-between text-xs text-gray-400">
                <span>1</span>
                <span>16</span>
              </div>
            </div>

            {/* 프레임 추출 간격 */}
            <div className="space-y-2">
              <label className="block text-sm font-medium text-gray-700" htmlFor="output-fps">
                프레임 추출 간격 (fps)
              </label>
              <select
                id="output-fps"
                value={outputFps}
                onChange={(e) => { setOutputFps(Number(e.target.value)); setFfmpegDirty(true); }}
                className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
              >
                <option value={1}>1 fps (1초당 1프레임)</option>
                <option value={2}>2 fps</option>
                <option value={5}>5 fps</option>
                <option value={10}>10 fps</option>
              </select>
            </div>

          </div>

          {/* 배치 처리 카드 */}
          <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-5">
            <div className="flex items-center justify-between border-b border-gray-100 pb-3">
              <h3 className="text-sm font-semibold text-gray-700">배치 처리</h3>
              <Button
                variant="primary"
                size="sm"
                leftIcon={Save}
                loading={savingBatch}
                disabled={!batchDirty}
                onClick={() => { void handleSaveBatch(); }}
              >
                저장
              </Button>
            </div>

            {/* 처리 주기 */}
            <div className="space-y-2">
              <label className="flex items-center justify-between text-sm" htmlFor="batch-interval">
                <span className="font-medium text-gray-700">처리 주기 (초)</span>
                <span className="text-primary-600 font-semibold tabular-nums">{batchInterval}s</span>
              </label>
              <input
                id="batch-interval"
                type="range"
                min={10}
                max={300}
                step={10}
                value={batchInterval}
                onChange={(e) => { setBatchInterval(Number(e.target.value)); setBatchDirty(true); }}
                className="w-full h-2 bg-gray-200 rounded-full appearance-none cursor-pointer accent-primary-600"
              />
              <div className="flex justify-between text-xs text-gray-400">
                <span>10s</span>
                <span>300s</span>
              </div>
            </div>

            {/* 동시 처리 수 */}
            <div className="space-y-2">
              <label className="flex items-center justify-between text-sm" htmlFor="concurrent-jobs">
                <span className="font-medium text-gray-700">동시 처리 수</span>
                <span className="text-primary-600 font-semibold tabular-nums">{concurrency}</span>
              </label>
              <input
                id="concurrent-jobs"
                type="range"
                min={1}
                max={8}
                step={1}
                value={concurrency}
                onChange={(e) => { setConcurrency(Number(e.target.value)); setBatchDirty(true); }}
                className="w-full h-2 bg-gray-200 rounded-full appearance-none cursor-pointer accent-primary-600"
              />
              <div className="flex justify-between text-xs text-gray-400">
                <span>1</span>
                <span>8</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      {/* ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
          섹션 2: 실시간 모니터링 (read-only)
         ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ */}
      <section className="space-y-4">
        <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide">
          실시간 모니터링
        </h2>

        <div className="bg-white border border-gray-200 rounded-lg p-5">
          {/* 카드 헤더 */}
          <div className="flex items-center justify-between border-b border-gray-100 pb-3 mb-4">
            <h3 className="text-sm font-semibold text-gray-700">외부 연동 상태</h3>
            <span className="flex items-center gap-1.5 text-xs font-medium text-blue-600 bg-blue-50 border border-blue-100 rounded-full px-2.5 py-1">
              <Eye size={12} />
              실시간 모니터링
            </span>
          </div>

          {/* 연동 목록 */}
          <div className="space-y-3">
            {externalSystems.map((sys) => {
              const isConnected = sys.status === 'CONNECTED';
              const isSlowLatency = sys.latencyMs > 100;
              return (
                <div
                  key={sys.name}
                  className={[
                    'flex items-center justify-between p-3 rounded-lg border',
                    isConnected
                      ? isSlowLatency
                        ? 'border-yellow-200 bg-yellow-50'
                        : 'border-green-200 bg-green-50'
                      : 'border-red-200 bg-red-50',
                  ].join(' ')}
                >
                  <div className="flex items-center gap-3">
                    {isConnected ? (
                      <Wifi
                        size={16}
                        className={isSlowLatency ? 'text-yellow-500 shrink-0' : 'text-green-600 shrink-0'}
                      />
                    ) : (
                      <WifiOff size={16} className="text-red-500 shrink-0" />
                    )}
                    <div>
                      <p className="text-sm font-medium text-gray-800">{sys.name}</p>
                      <p className="text-xs text-gray-400 truncate max-w-[200px]">{sys.url}</p>
                    </div>
                  </div>

                  <div className="flex items-center gap-3">
                    {/* 레이턴시 */}
                    <span className="flex items-center gap-1 text-xs text-gray-500">
                      <Clock size={11} />
                      {sys.latencyMs}ms
                    </span>
                    {/* 상태 뱃지 */}
                    <span
                      className={[
                        'text-xs font-semibold px-2 py-0.5 rounded-full',
                        isConnected
                          ? isSlowLatency
                            ? 'bg-yellow-100 text-yellow-700'
                            : 'bg-green-100 text-green-700'
                          : 'bg-red-100 text-red-700',
                      ].join(' ')}
                    >
                      {isConnected ? (isSlowLatency ? '지연' : '정상') : '연결 끊김'}
                    </span>
                  </div>
                </div>
              );
            })}
          </div>

          {/* 카드 푸터 안내 */}
          <p className="mt-4 text-xs text-gray-400 flex items-start gap-1.5">
            <Eye size={12} className="mt-0.5 shrink-0" />
            이 항목은 actuator/health에서 실시간 조회되며 편집할 수 없습니다.
          </p>
        </div>
      </section>

      {/* ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
          섹션 3: 위험 액션 (운영 도구 이관 예정 — placeholder)
         ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━ */}
      <section className="space-y-4">
        <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide">
          위험 액션
        </h2>

        <div className="bg-white border border-red-200 rounded-lg p-5 space-y-4">
          {/* 운영 도구 이관 예정 안내 배너 */}
          <div className="flex items-start gap-2 rounded-lg bg-yellow-50 border border-yellow-200 px-4 py-3">
            <AlertTriangle size={16} className="text-yellow-600 shrink-0 mt-0.5" />
            <p className="text-xs text-yellow-800 leading-relaxed">
              위험 액션은 별도 운영 도구로 이관 예정입니다. 본 화면에서는 데모 동작만 수행됩니다.
            </p>
          </div>

          <div className="flex items-center gap-2 text-red-600">
            <AlertTriangle size={16} />
            <h3 className="text-sm font-semibold">위험 구역</h3>
          </div>

          <p className="text-xs text-gray-500">
            아래 작업은 되돌릴 수 없습니다. 신중하게 진행하세요.
          </p>

          <div className="flex flex-wrap gap-3">
            <Button
              variant="danger"
              size="sm"
              onClick={handleSystemReset}
            >
              시스템 초기화 (개발 전용)
            </Button>
            <Button
              variant="secondary"
              size="sm"
              onClick={handleBatchQueueReset}
            >
              배치 큐 초기화
            </Button>
            <Button
              variant="secondary"
              size="sm"
              onClick={handleCacheClear}
            >
              캐시 삭제
            </Button>
          </div>
        </div>
      </section>
    </div>
  );
}

export default SystemSettings;
