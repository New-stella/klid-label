/**
 * 데이터셋 영상 구역 — 소재가 준비된 데이터셋에서 영상을 골라 라벨링으로 들어간다.
 * [@design SCREEN-046] [@design API-253]
 *
 * <h3>흐름에서 이 구역의 자리</h3>
 * 포털 학습데이터 상세 → (이 화면) 소재 가져오기 → **영상 고르기** → 라벨링 → 저장. 영상과 프레임은
 * 소재 준비 뒤 원장에 먼저 등록되고, 라벨은 사용자가 라벨링 화면에서 **저장했을 때만** 쌓인다.
 * 그래서 열어 보기만 한 영상은 내 작업에 남지 않는다 — 구역 머리의 한 줄 설명이 그 사실을 말한다.
 *
 * <h3>상태를 섞지 않는다</h3>
 * 조회 실패 · 등록 중 · 등록 실패 · 영상 없음 · 목록을 **서로 다른 자리**로 그린다. 조회 실패나
 * 등록 중을 「영상이 없다」로 보이면 사용자가 데이터셋이 비었다고 오해한다.
 *
 * <h3>화면이 프레임을 고르지 않는다</h3>
 * 어느 프레임으로 열지는 응답의 `entrySrcSn` 이 정한다. 비어 있으면 진입을 두지 않고 사유를 한 줄로
 * 보인다 — 「첫 프레임으로 대신 연다」로 때우지 않는다.
 *
 * 보안: 영상 이름은 텍스트 노드로만 렌더한다(자동 escape). 사용자 격리는 서버가 토큰 주체로 한다.
 */

import { useState } from 'react';
import { CircleAlert, Inbox } from 'lucide-react';
import { Link } from 'react-router-dom';

import { Pagination } from '@/components/common/Pagination';
import { PortalAlert } from '@/components/portal/ui/PortalAlert';
import { PortalBadge } from '@/components/portal/ui/PortalBadge';
import { PortalEmptyState } from '@/components/portal/ui/PortalEmptyState';
import { PortalListSkeleton } from '@/components/portal/ui/PortalListSkeleton';
import {
  PortalFactChip,
  PortalRecordList,
  PortalRecordRow,
} from '@/components/portal/ui/PortalRecordRow';
import { PortalSectionHead } from '@/components/portal/ui/PortalSectionHead';
import { portalButtonSm } from '@/components/portal/ui/portalControl';
import { formatPortalDateTime } from '@/features/portal/formatDateTime';
import { buildPortalDatamartLabelPath } from '@/features/portal/labelingEntry';

import { useDatasetVideos } from '../hooks/useDatasetVideos';
import { PortalDatasetVideoRegistrationState, type PortalDatasetVideo } from '../types';

const SECTION_ID = 'portal-dataset-videos';

/** 구역 머리 한 줄 설명 — 저장해야 남는다는 사실을 먼저 알린다. */
export const DATASET_VIDEOS_LEAD = '영상을 골라 라벨링합니다. 라벨을 저장해야 내 작업에 남습니다.';

/** 열 프레임이 없는 영상의 사유. */
const NO_ENTRY_REASON = '열 수 있는 프레임이 없습니다.';

interface DatasetVideoSectionProps {
  datasetId: number;
}

export function DatasetVideoSection({ datasetId }: DatasetVideoSectionProps) {
  const [page, setPage] = useState(0);
  const query = useDatasetVideos(datasetId, page, true);
  const data = query.data;
  const state = data?.registrationState;
  /** 등록이 끝난 응답만 목록으로 믿는다 — 그 밖의 상태는 목록이 완전하지 않다. */
  const done = data !== undefined && state === PortalDatasetVideoRegistrationState.DONE ? data : undefined;

  return (
    <section aria-labelledby={SECTION_ID} className="flex flex-col gap-in-component">
      <PortalSectionHead
        id={SECTION_ID}
        title="데이터셋 영상"
        lead={DATASET_VIDEOS_LEAD}
        count={done ? `${done.totalElements.toLocaleString()}건` : undefined}
      />

      {query.isLoading ? (
        <>
          <p role="status" className="text-body-sm text-gray-600">
            영상 목록을 불러오고 있습니다.
          </p>
          <PortalListSkeleton />
        </>
      ) : query.isError ? (
        <PortalAlert
          live
          tone="error"
          title="영상 목록을 불러오지 못했습니다"
          description="잠시 후 다시 시도해 주세요. 가져온 소재가 사라진 것은 아닙니다."
          action={
            <button
              type="button"
              onClick={() => void query.refetch()}
              className={portalButtonSm('secondary')}
            >
              다시 시도
            </button>
          }
          data-testid="dataset-videos-error"
        />
      ) : state === PortalDatasetVideoRegistrationState.IN_PROGRESS ? (
        <>
          <p role="status" className="text-body-sm text-gray-700" data-testid="dataset-videos-registering">
            영상을 등록하고 있습니다. 끝나면 이 자리에 목록이 나타납니다.
          </p>
          <PortalListSkeleton />
        </>
      ) : state === PortalDatasetVideoRegistrationState.FAILED ? (
        <PortalAlert
          live
          tone="error"
          title="영상을 등록하지 못했습니다"
          description="가져온 소재는 그대로 남아 있습니다. 잠시 후 다시 확인해 주세요."
          action={
            <button
              type="button"
              onClick={() => void query.refetch()}
              className={portalButtonSm('secondary')}
            >
              다시 확인
            </button>
          }
          data-testid="dataset-videos-registration-failed"
        />
      ) : done === undefined ? (
        /* 서버가 값역을 넓혔을 때 — 모르는 값을 완료로 읽지 않는다. */
        <PortalAlert
          tone="warning"
          title="영상 등록 상태를 확인할 수 없습니다"
          description="잠시 후 다시 확인해 주세요."
          action={
            <button
              type="button"
              onClick={() => void query.refetch()}
              className={portalButtonSm('secondary')}
            >
              다시 확인
            </button>
          }
          data-testid="dataset-videos-unknown-state"
        />
      ) : done.content.length === 0 ? (
        <PortalEmptyState
          icon={Inbox}
          title="영상이 없습니다."
          description="이 데이터셋에는 라벨링할 영상이 없습니다. 포털에서 다른 학습데이터를 골라 주세요."
          data-testid="dataset-videos-empty"
        />
      ) : (
        <PortalRecordList aria-label="데이터셋 영상 목록" data-testid="dataset-videos-list">
          {done.content.map((video) => (
            <li key={video.rawSn}>
              <DatasetVideoRow video={video} />
            </li>
          ))}
        </PortalRecordList>
      )}

      {done !== undefined && done.totalPages > 1 && (
        <Pagination page={page} totalPages={done.totalPages} onChange={setPage} />
      )}
    </section>
  );
}

function DatasetVideoRow({ video }: { video: PortalDatasetVideo }) {
  const savedAt = video.lastSavedAt;
  const saved = savedAt !== null;
  const entryPath = video.entrySrcSn === null ? null : buildPortalDatamartLabelPath(video.entrySrcSn);
  const actionLabel = saved ? '이어서 라벨링' : '라벨링';

  return (
    <PortalRecordRow
      data-testid={`dataset-video-row-${video.rawSn}`}
      /* 영상 이름 — 텍스트 노드(자동 escape). 자르지 않고 줄바꿈한다. */
      title={video.videoName}
      titleAside={saved ? <PortalBadge tone="primary">저장한 작업 있음</PortalBadge> : undefined}
      body={
        <span className="flex flex-wrap items-center gap-tight">
          <PortalFactChip label="프레임" value={`${video.frameCount.toLocaleString()}장`} />
          <PortalFactChip label="기존 라벨" value={`${video.labelCount.toLocaleString()}건`} />
        </span>
      }
      meta={savedAt !== null ? <span>마지막 저장 {formatPortalDateTime(savedAt)}</span> : undefined}
      actions={
        entryPath !== null ? (
          <Link
            to={entryPath}
            aria-label={`${video.videoName} ${actionLabel}`}
            className={portalButtonSm('primary')}
          >
            {actionLabel}
          </Link>
        ) : (
          <span className="flex items-center gap-tight text-caption text-gray-600">
            <CircleAlert className="size-4 shrink-0" strokeWidth={2} aria-hidden />
            {NO_ENTRY_REASON}
          </span>
        )
      }
    />
  );
}
