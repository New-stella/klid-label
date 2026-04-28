import { useState, useEffect } from 'react';
import { Settings, Wifi, WifiOff, AlertTriangle, Save } from 'lucide-react';
import { useFetch, useMutation } from '../../api/queries';
import { api } from '../../api/client';
import { Button } from '../../components/ui/Button';
import { Skeleton } from '../../components/ui/Skeleton';
import { useToast } from '../../components/common/Toast';

interface ExternalSystem {
  name: string;
  url: string;
  status: 'CONNECTED' | 'DISCONNECTED';
}

interface FfmpegConfig {
  threads: number;
  outputFps: number;
  resolution: string;
}

interface AdminSettings {
  ffmpegConfig: FfmpegConfig;
  batchInterval: number;
  externalSystems: ExternalSystem[];
}

export function SystemSettings() {
  const { showToast } = useToast();

  const { data: settings, isLoading, refetch } = useFetch<AdminSettings>('/admin/settings');
  const { mutate: saveSettings, isLoading: saving } = useMutation<AdminSettings, AdminSettings>(
    (body) => api.put<AdminSettings>('/admin/settings', body),
  );

  const [threads, setThreads] = useState(4);
  const [outputFps, setOutputFps] = useState(1);
  const [resolution, setResolution] = useState('1920x1080');
  const [batchInterval, setBatchInterval] = useState(60);
  const [concurrentJobs, setConcurrentJobs] = useState(2);

  useEffect(() => {
    if (settings) {
      setThreads(settings.ffmpegConfig.threads);
      setOutputFps(settings.ffmpegConfig.outputFps);
      setResolution(settings.ffmpegConfig.resolution);
      setBatchInterval(settings.batchInterval);
    }
  }, [settings]);

  const handleSave = async () => {
    await saveSettings({
      ffmpegConfig: { threads, outputFps, resolution },
      batchInterval,
      externalSystems: settings?.externalSystems ?? [],
    });
    showToast('시스템 설정이 저장되었습니다.', 'success');
    refetch();
  };

  const handleResetDangerous = () => {
    showToast('위험 작업: 시스템 초기화는 개발 환경에서만 지원됩니다.', 'error');
  };

  if (isLoading) {
    return (
      <div className="p-6 space-y-6">
        <Skeleton height="2rem" width="30%" />
        <div className="grid grid-cols-2 gap-6">
          <Skeleton height="24rem" />
          <Skeleton height="24rem" />
        </div>
      </div>
    );
  }

  return (
    <div className="p-6 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="flex items-center justify-center w-9 h-9 rounded-lg bg-gray-100">
            <Settings size={20} className="text-gray-600" />
          </div>
          <h1 className="text-xl font-bold text-gray-900">시스템 설정</h1>
        </div>
        <Button
          variant="primary"
          size="md"
          leftIcon={Save}
          loading={saving}
          onClick={() => { void handleSave(); }}
        >
          설정 저장
        </Button>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-6 items-start">
        {/* Left: 배치 파이프라인 폼 */}
        <div className="bg-white border border-gray-200 rounded-lg p-5 space-y-5">
          <h2 className="text-sm font-semibold text-gray-700 border-b border-gray-100 pb-3">
            배치 파이프라인 설정
          </h2>

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
              onChange={(e) => setBatchInterval(Number(e.target.value))}
              className="w-full h-2 bg-gray-200 rounded-full appearance-none cursor-pointer accent-primary-600"
            />
            <div className="flex justify-between text-xs text-gray-400">
              <span>10s</span>
              <span>300s</span>
            </div>
          </div>

          {/* FFmpeg Threads */}
          <div className="space-y-2">
            <label className="flex items-center justify-between text-sm" htmlFor="ffmpeg-threads">
              <span className="font-medium text-gray-700">FFmpeg 스레드 수</span>
              <span className="text-primary-600 font-semibold tabular-nums">{threads}</span>
            </label>
            <input
              id="ffmpeg-threads"
              type="range"
              min={1}
              max={16}
              step={1}
              value={threads}
              onChange={(e) => setThreads(Number(e.target.value))}
              className="w-full h-2 bg-gray-200 rounded-full appearance-none cursor-pointer accent-primary-600"
            />
            <div className="flex justify-between text-xs text-gray-400">
              <span>1</span>
              <span>16</span>
            </div>
          </div>

          {/* Output FPS */}
          <div className="space-y-2">
            <label className="block text-sm font-medium text-gray-700" htmlFor="output-fps">
              프레임 추출 간격 (fps)
            </label>
            <select
              id="output-fps"
              value={outputFps}
              onChange={(e) => setOutputFps(Number(e.target.value))}
              className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
              <option value={1}>1 fps (1초당 1프레임)</option>
              <option value={2}>2 fps</option>
              <option value={5}>5 fps</option>
              <option value={10}>10 fps</option>
            </select>
          </div>

          {/* Resolution */}
          <div className="space-y-2">
            <label className="block text-sm font-medium text-gray-700" htmlFor="resolution">
              출력 해상도
            </label>
            <select
              id="resolution"
              value={resolution}
              onChange={(e) => setResolution(e.target.value)}
              className="w-full text-sm border border-gray-300 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-primary-500"
            >
              <option value="1920x1080">1920×1080 (FHD)</option>
              <option value="1280x720">1280×720 (HD)</option>
              <option value="3840x2160">3840×2160 (4K)</option>
            </select>
          </div>

          {/* 동시 처리 수 */}
          <div className="space-y-2">
            <label className="flex items-center justify-between text-sm" htmlFor="concurrent-jobs">
              <span className="font-medium text-gray-700">동시 처리 수</span>
              <span className="text-primary-600 font-semibold tabular-nums">{concurrentJobs}</span>
            </label>
            <input
              id="concurrent-jobs"
              type="range"
              min={1}
              max={8}
              step={1}
              value={concurrentJobs}
              onChange={(e) => setConcurrentJobs(Number(e.target.value))}
              className="w-full h-2 bg-gray-200 rounded-full appearance-none cursor-pointer accent-primary-600"
            />
            <div className="flex justify-between text-xs text-gray-400">
              <span>1</span>
              <span>8</span>
            </div>
          </div>
        </div>

        {/* Right: 외부 연동 상태 카드 */}
        <div className="space-y-4">
          <div className="bg-white border border-gray-200 rounded-lg p-5">
            <h2 className="text-sm font-semibold text-gray-700 border-b border-gray-100 pb-3 mb-4">
              외부 연동 상태
            </h2>
            <div className="space-y-3">
              {(settings?.externalSystems ?? []).map((sys) => (
                <div
                  key={sys.name}
                  className={[
                    'flex items-center justify-between p-3 rounded-lg border',
                    sys.status === 'CONNECTED'
                      ? 'border-green-200 bg-green-50'
                      : 'border-red-200 bg-red-50',
                  ].join(' ')}
                >
                  <div className="flex items-center gap-3">
                    {sys.status === 'CONNECTED' ? (
                      <Wifi size={16} className="text-green-600 shrink-0" />
                    ) : (
                      <WifiOff size={16} className="text-red-500 shrink-0" />
                    )}
                    <div>
                      <p className="text-sm font-medium text-gray-800">{sys.name}</p>
                      <p className="text-xs text-gray-400 truncate max-w-[200px]">{sys.url}</p>
                    </div>
                  </div>
                  <span
                    className={[
                      'text-xs font-semibold px-2 py-0.5 rounded-full',
                      sys.status === 'CONNECTED'
                        ? 'bg-green-100 text-green-700'
                        : 'bg-red-100 text-red-700',
                    ].join(' ')}
                  >
                    {sys.status === 'CONNECTED' ? '연결됨' : '연결 끊김'}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      {/* 위험 구역 */}
      <div className="bg-white border border-red-200 rounded-lg p-5 space-y-4">
        <div className="flex items-center gap-2 text-red-600">
          <AlertTriangle size={18} />
          <h2 className="text-sm font-semibold">위험 구역</h2>
        </div>
        <p className="text-xs text-gray-500">
          아래 작업은 되돌릴 수 없습니다. 신중하게 진행하세요.
        </p>
        <div className="flex flex-wrap gap-3">
          <Button
            variant="danger"
            size="sm"
            onClick={handleResetDangerous}
          >
            시스템 초기화 (개발 전용)
          </Button>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => showToast('배치 큐 초기화 요청이 전달되었습니다.', 'info')}
          >
            배치 큐 초기화
          </Button>
          <Button
            variant="secondary"
            size="sm"
            onClick={() => showToast('캐시 삭제가 완료되었습니다.', 'success')}
          >
            캐시 삭제
          </Button>
        </div>
      </div>
    </div>
  );
}

export default SystemSettings;
