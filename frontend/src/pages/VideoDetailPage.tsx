import { useEffect, useState } from 'react';
import { useMutationState } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Video as VideoIcon } from 'lucide-react';

import { AuthImage } from '@/components/common/AuthImage';
import { Badge } from '@/components/common/Badge';
import { BatchStageIndicator, DOT_HALO_PX } from '@/components/common/BatchStageIndicator';
import { Button } from '@/components/common/Button';
import { Card, CardContent } from '@/components/common/Card';
import { EmptyState } from '@/components/common/EmptyState';
import { ErrorState } from '@/components/common/ErrorState';
import { EventTypeBadge } from '@/components/common/EventTypeBadge';
import { Modal } from '@/components/common/Modal';
import { PageHeader } from '@/components/common/PageHeader';
import { Skeleton } from '@/components/common/Skeleton';
import { StatusBadge } from '@/components/common/StatusBadge';
import { Tabs } from '@/components/common/Tabs';
import { BatchFailurePanel } from '@/features/video/components/BatchFailurePanel';
import { DeidentHistoryPanel } from '@/features/video/components/DeidentHistoryPanel';
import {
  BATCH_PROCESSING_POLL_WINDOW_MS,
  LEAD_DEIDENT_ACCEPT_POLL_WINDOW_MS,
  useVideoDetail,
  type LeadDeidentAcceptWatch,
} from '@/features/video/hooks/useVideoDetail';
import {
  batchRetryMutationKey,
  type BatchRetryVariables,
} from '@/features/video/hooks/useBatchRecovery';
import { useVideoLabels } from '@/features/video/hooks/useVideoLabels';
import {
  BATCH_STATUS_PENDING,
  isBatchProcessing,
  isLeadDeidentRunning,
} from '@/features/video/types';
import type { BatchRetryResult, FramePreview, VideoDetail } from '@/features/video/types';
import { Role } from '@/lib/api/types';
import { roleSatisfies } from '@/lib/authz';
import { cn } from '@/lib/cn';
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

/**
 * 프레임의 영상 내 시각을 **아는가** — 썸네일 캡션과 확대 보기가 함께 쓰는 단일 판정기.
 *
 * ★값이 없을 때는 **`null` 도 `undefined` 와 같게** 다룬다 — 서버는 위치를 모르는 프레임에
 * `timestampMs: null` 을 실어 보내므로(키 부재가 아니다) `undefined` 만 거르면 null 이 그대로
 * 흘러든다. `0` 은 유효한 값이라 참/거짓으로 가리지 않는다(영상 첫 프레임).
 *
 * ⚠ 두 표시 자리가 이 판정을 **복제하지 않는다** — 복제하면 한쪽만 갱신돼 같은 프레임의 시각이
 * 두 자리에서 다르게 보인다(음수·NaN 구간이 정확히 그렇게 갈렸다).
 */
function hasClock(ms: number | null | undefined): ms is number {
  return typeof ms === 'number' && Number.isFinite(ms) && ms >= 0;
}

/**
 * 썸네일 캡션의 재생 시점(mm:ss) — 확정 디자인 `.thumb-caption` 의 `#0 · 00:00` 표기용.
 *
 * ★★미상은 **`-`** 다 — `00:00` 을 돌려주면 안 된다. `00:00` 은 **영상 맨 앞 프레임의 실제
 * 값**이라, 「위치를 모른다」와 「영상 맨 앞이다」가 화면에서 구분되지 않는다(미상을 실제 값처럼
 * 보여주는 것이다). 표기는 바로 아래 `formatDateTime` 이 이미 쓰는 그 한 글자에 맞춘다.
 */
function formatClock(ms: number | null | undefined): string {
  if (!hasClock(ms)) return '-';
  const total = Math.floor(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/**
 * 확대 보기의 정밀 재생 시점(`hh:mm:ss.SSS`) — 확정 디자인 `.lightbox-meta` 의 `00:00:00.960` 표기용.
 *
 * ★★위 `formatClock`(mm:ss)과 **일부러 다른 함수**다 — 합치지 말 것. 두 자리는 폭도 목적도 다르다:
 * 썸네일 캡션은 타일 안 좁은 자리라 짧은 표기가 맞고, 확대 보기는 그 프레임이 영상의 정확히 어느
 * 지점인지 읽는 자리라 밀리초까지 적는다. 하나로 합치면 한쪽은 잘리고 다른 쪽은 정밀도를 잃는다.
 *
 * ★구 표기는 원시 밀리초(`960 ms`)였다 — 긴 영상일수록 사람이 감을 잡지 못한다(`754320 ms` 가
 * 영상의 어디인지 읽어낼 수 없다). 시안·사양·구현이 갈려 있던 것을 시안으로 통일했다.
 *
 * ⚠ 미상 판정은 이 자리에서 다시 쓰지 않고 `hasClock` 한 곳에 위임한다(캡션과 같은 술어) —
 * 복제하면 음수·NaN 구간부터 두 자리가 갈린다. 미상은 `-` 이며 단위를 붙이지 않는다.
 */
function formatPreciseClock(ms: number | null | undefined): string {
  if (!hasClock(ms)) return '-';
  // 소수 밀리초가 들어와도 표기가 깨지지 않게 정수로 내린다(서버는 정수를 보내지만 계약이 아니다).
  const total = Math.floor(ms);
  const h = Math.floor(total / 3_600_000);
  const m = Math.floor((total % 3_600_000) / 60_000);
  const sec = Math.floor((total % 60_000) / 1000);
  const milli = total % 1000;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}.${String(milli).padStart(3, '0')}`;
}

/** 날짜+시각 표기(초 제외) — 메타 그리드와 헤더 메타 행이 같은 값을 보이도록 한 곳에서 만든다. */
function formatDateTime(iso: string | undefined): string {
  return iso ? iso.slice(0, 16).replace('T', ' ') : '-';
}

function InfoTab({ video, isReviewer }: { video: VideoDetail; isReviewer: boolean }) {
  // [@design SCREEN-009] 메타 그리드에서 '처리 단계'를 뺀다 — 확정 디자인의 처리 단계는 그리드
  //   셀이 아니라 **콘텐츠 전폭 밴드**(design-main.html `.stage-row`)다. 2열 그리드 셀(≈530px)에
  //   가두면 7단계 노드와 캡션이 압축돼 판독이 안 된다.
  // [req: R2] '개인정보 분류' 항목은 두지 않는다 — 관제서버가 개인정보 유무를 실제로 보내지 않고
  //   privacyTypeCd 는 적재 시 고정되는 레거시 컬럼이다(BE 응답 계약은 그대로 유지).
  const metaRows: { label: string; value: React.ReactNode; mono?: boolean }[] = [
    // [@design SCREEN-009] [@design API-043] CCTV ID 는 **영상이 보유한 실제 식별자**를 그대로
    //   쓴다. 구 구현은 `video-${rawSn}` 로 문자열을 조립해, 같은 화면에서 상단 제목의 진짜
    //   식별자(`cctvName`)와 이 칸의 조립값이 **서로 다른 두 값**으로 떴다.
    //   ⚠ 값이 없으면 조립값으로 되돌아가지 않고 `-` 로 둔다 — 없는 식별자를 지어내면 그것이
    //     실값처럼 보인다(조립값은 어디에도 저장되지 않는 화면 전용 문자열이었다).
    { label: 'CCTV ID', value: video.vmsCctvId || '-', mono: true },
    // [@design API-043] 해상도는 서버가 기술메타에서 조달한 값을 그대로 표시한다(가로·세로 재조립
    //   금지). 미상이면 서버가 null 을 내리므로 여기서 `-` 가 된다 — 숫자 폴백을 두지 않는다.
    { label: '해상도', value: video.resolution || '-', mono: true },
    { label: '길이', value: formatDuration(video.durationSec ?? video.duration), mono: true },
    { label: '녹화 시각', value: formatDateTime(video.capturedAt) },
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
            {/* [@design SCREEN-009] 식별자·수치 칸은 **D2Coding 등폭**이다(`.meta-grid dd.mono`).
                구 구현은 `tabular-nums` 만 걸어 자간만 맞추고 글꼴은 본문체였다 — 확정 디자인은
                글꼴 자체를 바꾼다(해상도 `1920x1080` 처럼 숫자·기호가 섞인 값의 판독을 위해서다). */}
            <dd
              className={cn('m-0 text-body-sm text-gray-900', r.mono && 'font-mono')}
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
      <div className="mt-2 border-t border-gray-200 pt-4">
        <p className="mb-2 text-label text-gray-900">처리 단계</p>
        {video.stages && video.stages.length > 0 ? (
          /* ★ 이 래퍼는 좁은 폭에서 표시기를 **좌우로 밀어 읽게** 하는 자리이자, 동시에
               **세로로도 잘라 내는 상자**다 — CSS 규칙상 한 축이 `visible` 이 아니면 다른 축도
               `auto` 로 계산되기 때문이다. 표시기의 진행 중 점은 위쪽 모서리가 이 상자의 내용
               상자 top 과 같고 헤일로는 `box-shadow` 라 스크롤 영역에 기여하지 않아, 위로 뻗은
               두께가 그대로 잘려 «위가 평평한 반달»이 된다(브라우저 실측 — jsdom 은 못 본다).
               위쪽 패딩으로 그 두께만큼 자리를 비워 준다.
             ⚠ 숫자를 적지 않고 {@link DOT_HALO_PX} 에서 가져온다 — 헤일로 두께가 바뀌면 이 여백도
               함께 따라와야 한다. 패딩은 점과 칸을 함께 밀어 내리므로 표시기 내부의 연결선 정렬
               파생(`CONNECTOR_TOP_PX`)은 그대로 성립한다. */
          <div className="overflow-x-auto" style={{ paddingTop: DOT_HALO_PX }}>
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

  // [@design SCREEN-009] 툴바의 「총 N개 중 N개」 — 분모는 **영상의 전체 프레임 수**이고 분자는
  //   이 그리드가 실제로 그린 미리보기 수다. 두 값이 다른 것이 정상이다(미리보기는 부분집합).
  //   ⚠ frameCount 는 구 응답에서 0 으로 폴백될 수 있어(api.ts normalizeVideo) 그때는 분모가
  //     분자보다 작아진다 — 그 경우에만 실제로 그린 수를 분모로 쓴다. 없는 총계를 지어내지 않는다.
  const shownCount = frames.length;
  const totalFrames = video.frameCount > shownCount ? video.frameCount : shownCount;

  return (
    <>
      {/* [@design SCREEN-009] `.frame-toolbar` — 표시 개수 + 이슈 점 범례. 빨간 점은 색만으로
          의미를 전하므로 범례가 그 색의 뜻을 글로 적는다(KRDS 색상 단독 구분 금지). */}
      <div className="flex items-baseline justify-between gap-4">
        <span className="text-body-sm text-gray-600">
          총 {totalFrames.toLocaleString('ko-KR')}개 프레임 중{' '}
          {shownCount.toLocaleString('ko-KR')}개 표시
        </span>
        <span className="text-caption text-gray-500">빨간 점 = 이슈 있는 프레임</span>
      </div>

      {/* [@design SCREEN-009] 열 수는 확정 디자인의 브레이크포인트를 따른다 — 1280 이상 6열,
          그 아래 4열(`.frame-grid` + `@media (max-width: 1279px)`). 좌측 탭 레일이 폭을 먼저
          가져가므로 태블릿 폭에서 6열이면 타일이 판독 불가로 눌린다. */}
      <div className="mt-3 grid grid-cols-4 gap-2 xl:grid-cols-6">
        {frames.map((f) => (
          <button
            key={f.frameNo}
            type="button"
            onClick={() => setLightboxFrame(f)}
            aria-label={`프레임 ${f.frameNo} 상세 보기`}
            className={cn(
              'group relative block overflow-hidden rounded-md border border-gray-200 bg-white text-left',
              KRDS_FOCUS,
            )}
          >
            {/* 미디어 매트는 다크(`--n-8`)다 — 어두운 CCTV 프레임이 밝은 회색 위에 뜨면 타일
                경계와 이미지 경계가 섞인다. DS 가 허용하는 유일한 다크 표면 예외. */}
            <AuthImage
              srcSn={f.srcSn}
              alt={`Frame ${f.frameNo}`}
              className="aspect-video w-full bg-gray-800 object-cover"
              width={160}
              height={90}
            />
            {/* [@design SCREEN-009] 프레임 번호는 이미지 **아래 흰 스트립**이다. 구 구현은 이미지
                위 반투명 오버레이라 번호가 프레임 내용을 가렸다(어두운 장면에선 판독도 어렵다). */}
            <span className="block px-2 py-1.5 text-caption text-gray-600">#{f.frameNo}</span>
            {f.hasIssue && (
              <span
                className="absolute right-1.5 top-1.5 h-2.5 w-2.5 rounded-full border-2 border-white bg-danger shadow-sm"
                role="img"
                aria-label="이슈 있음"
              />
            )}
            {/* hover/focus 시에만 뜨는 '상세 보기' 어포던스. 타일 자체가 버튼이라 여기에 중첩
                버튼을 두지 않는다(중첩 button 은 유효하지 않은 DOM 이다) — 버튼 모양만 입힌다. */}
            <span
              aria-hidden="true"
              className="pointer-events-none absolute inset-0 flex items-end justify-center bg-gradient-to-b from-transparent via-transparent to-gray-950/60 pb-[30px] opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100"
            >
              <span className="inline-flex min-h-9 items-center rounded-md border border-gray-400 bg-white px-3 text-body-sm font-medium text-gray-700">
                상세 보기
              </span>
            </span>
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
          <div className="flex flex-col gap-4">
            {/* [@design SCREEN-009] `.lightbox-media` — 16:9 다크 매트 안에 원본 비율을 유지해
                담는다. 구 구현은 `max-h-80` 로 높이를 잘라 세로가 긴 프레임이 축소돼 보였다. */}
            <div className="flex aspect-video w-full items-center justify-center overflow-hidden rounded-md bg-gray-900">
              <AuthImage
                srcSn={lightboxFrame.srcSn}
                alt={`Frame ${lightboxFrame.frameNo}`}
                className="h-full w-full bg-gray-900 object-contain"
                width={640}
                height={360}
              />
            </div>
            <div className="flex flex-wrap items-center gap-4 text-body-md text-gray-500">
              <span>프레임 #{lightboxFrame.frameNo}</span>
              {/* [@design SCREEN-009] [@design API-043] 재생 시점 — 확정 디자인의 `00:00:00.960`
                  표기다. ★구 표기는 원시 밀리초(`960 ms`)라 긴 영상에서 사람이 어느 지점인지
                  읽어낼 수 없었다(시안·사양·구현이 갈려 있던 것을 시안으로 통일했다).
                  ⚠ 캡션의 `formatClock`(mm:ss)과 **합치지 않는다** — 좁은 자리와 정밀 표기는
                  목적이 다르다. 두 자리가 공유하는 것은 미상 판정(`hasClock`)뿐이다.
                  ★★값이 없어도 **줄은 그린다** — 미상은 `-` 다. 줄을 통째로 숨기면 같은 프레임의
                  썸네일 캡션(`formatClock` → `-`)과 이 자리가 같은 값을 다르게 보여준다.
                  ⚠ 단위를 붙이지 않는다 — 없는 값에 단위를 붙이면 값처럼 읽힌다.
                  ★글꼴은 **등폭**이다 — `hh:mm:ss.SSS` 는 자릿수가 고정된 값이라 본문체로 그리면
                  글자마다 폭이 달라 프레임을 넘길 때 숫자가 좌우로 흔들린다. 같은 화면의 길이·
                  해상도 값이 이미 같은 이유로 `font-mono text-mono` 를 쓴다 — 그 관례를 따른다. */}
              <span className="font-mono text-mono">
                {formatPreciseClock(lightboxFrame.timestampMs)}
              </span>
              {/* 이슈 여부는 평문이 아니라 배지다 — 같은 사실을 그리드의 빨간 점과 같은 위험 톤으로
                  말해야 두 표시가 한 축임이 드러난다(`badge-error`). */}
              {lightboxFrame.hasIssue && <Badge variant="error" label="이슈 있음" />}
            </div>
          </div>
        )}
      </Modal>
    </>
  );
}

function AutoLabelTab({ video }: { video: VideoDetail }) {
  const { data, isLoading, isError } = useVideoLabels(video.id);
  const labels = data?.objects ?? [];

  if (isLoading) {
    // [@design SCREEN-009] 확정 디자인의 스켈레톤은 «제목 한 줄 + 블록 두 개» 로 실제 레이아웃을
    //   흉내 낸다(폭 60% / 100% / 100%). 같은 폭 블록 3개는 무엇이 올지 예고하지 못한다.
    return (
      <div className="flex flex-col gap-2">
        <Skeleton height={16} width="60%" className="rounded-sm" />
        <Skeleton height={64} width="100%" className="rounded-sm" />
        <Skeleton height={64} width="100%" className="rounded-sm" />
      </div>
    );
  }

  if (isError) {
    return (
      <ErrorState
        title="오토라벨 결과를 불러올 수 없습니다"
        message="잠시 후 다시 시도해주세요"
      />
    );
  }

  if (labels.length === 0) {
    return (
      <EmptyState
        title="오토라벨 결과가 없습니다"
        message="배치 처리 완료 후 결과가 표시됩니다."
      />
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

  // [@design SCREEN-009] `.stat-card` — 값은 display-sm(26/700)이고 단위는 값에 붙는 보조 표기다.
  //   ⚠ '처리 상태'만 `unit` 없이 `emphasis: false` 다 — 값이 수치가 아니라 상태 코드 문자열이라
  //     26px/700 로 키우면 칸을 넘친다. (이 칸의 **값 자체**는 영상 상태 축이며 여기서 만들지 않는다.)
  const stats: { label: string; value: string; unit?: string; emphasis: boolean }[] = [
    { label: '총 라벨 수', value: totalLabels.toLocaleString('ko-KR'), unit: '건', emphasis: true },
    { label: '오토라벨 수', value: autoCount.toLocaleString('ko-KR'), unit: '건', emphasis: true },
    { label: '오토라벨 비율', value: autoRate, unit: '%', emphasis: true },
    // [@design SCREEN-009] 처리 상태는 **영상이 실제로 가진 상태**(VideoDetail.status)다.
    //   구 구현은 `'COMPLETED'` 리터럴이라 배치가 처리중이든 실패든 늘 완료로 보였다.
    //   ⚠ 이 값의 조달처는 이 탭이 쓰는 오토라벨 응답(FrameLabels)이 아니다 — 거기엔
    //     상태 필드가 없어 만들어낼 수 없으므로 부모가 가진 영상 상태를 내려받는다.
    //   ⚠ 여기서 빈값 폴백을 다시 만들지 않는다 — 구 응답 처리는 이미 생산자
    //     (`features/video/api.ts` normalizeVideo → `dataSttsCd`, 최종 'PENDING')가
    //     소유한다. 화면이 그 전제를 재유도하면 두 규칙이 갈린다.
    { label: '처리 상태', value: video.status, emphasis: false },
  ];

  return (
    <div className="flex flex-col gap-6">
      {/* 처리 정보 */}
      <div>
        <h4 className="mb-4 text-title-sm text-gray-900">처리 정보</h4>
        {/* [@design SCREEN-009] `.stat-strip` — 4항목이므로 넓은 폭에서 4열이다. 구 3열은 4번째
            항목만 다음 줄로 떨어져 마지막 줄이 어긋났다. 1280 미만에서는 2열로 접는다. */}
        <div className="grid grid-cols-2 gap-4 xl:grid-cols-4">
          {stats.map((item) => (
            <div
              key={item.label}
              className="flex flex-col gap-1 rounded-lg border border-gray-200 bg-white p-4"
            >
              <span className="text-label text-gray-600">{item.label}</span>
              {item.emphasis ? (
                <span className="text-display-sm text-gray-950">
                  {item.value}
                  {item.unit && (
                    <span className="ml-1 text-body-md text-gray-600">{item.unit}</span>
                  )}
                </span>
              ) : (
                <span className="text-body-md text-gray-950">{item.value}</span>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* [@design SCREEN-009] `.chart-row` — 두 차트는 넓은 폭에서 **좌우 두 칸**이고 각각 카드다.
          세로 스택은 오토라벨 탭을 두 배로 길게 만들어 두 분포를 한눈에 대조할 수 없었다. */}
      <div className="grid gap-4 xl:grid-cols-2">
        {/* 신뢰도 분포 */}
        {(() => {
          // 신뢰도 버킷 색 — KRDS 의미상태색 토큰 (고=success, 중=warning, 저=danger).
          // ※ 아래 라벨별 분포 막대의 `backgroundColor: color`(l.color ?? '#4ECDC4')는 라벨 고유색이라 불변.
          // ⚠ 중간 버킷은 `warning-400`(#C78500)이다 — 확정 디자인 `.bar-conf-mid` 가 그 단을 쓴다.
          //   DEFAULT(500, #9E6A00)는 저(danger)와 명도가 붙어 세 막대의 서열이 흐려진다.
          const buckets = [
            { label: '0.9 이상', count: high, color: 'bg-success' },
            { label: '0.7~0.9', count: mid, color: 'bg-warning-400' },
            { label: '0.7 미만', count: low, color: 'bg-danger' },
          ];
          const max = Math.max(...buckets.map((b) => b.count), 1);
          return (
            <div className="rounded-lg border border-gray-200 bg-white p-4">
              <h4 className="text-title-sm text-gray-900">신뢰도 분포</h4>
              <p className="mb-4 mt-1 text-caption text-gray-600">오토라벨 {autoCount}건 기준</p>
              {autoCount === 0 ? (
                <p className="text-body-md text-gray-400 py-4 text-center">
                  오토라벨 데이터가 없습니다.
                </p>
              ) : (
                <div className="flex h-[180px] items-end gap-4 border-b border-gray-200 px-2">
                  {buckets.map((b) => (
                    <div
                      key={b.label}
                      className="flex h-full min-w-0 flex-1 flex-col items-center justify-end gap-1.5"
                    >
                      <span className="text-body-sm tabular-nums text-gray-900">{b.count}</span>
                      {/* 막대는 «값 표기·축 라벨을 뺀 나머지» 안에서 비율을 차지한다 — 부모 높이에
                          직접 % 를 걸면 그 두 줄만큼 위로 넘친다. */}
                      <div className="flex min-h-0 w-full flex-1 items-end justify-center">
                        <div
                          className={cn(b.color, 'w-10 rounded-t-[3px] transition-all')}
                          style={{ height: `${Math.round((b.count / max) * 100)}%` }}
                        />
                      </div>
                      <span className="max-w-[72px] truncate text-center text-caption text-gray-600">
                        {b.label}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          );
        })()}

        {/* 라벨별 분포 */}
        <div className="rounded-lg border border-gray-200 bg-white p-4">
          {/* 제목이 상위 10종만 그린다는 사실을 말한다 — 코드가 실제로 `slice(0, 10)` 이라
              제목이 그 절단을 밝히지 않으면 화면이 전체 분포인 척하게 된다. */}
          <h4 className="mb-4 text-title-sm text-gray-900">라벨별 분포 (상위 10종)</h4>
          {labelCounts.length === 0 ? (
            <p className="text-body-md text-gray-400 py-4 text-center">라벨 데이터가 없습니다.</p>
          ) : (
            <div className="flex flex-col gap-2">
              {labelCounts.slice(0, 10).map(([code, { name, count, color }]) => (
                // [@design SCREEN-009] `.label-bar-row` — 이름 96px / 트랙 1fr / 수치 48px 고정.
                //   구 구현(80/flex/32)은 두 자리 수치가 잘리고 긴 라벨명이 과하게 눌렸다.
                <div
                  key={code}
                  className="grid grid-cols-[96px_1fr_48px] items-center gap-2"
                >
                  <span className="truncate text-body-sm text-gray-900">{name}</span>
                  <div className="h-3 overflow-hidden rounded-full bg-gray-50">
                    <div
                      className="h-full rounded-full transition-all"
                      style={{
                        width: `${Math.round((count / maxCount) * 100)}%`,
                        backgroundColor: color,
                      }}
                    />
                  </div>
                  <span className="text-right text-body-sm tabular-nums text-gray-700">
                    {count}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * SCR-VIDEO-003 영상 상세 (라우트 페이지).
 * 페이지 헤더 + 영상 헤더 카드 + 3개 탭 (기본 정보 / 프레임 미리보기 / 오토라벨 결과).
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
  // [@design API-167] 선두 비식별 재시도 접수 직후 추적 — 아래 effect 가 접수 응답을 보고 무장한다.
  const [leadDeidentAccept, setLeadDeidentAccept] = useState<LeadDeidentAcceptWatch | null>(null);
  const { data, isLoading, error } = useVideoDetail(validId, {
    pollUntil: batchPollUntil,
    leadDeidentAccept,
  });

  // ★ 창을 무장하는 축은 "버튼을 눌렀는가"가 아니라 **영상이 처리 중인가**다(구 동작 폐기).
  //   그래서 일괄 재시작 후 상세로 들어온 사람에게도 진행 추적이 열린다.
  // ⚠ 의존성은 **처리 중 여부의 전이**와 영상 식별자뿐이다 — 폴링이 돌 때마다 다시 무장하면
  //   상한이 사라져 PROCESSING 고착 영상에서 무한 폴링(self-DoS)이 된다. 상태가 바뀌지 않는 한
  //   이 effect 는 다시 돌지 않으므로, 한 번의 처리 중 구간에 창은 정확히 한 번만 열린다.
  // ★ 선두 비식별 재시도는 배치 상태를 선점하지 않으므로 「진행 중」을 비식별 이력 최신 회차로 본다.
  //   같은 상한 창을 쓰며, 무장 축은 역시 **상태 전이**다(폴링마다 다시 늘리지 않는다).
  const batchProcessing = data ? isBatchProcessing(data) || isLeadDeidentRunning(data) : false;
  useEffect(() => {
    setBatchPollUntil(batchProcessing ? Date.now() + BATCH_PROCESSING_POLL_WINDOW_MS : null);
  }, [batchProcessing, validId]);

  // [@design API-167] 재기동 접수 응답 — 패널(다른 컴포넌트)이 보낸 뮤테이션을 상태로 읽는다.
  //   ★ 접수 단계가 대기(PENDING)면 선두 비식별 재시도다. 서버는 접수 뒤 비동기로 위탁하므로 새 이력
  //     회차가 쌓이기 전 짧은 틈을 추적 창으로 메운다. 무장 축은 **접수 1건**(submittedAt)이다.
  const lastRetryAccept = useMutationState({
    filters: { mutationKey: batchRetryMutationKey(validId ?? -1), status: 'success' },
    select: (m) => ({
      submittedAt: m.state.submittedAt,
      stage: (m.state.data as BatchRetryResult | undefined)?.stage,
      baseline:
        (m.state.variables as BatchRetryVariables | undefined)?.deidentBaselineProcLogSn ?? null,
    }),
  }).at(-1);
  const leadAcceptAt =
    lastRetryAccept?.stage === BATCH_STATUS_PENDING ? lastRetryAccept.submittedAt : null;
  const leadAcceptBaseline = lastRetryAccept?.baseline ?? null;
  useEffect(() => {
    setLeadDeidentAccept(
      leadAcceptAt
        ? {
            until: Date.now() + LEAD_DEIDENT_ACCEPT_POLL_WINDOW_MS,
            baselineProcLogSn: leadAcceptBaseline,
          }
        : null,
    );
  }, [leadAcceptAt, leadAcceptBaseline]);

  // [@design SCREEN-009] '재비식별 요청' 버튼은 이 화면에 두지 않는다(확정 사양 — 헤더 카드
  //   컴포넌트 목록에 없다). 헤더에는 1차 액션이 없다(조회 화면). 서버 측 재비식별 경로는 그대로다.
  // REVIEWER 판정은 배치 실패 조치 패널(REVIEWER 전용) 노출에 계속 쓰인다.
  const role = useAuthStore((s) => s.claims?.role ?? null);
  const isReviewer = roleSatisfies(role, Role.REVIEWER);

  if (validId === null) {
    return <ErrorState title="잘못된 영상 ID" message="유효한 영상 ID가 필요합니다." />;
  }

  const tabs = [
    { value: 'info', label: '기본 정보' },
    { value: 'frames', label: '프레임 미리보기' },
    { value: 'autolabel', label: '오토라벨 결과' },
  ];

  const thumbnailFrame = data?.framePreviews?.[0] ?? null;

  return (
    // [@design SCREEN-009] `.screen-root` 블록 간격은 24px 다(`--sp-lg`). 16px 는 페이지 헤더·
    //   영상 헤더 카드·본문 세 덩어리를 한 덩어리처럼 보이게 만든다.
    <div className="flex flex-col gap-6">
      {/* [@design SCREEN-009] `.page-head` — 빵부스러기 + 제목 + 설명. 다른 13개 화면이 이미
          쓰는 공용 PageHeader 를 재사용한다(이 화면만 별도 헤더를 만들지 않는다).
          ⚠ 빵부스러기 부모는 **영상 처리 현황**이다 — 확정 시안의 표기는 「작업 목록」이지만
            그 링크는 자리표시(`href="#"`)이고, 이 화면으로 오는 실제 진입점은 영상 처리 현황
            목록 하나뿐이다(`/task` 는 이 화면으로 이동하지 않는다). 표기를 그대로 옮기면
            빵부스러기가 사용자를 무관한 목록으로 보낸다. */}
      <PageHeader
        title="영상 상세 화면"
        description="영상 메타와 오토라벨 결과를 조회합니다. 기본 정보 탭에서 배치 파이프라인 처리 단계를 확인할 수 있습니다."
        breadcrumb={[
          { label: '영상 처리 현황', href: '/video/status' },
          { label: '영상 상세 화면' },
        ]}
      />

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
            <CardContent className="flex flex-col gap-4">
              {/* [@design SCREEN-009] `.hero-top` — 뒤로가기는 카드 **안** 최상단이다. 카드 밖에
                  두면 페이지 헤더와 카드 사이에 소속 없는 조각이 하나 뜬다. 형태도 공용 Button
                  (ghost)이라 44px 터치 타깃·포커스 링을 그대로 얻는다(구 순수 <button> 은 둘 다
                  없었다). */}
              <div className="flex items-center justify-between gap-4">
                <Button variant="ghost" onClick={() => navigate(-1)}>
                  <ArrowLeft className="h-5 w-5" aria-hidden />
                  뒤로가기
                </Button>
              </div>

              <div className="flex flex-wrap items-start gap-6">
                {/* [@design SCREEN-009] `.thumb-frame` — 200×120 다크 매트. 구 128×80 은 첫
                    프레임이 무엇인지 알아볼 수 없었고, 밝은 회색 매트는 어두운 CCTV 프레임의
                    경계를 지웠다. */}
                <div className="relative h-[120px] w-[200px] shrink-0 overflow-hidden rounded-md bg-gray-900">
                  {thumbnailFrame ? (
                    <AuthImage
                      srcSn={thumbnailFrame.srcSn}
                      alt={data.cctvName}
                      className="h-full w-full bg-gray-900 object-cover"
                      width={200}
                      height={120}
                    />
                  ) : (
                    <div className="flex h-full w-full flex-col items-center justify-center gap-1">
                      <VideoIcon className="h-8 w-8 text-gray-400" aria-hidden />
                      <span className="text-caption text-gray-400">미리보기 없음</span>
                    </div>
                  )}
                  {thumbnailFrame && (
                    // `.thumb-caption` — 이 썸네일이 **몇 번째·몇 초 지점** 프레임인지 밝힌다.
                    <span className="absolute bottom-2 left-2 rounded-sm bg-gray-950/70 px-2 py-0.5 text-caption font-medium text-white">
                      #{thumbnailFrame.frameNo} · {formatClock(thumbnailFrame.timestampMs)}
                    </span>
                  )}
                </div>
                <div className="flex min-w-0 flex-1 flex-col gap-2">
                  {/* [@design SCREEN-009] 제목·배지는 한 줄(hero-title-row). 헤더 1차 액션 없음.
                      제목 굵기는 ladder 의 `title-md`(18/600)가 소유한다 — `font-bold`(700)를
                      덧칠하면 화면 하나가 ladder 밖 굵기를 갖는다. */}
                  <div className="flex flex-wrap items-center gap-2">
                    <h2 className="text-title-md text-gray-950">{data.cctvName}</h2>
                    <EventTypeBadge
                      eventType={data.eventTypeCd ?? data.eventName ?? ''}
                      size="md"
                    />
                    <StatusBadge status={data.status} />
                  </div>
                  {/* [@design SCREEN-009] 헤더 메타 행 — 길이·녹화 시각·**해상도**(hero-sub-row).
                      ★키와 값을 **두 색**으로 나누고 콜론을 쓰지 않는다(`.kv .k` n-6 / `.kv .v` n-9).
                        구 구현은 「길이: 3분 5초」를 통째로 한 색(gray-500)으로 칠해 무엇이 라벨이고
                        무엇이 값인지 색으로 구분되지 않았다. 값 중 수치·식별자는 등폭이다. */}
                  <div
                    data-testid="video-hero-meta"
                    className="flex flex-wrap items-center gap-6 text-body-sm text-gray-600"
                  >
                    <span className="flex items-baseline gap-1">
                      <span>길이</span>
                      <span className="font-mono text-mono text-gray-900">
                        {formatDuration(data.durationSec ?? data.duration)}
                      </span>
                    </span>
                    <span className="flex items-baseline gap-1">
                      <span>녹화 시각</span>
                      <span className="text-gray-900">{formatDateTime(data.capturedAt)}</span>
                    </span>
                    <span className="flex items-baseline gap-1">
                      <span>해상도</span>
                      <span className="font-mono text-mono text-gray-900">
                        {data.resolution || '-'}
                      </span>
                    </span>
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
              className="shrink-0 rounded-lg border border-gray-200 bg-white p-2 shadow-sm md:sticky md:top-6 md:w-[200px]"
            >
              <p className="px-2 pb-1 pt-2 text-caption text-gray-500">상세 정보</p>
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
              {activeTab === 'autolabel' && <AutoLabelTab video={data} />}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
