/**
 * 데이터셋 영상 구역 — 소재가 준비된 데이터셋에서 영상을 골라 라벨링으로 들어간다.
 * [@design SCREEN-046] [@design API-253] [@design API-262]
 *
 * <h3>등록 실패는 「다시 등록」으로 사람이 재착수한다 (2026-09-16)</h3>
 * 목록 조회는 실패 표식을 다시 시작시키지 않는다 — 구조 불일치는 다시 돌려도 같은 사유로 실패하므로
 * 자동으로 되풀이하지 않는다. 대신 응답의 실패 사유를 문구로 보이고(사유 코드를 그대로 찍지 않는다 ·
 * 모르는 값은 폴백), 「다시 등록」이 재착수 창구를 부른 뒤 목록을 무효화해 등록 중 폴링이 재개된다.
 * 같은 답이 돌아올 사유에서는 버튼을 숨기지 않고 보조 위계로 낮춘다. 재착수 요청 자체가 거부되면
 * (소재 미준비·등록 꺼짐·대기열 포화) 서버 안내 문장을 그 판 안에 보이고 버튼은 다시 누를 수 있다.
 * ⚠ 2026-09-16 실사고 — 파서를 고쳐 배포해도 옛 실패 표식이 남은 데이터셋은 영원히 실패로 보였고
 *   서버에서 표식 파일을 손으로 지워야 풀렸다. 「다시 확인」(재조회)으로는 풀리지 않는다.
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
 * <h3>모양 — 부모 포털의 저작도구 화면을 그대로 입혔다 (2026-09-16)</h3>
 * 형제 화면(내 업로드 · 증강)과 **같은 줄 카드 목록**이다.
 *   · 영상 하나가 카드 한 장(`RecordRow`) — 왼쪽은 이름 → 영상 정보 → 저장 시각, 오른쪽 끝이 걸음
 *   · 프레임 수·기존 라벨은 **이름 흐리게 · 값 진하게**인 조각 줄(`ConditionChips look="text"`)
 *   · 건수는 목록 바로 위 왼쪽(`ResultCount`) — 형제 화면과 같은 자리
 *   · 조회 실패·등록 중·영상 없음이 전부 **빈 판**(`EmptyState`)으로 서서 목록이 올 자리를 지킨다
 *   · 페이저는 킷 것(`PageNav`)이고 **1부터** 센다 — 우리 창구는 0부터라 경계에서 한 번만 옮긴다
 *
 * ⚠ 관제 공통 부품(`components/common/*`)을 쓰지 않는다 — 관제 화면 여럿이 함께 쓰므로 포털 모양을
 *   넣으면 관제 화면이 같이 바뀐다(사용자 확정 구속: **관제향 화면·컴포넌트 불변**).
 *   그래서 구 `Pagination` 도 킷 `PageNav` 로 바뀌었다.
 *
 * 보안: 영상 이름은 텍스트 노드로만 렌더한다(자동 escape). 사용자 격리는 서버가 토큰 주체로 한다.
 */

import { useState } from 'react';
import { Badge, Button } from 'krds-react';
import { CircleAlert, Inbox, RotateCcw } from 'lucide-react';
import { Link } from 'react-router-dom';

import {
  Alert,
  EmptyState,
  PageNav,
  RecordRow,
  ResultCount,
  StepHeading,
} from '@/components/portal/kit';
import { ConditionChips } from '@/components/portal/authoring';
import { formatPortalDateTime } from '@/features/portal/formatDateTime';
import { buildPortalDatamartLabelPath } from '@/features/portal/labelingEntry';
import { extractBeMessage } from '@/lib/api/extractBeMessage';

import { useDatasetVideos } from '../hooks/useDatasetVideos';
import { useRestartDatasetRegistration } from '../hooks/useRestartDatasetRegistration';
import { registrationFailureNotice } from '../registrationFailureReason';
import { PortalDatasetVideoRegistrationState, type PortalDatasetVideo } from '../types';

const SECTION_ID = 'portal-dataset-videos';

/** 구역 머리 한 줄 설명 — 저장해야 남는다는 사실을 먼저 알린다. */
export const DATASET_VIDEOS_LEAD = '영상을 골라 라벨링합니다. 라벨을 저장해야 내 작업에 남습니다.';

/** 열 프레임이 없는 영상의 사유. */
const NO_ENTRY_REASON = '열 수 있는 프레임이 없습니다.';

/** 등록 실패 판의 제목 — 사유가 무엇이든 고정이다(사유는 설명 줄이 말한다). */
export const REGISTRATION_FAILED_TITLE = '영상을 등록하지 못했습니다';

/** 재착수 요청이 거부됐는데 서버 문장이 없을 때의 폴백. */
export const RESTART_REJECTED_FALLBACK = '다시 등록을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.';

interface DatasetVideoSectionProps {
  datasetId: number;
}

export function DatasetVideoSection({ datasetId }: DatasetVideoSectionProps) {
  const [page, setPage] = useState(0);
  const query = useDatasetVideos(datasetId, page, true);
  const restart = useRestartDatasetRegistration(datasetId);
  const data = query.data;
  const state = data?.registrationState;
  /** 등록이 끝난 응답만 목록으로 믿는다 — 그 밖의 상태는 목록이 완전하지 않다. */
  const done = data !== undefined && state === PortalDatasetVideoRegistrationState.DONE ? data : undefined;

  /** 다시 확인 걸음 — 여러 자리가 같은 모양·같은 문구로 쓴다. */
  const retryAction = (label: string) => (
    <Button variant="secondary" size="medium" onClick={() => void query.refetch()}>
      <RotateCcw aria-hidden />
      {label}
    </Button>
  );

  /* 재착수 — 거부는 아래 판 안에서 `restart.isError` 가 싣는다. 여기서 삼키되 던지지 않는다. */
  const restartRegistration = () => void restart.mutateAsync().catch(() => undefined);

  return (
    <section aria-labelledby={SECTION_ID} className="klid-authoring-block">
      <StepHeading size="md" id={SECTION_ID} title="데이터셋 영상" desc={DATASET_VIDEOS_LEAD} />

      {query.isLoading ? (
        /* 불러오는 동안 목록이 올 자리를 그대로 지킨다 — 비워 두면 실패한 것으로 읽힌다.
           도는 고리와 낭독은 킷 빈 판이 `busy` 로 갖는다. */
        <EmptyState busy title="영상 목록을 불러오고 있습니다." />
      ) : query.isError ? (
        /* ★「조회 실패」와 「실제로 0건」을 반드시 가른다 — 서버 오류를 영상이 없는 것으로 오해하면
           사용자가 데이터셋을 잘못 골랐다고 판단한다. 판은 빈 목록과 같고 느낌표·제목·걸음이 가른다.
           ★싸개가 `role="alert"` 를 갖는다 — 킷 빈 판은 `busy` 일 때만 읽어 주므로 그대로 두면
             실패 사실이 보조기술에 한 마디도 가지 않는다(형제 화면 증강과 같은 처리). */
        <div role="alert" data-testid="dataset-videos-error">
          <EmptyState
            icon={CircleAlert}
            title="영상 목록을 불러오지 못했습니다"
            desc="잠시 후 다시 시도해 주세요. 가져온 소재가 사라진 것은 아닙니다."
            action={retryAction('다시 시도')}
          />
        </div>
      ) : state === PortalDatasetVideoRegistrationState.IN_PROGRESS ? (
        <div data-testid="dataset-videos-registering">
          <EmptyState busy title="영상을 등록하고 있습니다. 끝나면 이 자리에 목록이 나타납니다." />
        </div>
      ) : state === PortalDatasetVideoRegistrationState.FAILED ? (
        <RegistrationFailedPane
          reason={data?.registrationFailureReason}
          pending={restart.isPending}
          rejected={restart.isError ? extractBeMessage(restart.error, RESTART_REJECTED_FALLBACK) : null}
          onRestart={restartRegistration}
        />
      ) : done === undefined ? (
        /* 서버가 값역을 넓혔을 때 — 모르는 값을 완료로 읽지 않는다. */
        <div role="alert" data-testid="dataset-videos-unknown-state">
          <EmptyState
            icon={CircleAlert}
            title="영상 등록 상태를 확인할 수 없습니다"
            desc="잠시 후 다시 확인해 주세요."
            action={retryAction('다시 확인')}
          />
        </div>
      ) : done.content.length === 0 ? (
        /* ★싸개가 `role="status"` 를 갖는다 — 조회가 끝나고 0건이라는 것은 **방금 일어난 일**이라
           알린다(`alert` 가 아니다 — 끼어들 만큼 급한 소식이 아니다).
           ★조작을 두지 않는다 — 다른 학습데이터를 고르는 자리가 **이 배포본 바깥**(Host 화면)이라
             여기서 갈 수 있는 곳이 없다. 누를 수 없는 버튼을 두면 막다른 길이 하나 더 는다. */
        <div role="status" data-testid="dataset-videos-empty">
          <EmptyState
            icon={Inbox}
            title="영상이 없습니다."
            desc="이 데이터셋에는 라벨링할 영상이 없습니다. 포털에서 다른 학습데이터를 골라 주세요."
          />
        </div>
      ) : (
        <div className="klid-authoring-assets">
          {/* 건수는 목록 바로 위 왼쪽 — 형제 화면(내 업로드·증강)과 같은 자리. 킷 건수 줄이
              `role="status"` 로 「몇 건으로 좁혀졌는지」를 읽어 준다. */}
          <div className="klid-result-head" data-testid="dataset-videos-count">
            <ResultCount total={done.totalElements} />
          </div>

          {/* 영상 하나가 카드 한 장 */}
          <ul
            className="klid-authoring-records"
            aria-label="데이터셋 영상 목록"
            data-testid="dataset-videos-list"
          >
            {done.content.map((video) => (
              <li key={video.rawSn} data-testid={`dataset-video-row-${video.rawSn}`}>
                <DatasetVideoRow video={video} />
              </li>
            ))}
          </ul>

          {/* 전체가 한 쪽에 들어오면 페이저를 그리지 않는다. */}
          {done.totalPages > 1 && (
            <PageNav
              totalPages={done.totalPages}
              /* 킷 페이저는 1부터 센다 — 우리 창구는 0부터라 경계에서 한 번만 옮긴다. */
              currentPage={page + 1}
              onChange={(oneBased) => setPage(Math.max(0, oneBased - 1))}
            />
          )}
        </div>
      )}
    </section>
  );
}

interface RegistrationFailedPaneProps {
  /** 목록 응답의 실패 사유 — 모르는 값·`null` 은 표기 모듈이 폴백으로 받는다. */
  reason: string | null | undefined;
  /** 재착수 요청이 나가 있는 동안 — 버튼을 잠근다(연타 방지). */
  pending: boolean;
  /** 재착수 요청이 거부됐을 때 보일 문장(서버 안내 또는 폴백). 거부가 아니면 `null`. */
  rejected: string | null;
  onRestart: () => void;
}

/**
 * 등록 실패 판 — 사유 문구 + 「다시 등록」. [@design SCREEN-046] [@design API-262]
 *
 * ★ 제목은 고정이고 사유는 설명 줄이 말한다(제목 · 사유 제목 · 다음 걸음 세 줄).
 * ★ 버튼 위계는 사유가 정한다 — 같은 답이 돌아올 사유(구조 불일치 계열)는 보조 위계(`tertiary`)로
 *   낮추되 **숨기지 않는다**. 어느 사유든 다시 착수할 수는 있다(서버가 막지 않는다).
 * ★ 거부 문장은 판 안의 별도 띠(`role="alert"`)로 선다 — 판을 둘 세우면 어느 것이 지금 상태인지
 *   갈리지 않는다. 사유 문구는 그대로 두고 그 아래에 「지금 시도가 거부됐다」만 덧붙인다.
 */
function RegistrationFailedPane({ reason, pending, rejected, onRestart }: RegistrationFailedPaneProps) {
  const notice = registrationFailureNotice(reason);
  return (
    <div role="alert" data-testid="dataset-videos-registration-failed">
      <EmptyState
        icon={CircleAlert}
        title={REGISTRATION_FAILED_TITLE}
        desc={
          <>
            <span data-testid="dataset-videos-registration-failed-reason">{notice.title}</span>{' '}
            {notice.description}
          </>
        }
        action={
          <div className="klid-authoring-row-actions">
            <Button
              variant={notice.retryWorthwhile ? 'secondary' : 'tertiary'}
              size="medium"
              onClick={onRestart}
              disabled={pending}
            >
              <RotateCcw aria-hidden />
              다시 등록
            </Button>
          </div>
        }
      />
      {rejected !== null && (
        <div data-testid="dataset-videos-restart-rejected">
          <Alert tone="danger" title="다시 등록을 시작하지 못했습니다.">
            {rejected}
          </Alert>
        </div>
      )}
    </div>
  );
}

function DatasetVideoRow({ video }: { video: PortalDatasetVideo }) {
  const savedAt = video.lastSavedAt;
  const saved = savedAt !== null;
  const entryPath = video.entrySrcSn === null ? null : buildPortalDatamartLabelPath(video.entrySrcSn);
  const actionLabel = saved ? '이어서 라벨링' : '라벨링';

  return (
    <RecordRow
      /* 줄 짜임은 형제 화면(증강·내 업로드)과 같다 — 왼쪽은 이름 → 영상 정보 → 저장 시각 한 줄,
         오른쪽 끝이 걸음. */
      titleSize="large"
      factsInline
      factsBelow
      /* 영상 이름 — 텍스트 노드(자동 escape). 자르지 않고 줄바꿈한다. */
      title={video.videoName}
      badge={
        saved ? (
          <Badge variant="light" color="primary" className="klid-badge-tint">
            저장한 작업 있음
          </Badge>
        ) : undefined
      }
      facts={savedAt !== null ? [<>마지막 저장 {formatPortalDateTime(savedAt)}</>] : undefined}
      /* 열 프레임이 없다는 사실은 걸음 자리 위에 경고 표식과 함께 선다(줄 카드가 표식을 붙인다).
         ★걸음을 잠근 버튼으로 대신하지 않는다 — 이 사유는 사용자가 지금 풀 수 있는 것이 아니라
           해당 영상에 열 프레임이 아직 없다는 사실이라, 누를 수 없는 버튼을 세우면 막다른 길이 는다. */
      actionNote={entryPath === null ? NO_ENTRY_REASON : undefined}
      action={
        entryPath !== null ? (
          <div className="klid-authoring-row-actions" data-align="end">
            {/* ★**링크로 남긴다** — 모양만 킷 버튼이고 실체는 이동이다. 킷 `Button` 은 `as` 로
                어떤 요소로든 설 수 있어(다형 부품) 라우터 `Link` 를 끼우면 시안의 생김새와
                링크의 성질(가운데 클릭 · 새 탭 · 주소 복사)을 둘 다 갖는다.
                ⚠ 킷 버튼은 **무엇으로 서든 `role="button"` 을 박는다** — 그대로 두면 보조기술이
                  「버튼」이라 읽어 링크로 남긴 뜻이 절반만 남는다. 역할을 되돌려 준다. */}
            <Button
              as={Link}
              to={entryPath}
              role="link"
              size="small"
              /* 같은 문구의 걸음이 줄마다 서므로 접근 이름에 영상 이름을 붙인다 — 그러지 않으면
                 보조기술 사용자가 어느 줄의 걸음인지 가릴 수 없다. */
              aria-label={`${video.videoName} ${actionLabel}`}
            >
              {actionLabel}
            </Button>
          </div>
        ) : undefined
      }
    >
      {/* 영상 정보 — 이름은 흐리게 · 값은 진하게, 사이는 세로선(내 업로드 자산 줄과 같은 규칙). */}
      <ConditionChips
        look="text"
        label="영상 정보"
        items={[
          { label: '프레임', value: `${video.frameCount.toLocaleString()}장` },
          { label: '기존 라벨', value: `${video.labelCount.toLocaleString()}건` },
        ]}
      />
    </RecordRow>
  );
}
