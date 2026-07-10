import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, GitBranch } from 'lucide-react';

import { AuthImage } from '@/components/common/AuthImage';
import { BatchStageIndicator } from '@/components/common/BatchStageIndicator';
import { Button } from '@/components/common/Button';
import { Card } from '@/components/common/Card';
import { ErrorState } from '@/components/common/ErrorState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { Modal } from '@/components/common/Modal';
import { PrivacyBadge } from '@/components/common/PrivacyBadge';
import { Skeleton } from '@/components/common/Skeleton';
import { StatusBadge } from '@/components/common/StatusBadge';
import { Tabs } from '@/components/common/Tabs';
import { RedeidentButton } from '@/features/video/components/RedeidentButton';
import { useVideoDetail } from '@/features/video/hooks/useVideoDetail';
import { useVideoLabels } from '@/features/video/hooks/useVideoLabels';
import type { FramePreview, VideoDetail } from '@/features/video/types';
import { Role } from '@/lib/api/types';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useAuthStore } from '@/stores/useAuthStore';

function formatDuration(seconds: number | undefined): string {
  if (!seconds) return '-';
  if (seconds >= 3600) {
    const h = Math.floor(seconds / 3600);
    const m = Math.floor((seconds % 3600) / 60);
    return `${h}시간 ${m}분`;
  }
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}분 ${s}초`;
}

function InfoTab({ video }: { video: VideoDetail }) {
  const metaRows: { label: string; value: React.ReactNode }[] = [
    { label: 'CCTV ID', value: `video-${String(video.id).padStart(4, '0')}` },
    { label: '해상도', value: video.resolution || '-' },
    { label: '길이', value: formatDuration(video.durationSec ?? video.duration) },
    {
      label: '녹화 시각',
      value: video.capturedAt
        ? video.capturedAt.slice(0, 16).replace('T', ' ')
        : '-',
    },
    {
      label: '처리 단계',
      value:
        video.stages && video.stages.length > 0 ? (
          <div className="overflow-x-auto">
            <BatchStageIndicator stages={video.stages} />
          </div>
        ) : (
          <StatusBadge status={video.status} />
        ),
    },
    {
      label: '개인정보 분류',
      value: <PrivacyBadge privacyType={video.privacyTypeCd ?? ''} size="sm" />,
    },
    { label: '생성일', value: video.createdAt ? video.createdAt.slice(0, 10) : '-' },
    { label: '수정일', value: video.updatedAt ? video.updatedAt.slice(0, 10) : '-' },
  ];

  return (
    <div className="mt-4">
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        {metaRows.map((r) => (
          <div key={r.label} className="bg-gray-50 rounded-lg px-4 py-3">
            <p className="text-xs text-gray-500 mb-0.5">{r.label}</p>
            <div className="text-sm font-medium text-gray-800">{r.value}</div>
          </div>
        ))}
      </div>
    </div>
  );
}

function FramePreviewTab({
  video,
  onNavigateLabel,
}: {
  video: VideoDetail;
  onNavigateLabel: (srcSn: number) => void;
}) {
  const [lightboxFrame, setLightboxFrame] = useState<FramePreview | null>(null);

  const frames = video.framePreviews ?? [];

  if (frames.length === 0) {
    return (
      <p className="mt-4 text-sm text-gray-400 text-center py-8">
        프레임 데이터가 없습니다.
      </p>
    );
  }

  return (
    <>
      <div className="mt-4 grid grid-cols-3 sm:grid-cols-6 gap-2">
        {frames.map((f) => (
          <button
            key={f.frameNo}
            type="button"
            onClick={() => setLightboxFrame(f)}
            aria-label={`프레임 ${f.frameNo} 상세 보기`}
            className={`relative group rounded-md overflow-hidden ${KRDS_FOCUS}`}
          >
            <AuthImage
              srcSn={f.srcSn}
              alt={`Frame ${f.frameNo}`}
              className="w-full aspect-video object-cover bg-gray-100"
              width={160}
              height={90}
            />
            {f.hasIssue && (
              <span
                className="absolute top-1 right-1 w-2 h-2 rounded-full bg-danger"
                role="img"
                aria-label="이슈 있음"
              />
            )}
            <div className="absolute bottom-0 left-0 right-0 bg-black/50 text-white text-xs px-1 py-0.5 text-center">
              #{f.frameNo}
            </div>
          </button>
        ))}
      </div>

      <Modal
        open={lightboxFrame !== null}
        onClose={() => setLightboxFrame(null)}
        title={`프레임 #${lightboxFrame?.frameNo ?? ''}`}
        size="xl"
        footer={
          <>
            <Button variant="secondary" onClick={() => setLightboxFrame(null)}>
              닫기
            </Button>
            {lightboxFrame && (
              <Button
                variant="primary"
                onClick={() => {
                  onNavigateLabel(lightboxFrame.srcSn);
                  setLightboxFrame(null);
                }}
              >
                라벨링 편집
              </Button>
            )}
          </>
        }
      >
        {lightboxFrame && (
          <div className="flex flex-col items-center gap-3">
            <AuthImage
              srcSn={lightboxFrame.srcSn}
              alt={`Frame ${lightboxFrame.frameNo}`}
              className="w-full rounded-lg object-contain max-h-80 bg-gray-100"
              width={640}
              height={360}
            />
            <div className="flex gap-4 text-sm text-gray-500">
              <span>프레임 #{lightboxFrame.frameNo}</span>
              {lightboxFrame.timestampMs !== undefined && (
                <span>{lightboxFrame.timestampMs} ms</span>
              )}
              {lightboxFrame.hasIssue && <span>이슈 있음</span>}
            </div>
          </div>
        )}
      </Modal>
    </>
  );
}

function AutoLabelTab({ videoId }: { videoId: number | string }) {
  const { data, isLoading, isError } = useVideoLabels(videoId);
  const labels = data?.objects ?? [];

  if (isLoading) {
    return (
      <div className="mt-4 space-y-3">
        <div className="h-16 bg-gray-100 animate-pulse rounded-lg" />
        <div className="h-16 bg-gray-100 animate-pulse rounded-lg" />
        <div className="h-16 bg-gray-100 animate-pulse rounded-lg" />
      </div>
    );
  }

  if (isError) {
    return (
      <div className="mt-4 flex flex-col items-center justify-center py-12 gap-2">
        <p className="text-danger text-sm">오토라벨 결과를 불러올 수 없습니다.</p>
        <p className="text-gray-400 text-xs">잠시 후 다시 시도해 주세요.</p>
      </div>
    );
  }

  if (labels.length === 0) {
    return (
      <div className="mt-4 flex flex-col items-center justify-center py-12 gap-2">
        <p className="text-gray-500 text-sm">오토라벨 결과가 없습니다.</p>
        <p className="text-gray-400 text-xs">배치 처리 완료 후 결과가 표시됩니다.</p>
      </div>
    );
  }

  // 집계
  const totalLabels = labels.length;
  const autoLabels = labels.filter((l) => !l.createdBy || l.createdBy === 'auto');
  const autoCount = autoLabels.length;
  const autoRate = totalLabels > 0 ? ((autoCount / totalLabels) * 100).toFixed(1) : '0.0';

  // 신뢰도 분포
  const high = autoLabels.filter((l) => l.confidence >= 0.9).length;
  const mid = autoLabels.filter((l) => l.confidence >= 0.7 && l.confidence < 0.9).length;
  const low = autoLabels.filter((l) => l.confidence < 0.7).length;

  // 라벨별 분포
  const labelMap: Record<string, { name: string; count: number; color: string }> = {};
  labels.forEach((l) => {
    if (!labelMap[l.labelCode])
      labelMap[l.labelCode] = { name: l.labelName, count: 0, color: l.color ?? '#4ECDC4' };
    labelMap[l.labelCode].count++;
  });
  const labelCounts = Object.entries(labelMap).sort(([, a], [, b]) => b.count - a.count);
  const maxCount = labelCounts[0]?.[1]?.count ?? 1;

  return (
    <div className="mt-4 flex flex-col gap-6">
      {/* 처리 정보 */}
      <div>
        <h4 className="text-sm font-semibold text-gray-700 mb-3">처리 정보</h4>
        <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
          {[
            { label: '총 라벨 수', value: totalLabels.toLocaleString('ko-KR') },
            { label: '오토라벨 수', value: autoCount.toLocaleString('ko-KR') },
            { label: '오토라벨 비율', value: `${autoRate}%` },
            { label: '처리 상태', value: 'COMPLETED' },
          ].map((item) => (
            <div key={item.label} className="bg-gray-50 rounded-lg p-3">
              <p className="text-xs text-gray-500 mb-0.5">{item.label}</p>
              <p className="text-sm font-semibold text-gray-800">{item.value}</p>
            </div>
          ))}
        </div>
      </div>

      {/* 신뢰도 분포 */}
      {(() => {
        // 신뢰도 버킷 색 — KRDS 의미상태색 토큰 (고=success, 중=warning, 저=danger).
        // ※ 아래 라벨별 분포 막대의 `backgroundColor: color`(l.color ?? '#4ECDC4')는 라벨 고유색이라 불변.
        const buckets = [
          { label: '0.9+', count: high, color: 'bg-success' },
          { label: '0.7~0.9', count: mid, color: 'bg-warning' },
          { label: '<0.7', count: low, color: 'bg-danger' },
        ];
        const max = Math.max(...buckets.map((b) => b.count), 1);
        return (
          <div>
            <h4 className="text-sm font-semibold text-gray-700 mb-1">신뢰도 분포 (오토라벨)</h4>
            <p className="text-xs text-gray-400 mb-2">오토라벨 {autoCount}건 기준</p>
            {autoCount === 0 ? (
              <p className="text-sm text-gray-400 py-4 text-center">오토라벨 데이터가 없습니다.</p>
            ) : (
              <div className="flex items-end gap-4 h-24 mt-2">
                {buckets.map((b) => (
                  <div key={b.label} className="flex flex-col items-center gap-1 flex-1">
                    <span className="text-xs font-semibold text-gray-700 tabular-nums">{b.count}</span>
                    <div className="w-full flex items-end" style={{ height: '60px' }}>
                      <div
                        className={[b.color, 'w-full rounded-t transition-all'].join(' ')}
                        style={{ height: `${Math.round((b.count / max) * 60)}px` }}
                      />
                    </div>
                    <span className="text-xs text-gray-500">{b.label}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })()}

      {/* 라벨별 분포 */}
      <div>
        <h4 className="text-sm font-semibold text-gray-700 mb-1">라벨별 분포</h4>
        {labelCounts.length === 0 ? (
          <p className="text-sm text-gray-400 py-4 text-center">라벨 데이터가 없습니다.</p>
        ) : (
          <div className="space-y-2 mt-2">
            {labelCounts.slice(0, 10).map(([code, { name, count, color }]) => (
              <div key={code} className="flex items-center gap-2">
                <span className="text-xs text-gray-600 w-20 truncate shrink-0">{name}</span>
                <div className="flex-1 bg-gray-100 rounded-full h-3 overflow-hidden">
                  <div
                    className="h-full rounded-full transition-all"
                    style={{
                      width: `${Math.round((count / maxCount) * 100)}%`,
                      backgroundColor: color,
                    }}
                  />
                </div>
                <span className="text-xs tabular-nums text-gray-500 w-8 text-right">{count}</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * SCR-VIDEO-003 영상 상세 (라우트 페이지).
 * 헤더 카드 + 3개 탭 (기본 정보 / 프레임 미리보기 / 오토라벨 결과).
 *
 * 보안: id는 number로 검증. video.cctvName 등은 React 자동 escape.
 */
export function VideoDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState('info');

  const numericId = Number.parseInt(id ?? '', 10);
  const validId = Number.isFinite(numericId) && numericId > 0 ? numericId : null;
  const { data, isLoading, error } = useVideoDetail(validId);

  // SC-009 재비식별 버튼 노출 가드: REVIEWER + 검수완료(APPROVED) + 비식별 미완(deIdntfYn !== 'Y').
  // 검수완료 판정은 reviewSttsCd(=LS_RAW_DATA_STATUS.DATA_STTS_CD, 진실원)로 한다.
  //   ※ status(=배치단계 LS_DATA_RAW.DATA_STTS_CD)는 종착이 COMPLETED 라 절대 APPROVED 가 되지 않으므로
  //     status 로 판정하면 버튼이 영구 미노출된다(과거 결함). 권한 가드는 UX 편의일 뿐 — 실제 강제는 BE(403).
  const role = useAuthStore((s) => s.claims?.role ?? null);
  const canReDeident =
    role === Role.REVIEWER && data?.reviewSttsCd === 'APPROVED' && data?.deIdntfYn !== 'Y';

  if (validId === null) {
    return <ErrorState title="잘못된 영상 ID" message="유효한 영상 ID가 필요합니다." />;
  }

  const tabs = [
    { value: 'info', label: '기본 정보' },
    { value: 'frames', label: '프레임 미리보기' },
    { value: 'autolabel', label: '오토라벨 결과' },
  ];

  const thumbnailSrcSn = data?.framePreviews?.[0]?.srcSn ?? null;

  return (
    <div className="space-y-4">
      <button
        type="button"
        onClick={() => navigate(-1)}
        className="flex items-center gap-1.5 text-sm text-gray-500 hover:text-gray-800 transition-colors"
      >
        <ArrowLeft size={16} aria-hidden />
        뒤로가기
      </button>

      {isLoading && (
        <div className="space-y-4">
          <Skeleton height={160} className="rounded-lg" />
          <Skeleton height={300} className="rounded-lg" />
        </div>
      )}

      {error && <ErrorState title="영상 정보를 불러올 수 없습니다" />}

      {data && (
        <>
          <Card>
            <div className="flex flex-wrap items-start gap-3">
              {thumbnailSrcSn ? (
                <AuthImage
                  srcSn={thumbnailSrcSn}
                  alt={data.cctvName}
                  className="w-32 h-20 object-cover rounded-lg bg-gray-100 shrink-0"
                  width={128}
                  height={80}
                />
              ) : (
                <div className="w-32 h-20 rounded-lg bg-gray-200 shrink-0 flex items-center justify-center">
                  <span className="text-xs text-gray-400">미리보기 없음</span>
                </div>
              )}
              <div className="flex-1 min-w-0">
                <div className="flex items-start justify-between gap-2">
                  <h2 className="text-lg font-bold text-gray-900">{data.cctvName}</h2>
                  <div className="flex items-center gap-2 shrink-0">
                    {canReDeident && <RedeidentButton rawSn={data.id} />}
                    <Button
                      variant="secondary"
                      size="sm"
                      onClick={() => navigate(`/history/${data.id}`)}
                    >
                      <GitBranch size={14} aria-hidden />
                      버전관리로 이동
                    </Button>
                  </div>
                </div>
                <div className="flex flex-wrap gap-2 mt-1.5">
                  <EventTypeBadge eventType={data.eventTypeCd ?? data.eventName ?? ''} size="md" />
                  <StatusBadge status={data.status} />
                </div>
                <div className="flex flex-wrap gap-4 mt-2 text-sm text-gray-500">
                  <span>길이: {formatDuration(data.durationSec ?? data.duration)}</span>
                  <span>
                    녹화일: {data.capturedAt ? data.capturedAt.slice(0, 10) : '-'}
                  </span>
                </div>
              </div>
            </div>
          </Card>

          <div className="bg-white border border-gray-200 rounded-lg shadow-sm">
            <div className="px-4 pt-4">
              <Tabs items={tabs} value={activeTab} onChange={setActiveTab} />
            </div>
            <div className="px-6 pb-6">
              {activeTab === 'info' && <InfoTab video={data} />}
              {activeTab === 'frames' && (
                <FramePreviewTab
                  video={data}
                  onNavigateLabel={(srcSn) => navigate(`/label/${srcSn}`)}
                />
              )}
              {activeTab === 'autolabel' && <AutoLabelTab videoId={data.id} />}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
