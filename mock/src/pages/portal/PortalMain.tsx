import { useNavigate } from 'react-router-dom';
import { Upload, Tag, ArrowRight, Play } from 'lucide-react';
import { useFetch } from '../../api/queries';
import { UploadDropzone } from '../../components/portal/UploadDropzone';
import type { PortalUserDto, VideoDto, Page } from '../../api/types';

// Status badge
function StatusBadge({ status }: { status: string }) {
  const MAP: Record<string, { label: string; cls: string }> = {
    BATCH_COMPLETED: { label: '업로드완료', cls: 'bg-blue-100 text-blue-700' },
    PENDING: { label: '업로드완료', cls: 'bg-blue-100 text-blue-700' },
    IN_PROGRESS: { label: '라벨링중', cls: 'bg-yellow-100 text-yellow-700' },
    REVIEW_PENDING: { label: '라벨링중', cls: 'bg-yellow-100 text-yellow-700' },
    REVIEW: { label: '라벨링중', cls: 'bg-yellow-100 text-yellow-700' },
    COMPLETED: { label: '완료', cls: 'bg-green-100 text-green-700' },
    REJECTED: { label: '반려', cls: 'bg-red-100 text-red-700' },
  };
  const info = MAP[status] ?? { label: status, cls: 'bg-gray-100 text-gray-600' };
  return (
    <span className={['text-[10px] font-semibold px-1.5 py-0.5 rounded-full', info.cls].join(' ')}>
      {info.label}
    </span>
  );
}

export function PortalMain() {
  const navigate = useNavigate();

  const { data: me, isLoading: meLoading, refetch: refetchMe } = useFetch<PortalUserDto>('/portal/me');
  const { data: labelsPage, isLoading: labelsLoading } = useFetch<Page<VideoDto>>(
    '/portal/labels',
    { page: 0, size: 8 },
  );

  const videos = labelsPage?.content ?? [];
  const firstVideoId = videos[0]?.id;
  const pendingCount = videos.filter(
    (v) => v.taskStatus === 'PENDING' || v.taskStatus === 'BATCH_COMPLETED',
  ).length;

  return (
    <div className="min-h-full bg-gray-50">
      {/* Hero section */}
      <section className="bg-gradient-to-br from-orange-500 to-orange-600 text-white py-10 px-6">
        <div className="max-w-4xl mx-auto">
          <h1 className="text-2xl font-bold mb-2">AI 학습데이터 작성 포털</h1>
          <p className="text-orange-100 text-sm">
            영상/이미지 업로드, 간편 라벨링, 오토라벨링 체험
          </p>
        </div>
      </section>

      <div className="max-w-4xl mx-auto px-4 py-6 space-y-8">

        {/* Summary cards */}
        <section>
          <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">
            내 현황
          </h2>
          {meLoading ? (
            <div className="grid grid-cols-2 gap-4">
              {[0, 1].map((i) => (
                <div key={i} className="h-20 bg-gray-200 rounded-xl animate-pulse" />
              ))}
            </div>
          ) : (
            <div className="grid grid-cols-2 gap-4">
              <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-4 flex flex-col gap-1">
                <span className="text-xs text-gray-500">업로드 건수</span>
                <span className="text-2xl font-bold text-gray-800">
                  {me?.uploadCount ?? 0}
                </span>
                <span className="text-xs text-gray-400">건</span>
              </div>
              <div className="bg-white rounded-xl shadow-sm border border-gray-100 p-4 flex flex-col gap-1">
                <span className="text-xs text-gray-500">라벨링 완료</span>
                <span className="text-2xl font-bold text-gray-800">
                  {me?.labeledCount ?? 0}
                </span>
                <span className="text-xs text-gray-400">건</span>
              </div>
            </div>
          )}
        </section>

        {/* 3-step flow cards */}
        <section>
          <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">
            이용 방법
          </h2>
          <div className="flex flex-col md:flex-row gap-4">
            {/* Step 1: Upload */}
            <div className="flex-1 bg-white rounded-xl shadow-sm border border-gray-100 p-5 flex flex-col gap-3">
              <div className="flex items-center gap-2">
                <div className="w-7 h-7 rounded-full bg-orange-100 text-orange-600 flex items-center justify-center text-xs font-bold">
                  1
                </div>
                <Upload size={16} className="text-orange-500" />
                <span className="text-sm font-semibold text-gray-800">업로드</span>
              </div>
              <p className="text-xs text-gray-500 leading-relaxed">
                영상 또는 이미지를 업로드하세요
              </p>
              <UploadDropzone onUploadComplete={refetchMe} />
            </div>

            {/* Arrow */}
            <div className="hidden md:flex items-center text-gray-300 shrink-0">
              <ArrowRight size={20} />
            </div>

            {/* Step 2: Label */}
            <div className="flex-1 bg-white rounded-xl shadow-sm border border-gray-100 p-5 flex flex-col gap-3">
              <div className="flex items-center gap-2">
                <div className="w-7 h-7 rounded-full bg-orange-100 text-orange-600 flex items-center justify-center text-xs font-bold">
                  2
                </div>
                <Tag size={16} className="text-orange-500" />
                <span className="text-sm font-semibold text-gray-800">라벨링</span>
              </div>
              <p className="text-xs text-gray-500 leading-relaxed">
                업로드한 데이터에 라벨을 추가하세요
              </p>
              <p className="text-xs text-gray-600">
                현재 라벨링 필요{' '}
                <span className="font-semibold text-orange-600">{pendingCount}건</span>
              </p>
              <button
                onClick={() => {
                  if (firstVideoId) {
                    navigate(`/portal/label/${firstVideoId}`);
                  }
                }}
                disabled={!firstVideoId}
                className="mt-auto flex items-center justify-center gap-1.5 w-full py-2 rounded-lg text-sm font-medium bg-orange-500 text-white hover:bg-orange-600 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
              >
                <Play size={14} />
                시작하기
              </button>
            </div>
          </div>
          <p className="mt-3 text-xs text-gray-500">
            ※ 다운로드는 포털 자체 시스템에서 별도 제공됩니다.
          </p>
        </section>

        {/* My uploads grid */}
        <section id="my-uploads">
          <div className="flex items-center justify-between mb-3">
            <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide">
              내 업로드 목록
            </h2>
            {(labelsPage?.totalElements ?? 0) > 8 && (
              <button className="text-xs text-orange-600 hover:underline">
                전체 보기
              </button>
            )}
          </div>

          {labelsLoading ? (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              {[0, 1, 2, 3].map((i) => (
                <div key={i} className="h-32 bg-gray-200 rounded-xl animate-pulse" />
              ))}
            </div>
          ) : videos.length === 0 ? (
            <div className="text-center py-12 text-gray-400 text-sm">
              업로드한 데이터가 없습니다. 위에서 파일을 업로드해보세요.
            </div>
          ) : (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
              {videos.map((video) => (
                <div
                  key={video.id}
                  className="bg-white rounded-xl shadow-sm border border-gray-100 overflow-hidden flex flex-col"
                >
                  {/* Thumbnail */}
                  <div className="relative aspect-video bg-gray-200 overflow-hidden">
                    <img
                      src={`https://picsum.photos/seed/video-${video.id}/320/180`}
                      alt={video.cctvName}
                      loading="lazy"
                      className="w-full h-full object-cover"
                    />
                  </div>
                  {/* Info */}
                  <div className="p-2 flex flex-col gap-1 flex-1">
                    <p className="text-xs font-medium text-gray-700 truncate leading-tight">
                      {video.cctvName}
                    </p>
                    <div className="flex items-center justify-between">
                      <StatusBadge status={video.taskStatus ?? 'PENDING'} />
                    </div>
                    {video.taskStatus === 'COMPLETED' ? (
                      <button
                        onClick={() => navigate(`/portal/label/${video.id}`)}
                        className="mt-1 w-full py-1 text-[11px] font-medium rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50 transition-colors"
                      >
                        결과 보기
                      </button>
                    ) : (
                      <button
                        onClick={() => navigate(`/portal/label/${video.id}`)}
                        className="mt-1 w-full py-1 text-[11px] font-medium rounded-lg bg-orange-500 text-white hover:bg-orange-600 transition-colors flex items-center justify-center gap-1"
                      >
                        <Play size={10} />
                        라벨링
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}

export default PortalMain;
