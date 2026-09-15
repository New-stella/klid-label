/**
 * 포털 데이터셋 소재 조달 화면 — `/portal/datasets/:datasetId`.
 *
 * <h3>이 화면이 있는 이유</h3>
 * 포털 데이터셋 상세의 「저작도구로 열기」가 이 주소로 보낸다. 그 주소의 <b>경로 변수</b>에 실린
 * 데이터셋 숫자 식별자를 우리가 <b>직접 읽어</b> 조달 창구에 넘기고, 배포 압축본이 우리 작업영역에
 * 풀릴 때까지의 진행을 보여 준다.
 *
 * ⚠ <b>포털이 props 로 주입하지 않는다</b> — 우리가 주소에서 읽는다.
 * ⚠ <b>질의 문자열이 아니라 경로 변수</b>다. 포털이 질의 문자열을 시도했다가 우리 라우터에 걸리지
 *   않아 홈으로 흐르는 실패를 겪고 바꿨다 — 되돌리지 말 것.
 *
 * <h3>★ 준비 완료 뒤에는 영상을 골라 라벨링으로 들어간다 [@design SCREEN-046]</h3>
 * 흐름: 포털 학습데이터 상세 → 이 화면(소재 가져오기) → **데이터셋 영상 목록** → 라벨링 → 저장.
 * 영상과 프레임은 소재 준비 뒤 원장에 먼저 등록되고, 라벨은 사용자가 **저장했을 때만** 쌓인다
 * (2026-09-15 사용자 확정). 영상 목록 구역은 준비 완료일 때만 연다 — 그 전에 부르면 서버가 409 로
 * 거부한다. ⚠ 구 동작 「준비 완료 뒤 “지금은 여기까지입니다” 안내」는 이 구역으로 대체됐다.
 * ⚠ 영상 등록 단계의 서버 구현은 다음 단계다 — 창구 계약(`API-253`)만 먼저 섰다.
 *
 * <h3>★ 파일 경로를 보여 주지 않는다</h3>
 * 응답에 조달처 절대경로도 저장소 루트도 없다(의도 — CWE-209). 화면이 그 자리를 만들거나 경로를
 * 조합해 지어내지 않는다. 보여 주는 것은 <b>상태 · 사유 · 개수와 크기</b>뿐이다.
 *
 * <h3>진입 시 동작</h3>
 * 먼저 상태를 묻고, 아직 조달한 적이 없으면 <b>스스로 착수</b>한다(사용자가 버튼을 한 번 더 누를
 * 이유가 없다 — 이 주소로 들어온 것이 곧 그 의사다). 착수는 멱등이라 이미 준비됐거나 진행 중이면
 * 서버가 새로 시작하지 않는다.
 *
 * <h3>모양 — DS-002(포털 채널) 축</h3>
 * 관제 공통 부품을 쓰지 않고 포털 전용 계층(`components/portal/ui/*`)을 쓴다(관제향 불변 구속).
 * 자기 페이지 제목(`h1`)을 두지 않는 형제 화면들과 달리 <b>이 화면은 이동 탭의 목적지가 아니라</b>
 * 바깥에서 곧바로 떨어지는 자리라, 지금 무엇을 하는 중인지 말하는 구역 제목을 둔다.
 *
 * @design SCREEN-046
 * @design INT-014
 * @design INT-013
 * @design ADR-012
 */

import { useEffect, useRef } from 'react';
import { Link, useParams } from 'react-router-dom';
// ★새 글리프를 들이지 않는다 — 이 저장소는 「같은 의미 = 같은 아이콘」을 가드로 고정한다
//   (완료는 `CheckCircle2`, 무언가 잘못됐다는 알림은 `CircleAlert`). 꾸러미·내려받기 계열
//   글리프를 새로 고르면 같은 뜻이 두 모양으로 갈린다.
import { CheckCircle2, CircleAlert } from 'lucide-react';

import { PortalAlert } from '@/components/portal/ui/PortalAlert';
import { PortalCard } from '@/components/portal/ui/PortalCard';
import { PortalEmptyState } from '@/components/portal/ui/PortalEmptyState';
import { PortalFactChip } from '@/components/portal/ui/PortalRecordRow';
import { PortalSectionHead } from '@/components/portal/ui/PortalSectionHead';
import { portalButton } from '@/components/portal/ui/portalControl';
import { DatasetVideoSection } from '@/features/portal/materials/components/DatasetVideoSection';
import { formatMaterialsBytes } from '@/features/portal/materials/formatBytes';
import { materialsFailureNotice } from '@/features/portal/materials/failureReason';
import { useDatasetMaterials } from '@/features/portal/materials/hooks/useDatasetMaterials';
import { useStartDatasetMaterials } from '@/features/portal/materials/hooks/useStartDatasetMaterials';
import {
  PortalMaterialsState,
  type PortalMaterialsSummary,
} from '@/features/portal/materials/types';
import { formatPortalDateTime } from '@/features/portal/formatDateTime';
import { extractBeMessage } from '@/lib/api/extractBeMessage';

/** 돌아갈 자리 — 이 배포본 안에 있는 목적지 가운데 가장 가까운 곳. */
const HOME_PATH = '/portal';
const HOME_LABEL = '내 작업으로 가기';

/**
 * 경로 변수를 데이터셋 식별자로 읽는다 — <b>양의 정수만</b>.
 *
 * 서버가 같은 조건으로 거른다(양수 아니면 400). 여기서 먼저 거르는 것은 왕복 한 번을 아끼려는
 * 것이 아니라, <b>부를 값이 아닌 것으로 창구를 부르지 않기 위해서</b>다.
 *
 * ⚠ `Number('')` 는 `0`, `Number(' 3 ')` 은 `3` 이라 느슨하게 읽으면 주소가 아닌 것이 통과한다.
 *   숫자만으로 이뤄진 표기인지 먼저 본다.
 */
export function parseDatasetIdParam(raw: string | undefined): number | undefined {
  if (raw === undefined || !/^\d+$/.test(raw)) return undefined;
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) return undefined;
  return value;
}

/**
 * 우리가 아는 상태인가 — <b>문자열 축으로</b> 본다.
 *
 * ★ 좁혀진 유니온끼리 비교하면 타입 검사기가 「전부 덮었다」고 판정해 모르는 값 가지를 지워
 *   버린다. 그런데 이 값의 원천은 <b>서버 응답</b>이라 타입은 선언일 뿐 강제가 아니다 — 서버가
 *   값역을 넓히는 순간 화면이 아무 분기에도 걸리지 않고 조용히 빈 칸이 된다.
 */
const KNOWN_STATES: readonly string[] = Object.values(PortalMaterialsState);

function isKnownMaterialsState(state: string): boolean {
  return KNOWN_STATES.includes(state);
}

/** 해제본 요약 — 응답이 실제로 담는 값만 보여 준다. */
function MaterialsFacts({ materials }: { materials: PortalMaterialsSummary }) {
  return (
    <div data-testid="materials-facts" className="flex flex-wrap gap-inline">
      <PortalFactChip label="항목" value={`${materials.entryCount.toLocaleString()}개`} />
      <PortalFactChip label="총 용량" value={formatMaterialsBytes(materials.totalBytes)} />
      <PortalFactChip label="영상" value={`${materials.videoCount.toLocaleString()}건`} />
      {/* 코드·구분은 **비어 올 수 있다**(옛 데이터). 비면 그 칩 자체를 그리지 않는다 —
          빈 값을 `-` 로 덮으면 「없다」와 「못 읽었다」가 한 표기로 뭉개진다. */}
      {materials.code !== null && materials.code !== '' && (
        <PortalFactChip label="코드" value={materials.code} />
      )}
      {materials.version !== null && materials.version !== '' && (
        <PortalFactChip label="버전" value={materials.version} />
      )}
      {materials.variant !== null && materials.variant !== '' && (
        <PortalFactChip label="구분" value={materials.variant} />
      )}
      <PortalFactChip label="준비 시각" value={formatPortalDateTime(materials.provisionedAt)} />
    </div>
  );
}

export function PortalDatasetMaterialsPage() {
  const { datasetId: datasetIdParam } = useParams<{ datasetId: string }>();
  const datasetId = parseDatasetIdParam(datasetIdParam);

  const statusQuery = useDatasetMaterials(datasetId);
  const startMutation = useStartDatasetMaterials(datasetId);
  const status = statusQuery.data;

  // 자동 착수는 **데이터셋마다 한 번**이다. 실패 뒤 재시도는 사람이 누른다 — 자동으로 되풀이하면
  // 확정 실패 사유에서 같은 요청이 끝없이 나간다.
  const autoStartedRef = useRef<number | undefined>(undefined);
  const startAsync = startMutation.mutateAsync;
  useEffect(() => {
    if (datasetId === undefined) return;
    if (status?.state !== PortalMaterialsState.NOT_PROVISIONED) return;
    if (autoStartedRef.current === datasetId) return;
    autoStartedRef.current = datasetId;
    // 실패는 아래 `startMutation.isError` 가 화면에 싣는다 — 여기서 삼키되 던지지 않는다.
    void startAsync().catch(() => undefined);
  }, [datasetId, status?.state, startAsync]);

  // ── 주소가 가리키는 것이 데이터셋 식별자가 아니다 ────────────────────────────
  // 조용히 빈 화면으로 두지 않는다. 자동으로 다른 자리로 보내지도 않는다 — 왜 이 화면이 아닌지
  // 모른 채 다른 화면에 떨어지면 잘못된 링크라는 사실 자체가 드러나지 않는다.
  if (datasetId === undefined) {
    return (
      <div className="flex w-full flex-col gap-column">
        <PortalEmptyState
          icon={CircleAlert}
          title="이 주소로는 소재를 찾을 수 없습니다."
          description="데이터셋 번호가 주소에 올바르게 실리지 않았습니다. 포털의 데이터셋 화면에서 다시 열어 주세요."
          action={
            <Link to={HOME_PATH} className={portalButton('secondary')}>
              {HOME_LABEL}
            </Link>
          }
          data-testid="materials-bad-address"
        />
      </div>
    );
  }

  const isFailed = status?.state === PortalMaterialsState.FAILED;
  const notice = isFailed ? materialsFailureNotice(status.failureReason) : undefined;

  return (
    <div className="flex w-full flex-col gap-column">
      <section aria-labelledby="portal-materials" className="flex flex-col gap-in-component">
        <PortalSectionHead
          id="portal-materials"
          title="소재 가져오기"
          lead="포털에서 고른 데이터셋의 배포본을 작업영역으로 가져옵니다. 용량이 커서 시간이 걸립니다."
          count={`데이터셋 ${datasetId}`}
        />

        {/* 조회 자체가 실패한 경우 — 조달의 실패 상태(본문)와 다른 축이라 따로 말한다. */}
        {statusQuery.isError && (
          <PortalAlert
            tone="error"
            live
            title="조달 상태를 확인하지 못했습니다."
            description={extractBeMessage(
              statusQuery.error,
              '잠시 후 다시 시도해 주세요. 계속되면 운영자에게 알려 주세요.',
            )}
            action={
              <button
                type="button"
                className={portalButton('secondary')}
                onClick={() => void statusQuery.refetch()}
              >
                다시 확인
              </button>
            }
            data-testid="materials-status-error"
          />
        )}

        {/* 착수 요청 자체가 거부된 경우(대기열 포화 503 등). 서버 안내 문장을 그대로 싣는다. */}
        {startMutation.isError && (
          <PortalAlert
            tone="error"
            live
            title="조달을 시작하지 못했습니다."
            description={extractBeMessage(
              startMutation.error,
              '잠시 후 다시 시도해 주세요.',
            )}
            action={
              <button
                type="button"
                className={portalButton('secondary')}
                onClick={() => void startMutation.mutateAsync().catch(() => undefined)}
                disabled={startMutation.isPending}
              >
                다시 시도
              </button>
            }
            data-testid="materials-start-error"
          />
        )}

        {statusQuery.isLoading && (
          <PortalCard ariaLabel="소재 조달 상태">
            <p className="text-body-sm text-gray-700" role="status">
              소재 상태를 확인하고 있습니다.
            </p>
          </PortalCard>
        )}

        {status?.state === PortalMaterialsState.NOT_PROVISIONED && (
          <PortalCard ariaLabel="소재 조달 상태">
            <div className="flex flex-col gap-in-component">
              <p className="text-body-sm text-gray-700" role="status">
                {startMutation.isError
                  ? '아직 가져오지 않았습니다.'
                  : '소재 가져오기를 시작하고 있습니다.'}
              </p>
              {startMutation.isError && (
                <div>
                  <button
                    type="button"
                    className={portalButton('primary')}
                    onClick={() => void startMutation.mutateAsync().catch(() => undefined)}
                    disabled={startMutation.isPending}
                  >
                    소재 가져오기
                  </button>
                </div>
              )}
            </div>
          </PortalCard>
        )}

        {status?.state === PortalMaterialsState.IN_PROGRESS && (
          <PortalCard ariaLabel="소재 조달 상태">
            <div className="flex flex-col gap-in-component">
              {/* 진행률을 그리지 않는다 — 응답에 진행률이 없다. 지어내면 남은 시간을 오해시킨다. */}
              <p className="text-body-sm text-gray-700" role="status">
                소재를 가져오는 중입니다. 준비가 끝나면 이 화면이 바뀝니다.
              </p>
              <p className="text-caption text-gray-600">
                창을 닫아도 작업은 계속됩니다. 나중에 같은 주소로 다시 들어오면 이어서 확인할 수
                있습니다.
              </p>
              {/* 자동 확인에는 예산이 있다(무한 폴링 금지). 예산이 다한 뒤에도 사람이 누르는 길은
                  남겨 둔다 — 멈춤과 함께 조작까지 사라지면 새로고침 말고는 빠져나올 길이 없다. */}
              <div>
                <button
                  type="button"
                  className={portalButton('secondary')}
                  onClick={() => void statusQuery.refetch()}
                >
                  상태 다시 확인
                </button>
              </div>
            </div>
          </PortalCard>
        )}

        {status?.state === PortalMaterialsState.READY && (
          <PortalCard ariaLabel="소재 조달 상태">
            <div className="flex flex-col gap-in-component">
              <div className="flex items-start gap-inline">
                <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-success-600" aria-hidden />
                <p className="text-body-sm font-medium text-gray-900" role="status">
                  소재가 준비됐습니다.
                </p>
              </div>

              {/* 요약은 **`READY` 인데도 비어 올 수 있다**(요약 파일을 읽지 못한 경우). 준비 완료
                  판정은 그대로이므로 준비 전으로 되돌리지 않고, 요약만 없다고 말한다. */}
              {status.materials !== null ? (
                <MaterialsFacts materials={status.materials} />
              ) : (
                <p className="text-caption text-gray-600">
                  가져온 내용의 요약은 확인할 수 없지만, 소재는 준비돼 있습니다.
                </p>
              )}
            </div>
          </PortalCard>
        )}

        {isFailed && notice !== undefined && (
          <PortalCard ariaLabel="소재 조달 상태">
            <div className="flex flex-col gap-in-component">
              <div className="flex items-start gap-inline">
                <CircleAlert className="mt-0.5 size-5 shrink-0 text-danger-600" aria-hidden />
                <div className="flex flex-col gap-tight">
                  <p className="text-body-sm font-medium text-gray-900" role="status">
                    {notice.title}
                  </p>
                  <p className="text-caption text-gray-700">{notice.description}</p>
                </div>
              </div>
              <div className="flex flex-wrap gap-inline">
                <button
                  type="button"
                  className={portalButton(notice.retryWorthwhile ? 'primary' : 'secondary')}
                  onClick={() => void startMutation.mutateAsync().catch(() => undefined)}
                  disabled={startMutation.isPending}
                >
                  다시 시도
                </button>
                <Link to={HOME_PATH} className={portalButton('ghost')}>
                  {HOME_LABEL}
                </Link>
              </div>
            </div>
          </PortalCard>
        )}

        {/* 위 분기 넷이 지금 값역을 전부 덮는다. 이 자리는 **서버가 값역을 넓혔을 때**를 위한
            것이다 — 타입은 선언일 뿐 강제가 아니라서 런타임에는 모르는 값이 온다. 그때 이 자리가
            없으면 화면이 상태 칸만 통째로 빈 채로 선다(오류도 경고도 없이). */}
        {!statusQuery.isLoading &&
          !statusQuery.isError &&
          status !== undefined &&
          !isKnownMaterialsState(status.state) && (
            <PortalEmptyState
              icon={CircleAlert}
              title="소재 상태를 알 수 없습니다."
              description="잠시 후 다시 확인해 주세요. 계속되면 운영자에게 알려 주세요."
              action={
                <button
                  type="button"
                  className={portalButton('secondary')}
                  onClick={() => void statusQuery.refetch()}
                >
                  상태 다시 확인
                </button>
              }
              data-testid="materials-unknown-state"
            />
          )}

        {/* 어디에 풀렸는지는 말하지 않는다 — 응답에 경로가 없고, 내부 경로는 밖으로 내보내지 않는다.
            아이콘을 붙이지 않는다 — 문장이 이미 뜻을 다 말해 글리프가 정보를 더하지 않는다. */}
        <p className="text-caption text-gray-600">가져온 소재는 저작도구 작업영역에 보관됩니다.</p>
      </section>

      {/* 준비 완료일 때만 연다 — 그 전에 목록을 부르면 서버가 409 로 거부한다. */}
      {status?.state === PortalMaterialsState.READY && <DatasetVideoSection datasetId={datasetId} />}
    </div>
  );
}
