import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';

import { AuthImage } from '@/components/common/AuthImage';
import { BatchStageIndicator } from '@/components/common/BatchStageIndicator';
import { Button } from '@/components/common/Button';
import { Card, CardContent } from '@/components/common/Card';
import { ErrorState } from '@/components/common/ErrorState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { Modal } from '@/components/common/Modal';
import { Skeleton } from '@/components/common/Skeleton';
import { StatusBadge } from '@/components/common/StatusBadge';
import { Tabs } from '@/components/common/Tabs';
import { BatchFailurePanel } from '@/features/video/components/BatchFailurePanel';
import { DeidentHistoryPanel } from '@/features/video/components/DeidentHistoryPanel';
import {
  BATCH_PROCESSING_POLL_WINDOW_MS,
  useVideoDetail,
} from '@/features/video/hooks/useVideoDetail';
import { useVideoLabels } from '@/features/video/hooks/useVideoLabels';
import { isBatchProcessing } from '@/features/video/types';
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

function InfoTab({ video, isReviewer }: { video: VideoDetail; isReviewer: boolean }) {
  // [@design SCREEN-009] 메타 그리드에서 '처리 단계'를 뺀다 — 확정 디자인의 처리 단계는 그리드
  //   셀이 아니라 **콘텐츠 전폭 밴드**(design-main.html `.stage-row`)다. 2열 그리드 셀(≈530px)에
  //   가두면 7단계 노드와 캡션이 압축돼 판독이 안 된다.
  // [req: R2] '개인정보 분류' 항목은 두지 않는다 — 관제서버가 개인정보 유무를 실제로 보내지 않고
  //   privacyTypeCd 는 적재 시 고정되는 레거시 컬럼이다(BE 응답 계약은 그대로 유지).
  const metaRows: { label: string; value: React.ReactNode; mono?: boolean }[] = [
    { label: 'CCTV ID', value: `video-${String(video.id).padStart(4, '0')}`, mono: true },
    { label: '해상도', value: video.resolution || '-', mono: true },
    { label: '길이', value: formatDuration(video.durationSec ?? video.duration), mono: true },
    {
      label: '녹화 시각',
      value: video.capturedAt ? video.capturedAt.slice(0, 16).replace('T', ' ') : '-',
    },
    { label: '생성일', value: video.createdAt ? video.createdAt.slice(0, 10) : '-' },
    { label: '수정일', value: video.updatedAt ? video.updatedAt.slice(0, 10) : '-' },
  ];

  return (
    <div className="flex flex-col gap-6">
      {/* [@design SCREEN-009] 배경 타일이 아니라 배경 없는 label/value 2열(`<dl>`).
          확정 디자인의 `.meta-grid` 와 같은 골격 — 회색 박스는 정보 밀도를 떨어뜨린다. */}
      <dl className="grid grid-cols-1 gap-x-6 gap-y-4 md:grid-cols-2">
        {metaRows.map((r) => (
          <div key={r.label} className="flex flex-col gap-0.5">
            <dt className="text-label text-gray-600">{r.label}</dt>
            <dd
              className={[
                'm-0 text-body-sm text-gray-900',
                r.mono ? 'tabular-nums' : '',
              ].join(' ')}
            >
              {r.value}
            </dd>
          </div>
        ))}
      </dl>

      {/* [@design SCREEN-009] 처리 단계 — 콘텐츠 전폭 밴드. 위 그리드와 구분선으로 나눈다.
          ★표시기는 **칸을 전폭에 균등 분산**한다 — 밴드를 전폭으로 빼내도 표시기 자신이 내용 폭이면
          노드가 좌측에 뭉쳐 있어(실측 886px 밴드에 273px 만 사용) 원래 고치려던 판독성 저하가
          그대로 남는다.
          ⚠ **[폐기]** 구 서술 「`fill` 을 켜는 자리는 여기 하나다 / 마킹 화면은 헤더 행 인라인이라
          켜지 않는다」 — 마킹 화면이 이 표시기를 더 쓰지 않아 분기의 근거가 사라졌고, `fill` prop 과
          비-fill 렌더 경로를 함께 걷어냈다(전폭 분산이 유일한 렌더). */}
      <div className="border-t border-gray-200 pt-4">
        <p className="mb-2 text-label text-gray-600">처리 단계</p>
        {video.stages && video.stages.length > 0 ? (
          <div className="overflow-x-auto">
            <BatchStageIndicator stages={video.stages} />
          </div>
        ) : (
          <StatusBadge status={video.status} />
        )}
      </div>

      {/* [@design SCREEN-009] 배치 실패 사유 + 조치 — 처리 단계 바로 아래(같은 관심사의 연속).
          ★조작 버튼을 BatchStageIndicator 안에 두지 않는 이유: 그 표시기는 «어느 단계까지 왔는가»만
          말하고 조작은 그 바깥의 관심사다(사양 SCREEN-009 가 표시기 안에 두는 것을 명시적으로 금지).
          ⚠ **[폐기]** 구 근거 「그 표시기는 마킹 화면과 공유하므로 버튼을 넣으면 마킹 화면에도 함께
          나타난다」 — 마킹 화면이 표시기를 더 쓰지 않아 그 서술은 사실이 아니다. 근거가 하나 사라졌을
          뿐 **금지는 그대로**이며, 사용처가 한 곳이 됐다는 사실은 둘을 합칠 근거가 아니다.
          REVIEWER 전용 — 권한 가드는 UX 편의일 뿐 실제 강제는 BE(403)다. */}
      {isReviewer && <BatchFailurePanel video={video} />}
      {/* [req: R14] 비식별 이력 — 처리 단계(BatchStageIndicator) 바로 아래에 둔다.
          단계 표시가 "지금 어디까지 왔나" 라면 이력은 "몇 번 어떻게 처리했나" 로, 같은 관심사의
          연속이라 탭을 옮기지 않고 이어 붙인다. */}
      <DeidentHistoryPanel history={video.deidentHistory ?? []} />
    </div>
  );
}

function FramePreviewTab({ video }: { video: VideoDetail }) {
  const [lightboxFrame, setLightboxFrame] = useState<FramePreview | null>(null);

  const frames = video.framePreviews ?? [];

  if (frames.length === 0) {
    return (
      <p className="text-body-md text-gray-400 text-center py-8">프레임 데이터가 없습니다.</p>
    );
  }

  return (
    <>
      <div className="grid grid-cols-3 md:grid-cols-6 gap-2">
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
            <div className="absolute bottom-0 left-0 right-0 bg-black/50 text-white text-caption px-1 py-0.5 text-center">
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
          <Button variant="secondary" onClick={() => setLightboxFrame(null)}>
            닫기
          </Button>
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
            <div className="flex gap-4 text-body-md text-gray-500">
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
      <div className="space-y-3">
        <div className="h-16 bg-gray-100 animate-pulse rounded-lg" />
        <div className="h-16 bg-gray-100 animate-pulse rounded-lg" />
        <div className="h-16 bg-gray-100 animate-pulse rounded-lg" />
      </div>
    );
  }

  if (isError) {
    return (
      <div className="flex flex-col items-center justify-center py-12 gap-2">
        <p className="text-danger text-body-md">오토라벨 결과를 불러올 수 없습니다.</p>
        <p className="text-gray-400 text-caption">잠시 후 다시 시도해 주세요.</p>
      </div>
    );
  }

  if (labels.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 gap-2">
        <p className="text-gray-500 text-body-md">오토라벨 결과가 없습니다.</p>
        <p className="text-gray-400 text-caption">배치 처리 완료 후 결과가 표시됩니다.</p>
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
    <div className="flex flex-col gap-6">
      {/* 처리 정보 */}
      <div>
        <h4 className="text-title-sm font-semibold text-gray-700 mb-3">처리 정보</h4>
        <div className="grid grid-cols-2 md:grid-cols-3 gap-3">
          {[
            { label: '총 라벨 수', value: totalLabels.toLocaleString('ko-KR') },
            { label: '오토라벨 수', value: autoCount.toLocaleString('ko-KR') },
            { label: '오토라벨 비율', value: `${autoRate}%` },
            { label: '처리 상태', value: 'COMPLETED' },
          ].map((item) => (
            <div key={item.label} className="bg-gray-50 rounded-lg p-3">
              <p className="text-caption text-gray-600 mb-0.5">{item.label}</p>
              <p className="text-body-md font-semibold text-gray-800">{item.value}</p>
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
            <h4 className="text-title-sm font-semibold text-gray-700 mb-1">
              신뢰도 분포 (오토라벨)
            </h4>
            <p className="text-caption text-gray-400 mb-2">오토라벨 {autoCount}건 기준</p>
            {autoCount === 0 ? (
              <p className="text-body-md text-gray-400 py-4 text-center">
                오토라벨 데이터가 없습니다.
              </p>
            ) : (
              <div className="flex items-end gap-4 h-24 mt-2">
                {buckets.map((b) => (
                  <div key={b.label} className="flex flex-col items-center gap-1 flex-1">
                    <span className="text-label font-semibold text-gray-700 tabular-nums">
                      {b.count}
                    </span>
                    <div className="w-full flex items-end" style={{ height: '60px' }}>
                      <div
                        className={[b.color, 'w-full rounded-t transition-all'].join(' ')}
                        style={{ height: `${Math.round((b.count / max) * 60)}px` }}
                      />
                    </div>
                    <span className="text-caption text-gray-500">{b.label}</span>
                  </div>
                ))}
              </div>
            )}
          </div>
        );
      })()}

      {/* 라벨별 분포 */}
      <div>
        <h4 className="text-title-sm font-semibold text-gray-700 mb-1">라벨별 분포</h4>
        {labelCounts.length === 0 ? (
          <p className="text-body-md text-gray-400 py-4 text-center">라벨 데이터가 없습니다.</p>
        ) : (
          <div className="space-y-2 mt-2">
            {labelCounts.slice(0, 10).map(([code, { name, count, color }]) => (
              <div key={code} className="flex items-center gap-2">
                <span className="text-caption text-gray-600 w-20 truncate shrink-0">{name}</span>
                <div className="flex-1 bg-gray-100 rounded-full h-3 overflow-hidden">
                  <div
                    className="h-full rounded-full transition-all"
                    style={{
                      width: `${Math.round((count / maxCount) * 100)}%`,
                      backgroundColor: color,
                    }}
                  />
                </div>
                <span className="text-caption tabular-nums text-gray-500 w-8 text-right">
                  {count}
                </span>
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

  // [@design API-167] 배치 재기동은 **접수**만 확정되고 실행은 비동기다. 접수 직후의 상세는 진행
  //   로그가 아직 직전 실패 그대로라 기본 폴링 규칙(FAIL=종료)이 즉시 멈춘다 — 사용자에겐 화면이
  //   멈춘 것으로 보인다. 그래서 **영상 상태가 처리 중인 동안** 진행을 따라간다.
  const [batchPollUntil, setBatchPollUntil] = useState<number | null>(null);
  const { data, isLoading, error } = useVideoDetail(validId, { pollUntil: batchPollUntil });

  // ★ 창을 무장하는 축은 "버튼을 눌렀는가"가 아니라 **영상이 처리 중인가**다(구 동작 폐기).
  //   그래서 일괄 재시작 후 상세로 들어온 사람에게도 진행 추적이 열린다.
  // ⚠ 의존성은 **처리 중 여부의 전이**와 영상 식별자뿐이다 — 폴링이 돌 때마다 다시 무장하면
  //   상한이 사라져 PROCESSING 고착 영상에서 무한 폴링(self-DoS)이 된다. 상태가 바뀌지 않는 한
  //   이 effect 는 다시 돌지 않으므로, 한 번의 처리 중 구간에 창은 정확히 한 번만 열린다.
  const batchProcessing = data ? isBatchProcessing(data) : false;
  useEffect(() => {
    setBatchPollUntil(batchProcessing ? Date.now() + BATCH_PROCESSING_POLL_WINDOW_MS : null);
  }, [batchProcessing, validId]);

  // [@design SCREEN-009] '재비식별 요청' 버튼은 이 화면에 두지 않는다(확정 사양 — 헤더 카드
  //   컴포넌트 목록에 없다). 헤더에는 1차 액션이 없다(조회 화면). 서버 측 재비식별 경로는 그대로다.
  // REVIEWER 판정은 배치 실패 조치 패널(REVIEWER 전용) 노출에 계속 쓰인다.
  const role = useAuthStore((s) => s.claims?.role ?? null);
  const isReviewer = role === Role.REVIEWER;

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
        className="flex items-center gap-1.5 text-body-md text-gray-600 hover:text-gray-800 transition-colors"
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
            <CardContent>
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
                    <span className="text-caption text-gray-400">미리보기 없음</span>
                  </div>
                )}
                <div className="flex-1 min-w-0">
                  {/* [@design SCREEN-009] 제목·배지는 한 줄(hero-title-row). 헤더 1차 액션 없음. */}
                  <div className="flex flex-wrap items-center gap-2">
                    <h2 className="text-title-md font-bold text-gray-900">{data.cctvName}</h2>
                    <EventTypeBadge
                      eventType={data.eventTypeCd ?? data.eventName ?? ''}
                      size="md"
                    />
                    <StatusBadge status={data.status} />
                  </div>
                  {/* [@design SCREEN-009] 헤더 메타 행 — 길이·녹화 시각·**해상도**(hero-sub-row).
                      해상도는 확정 디자인에서 이 줄에 온다. */}
                  <div className="flex flex-wrap gap-4 mt-2 text-body-sm text-gray-500">
                    <span>길이: {formatDuration(data.durationSec ?? data.duration)}</span>
                    <span>녹화일: {data.capturedAt ? data.capturedAt.slice(0, 10) : '-'}</span>
                    <span>해상도: {data.resolution || '-'}</span>
                  </div>
                </div>
              </div>
            </CardContent>
          </Card>

          {/* [@design SCREEN-009] 2컬럼 골격 — 좌측 세로 탭 레일 + 우측 콘텐츠(design-main.css
              `.detail-layout`). 상단 가로 탭 1컬럼에서 전환한 것이며, 이 전환이 콘텐츠 폭을
              확보해 처리 단계 전폭 밴드가 성립한다. 좁은 폭에서는 세로 배치로 접는다. */}
          <div className="flex flex-col items-stretch gap-4 md:flex-row md:items-start">
            <nav
              aria-label="상세 정보"
              className="shrink-0 rounded-lg border border-gray-200 bg-white p-3 shadow-sm md:sticky md:top-6 md:w-[200px]"
            >
              <p className="px-3 pb-1 pt-2 text-caption text-gray-400">상세 정보</p>
              <Tabs
                orientation="vertical"
                items={tabs}
                value={activeTab}
                onChange={setActiveTab}
                ariaLabel="영상 상세 탭"
              />
            </nav>

            <div className="min-w-0 flex-1 rounded-lg border border-gray-200 bg-white p-6 shadow-sm">
              {activeTab === 'info' && <InfoTab video={data} isReviewer={isReviewer} />}
              {activeTab === 'frames' && <FramePreviewTab video={data} />}
              {activeTab === 'autolabel' && <AutoLabelTab videoId={data.id} />}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
