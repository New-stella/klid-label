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
 * 거부한다.
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
 * <h3>모양 — 부모 포털의 저작도구 화면을 그대로 입혔다 (2026-09-16)</h3>
 * 형제 화면 다섯(내 작업 · 내 업로드 · 증강 · 마킹 · 라벨링)과 같은 한 벌을 쓴다.
 *   · 판·블록 짜임 = `klid-authoring-*` (styles/portal/authoring-layout.css)
 *   · 부품 = 포털 킷(`components/portal/kit`) + KRDS 킷(`krds-react`)
 *   · <b>상태 하나가 판 한 장</b>이다 — 확인 중·착수 중·진행 중은 <b>빈 판(EmptyState)</b>으로
 *     서서 목록·요약이 올 자리를 그대로 지킨다(비워 두면 실패한 것으로 읽힌다). 준비 완료는
 *     초록 띠 + 요약 판, 실패는 느낌표 빈 판이다.
 *   · 요약은 칩이 아니라 <b>라벨·값 판</b>(KeyValueList)이다 — 되보여 주는 사실이 예닐곱이라
 *     오른쪽 끝선 하나로 내려 읽는 편이 낫다.
 *
 * ⚠ 관제 공통 부품(`components/common/*`)을 쓰지 않는다 — 관제 화면 여럿이 함께 쓰므로 포털 모양을
 *   넣으면 관제 화면이 같이 바뀐다(사용자 확정 구속: **관제향 화면·컴포넌트 불변**).
 * ⚠ 자기 페이지 제목(`h1`)을 두지 않는 형제 화면들과 달리 <b>이 화면은 이동 탭의 목적지가 아니라</b>
 *   바깥에서 곧바로 떨어지는 자리라, 지금 무엇을 하는 중인지 말하는 구역 제목을 둔다.
 *
 * @design SCREEN-046
 * @design INT-014
 * @design INT-013
 * @design ADR-012
 */

import { useEffect, useRef } from 'react';
import { Link, useParams } from 'react-router-dom';
import { Button } from 'krds-react';
// ★새 글리프를 들이지 않는다 — 이 저장소는 「같은 의미 = 같은 아이콘」을 가드로 고정한다
//   (무언가 잘못됐다는 알림은 `CircleAlert`, 비어 있음은 `Inbox`, 다시 하기는 `RotateCcw`).
//   꾸러미·내려받기 계열 글리프를 새로 고르면 같은 뜻이 두 모양으로 갈린다.
//   ⚠ 완료 표식은 화면이 고르지 않는다 — 킷 띠가 tone 에서 꺼낸다(`Alert` 주석).
import { CircleAlert, Inbox, RotateCcw } from 'lucide-react';

import {
  Alert,
  EmptyState,
  KeyValueList,
  StepHeading,
  type KeyValueItem,
} from '@/components/portal/kit';
import { NoteList } from '@/components/portal/authoring';
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

/** 어디에 풀렸는지는 말하지 않는다 — 응답에 경로가 없고, 내부 경로는 밖으로 내보내지 않는다. */
const STORAGE_NOTE = '가져온 소재는 저작도구 작업영역에 보관됩니다.';

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

/**
 * 해제본 요약의 라벨·값 — 응답이 실제로 담는 값만 담는다.
 *
 * ★ 코드·버전·구분은 **비어 올 수 있다**(옛 데이터). 비면 그 줄 자체를 두지 않는다 — 빈 값을
 *   `-` 로 덮으면 「없다」와 「못 읽었다」가 한 표기로 뭉개진다.
 */
export function materialsFactItems(materials: PortalMaterialsSummary): KeyValueItem[] {
  const items: KeyValueItem[] = [
    { label: '항목', value: `${materials.entryCount.toLocaleString()}개` },
    { label: '총 용량', value: formatMaterialsBytes(materials.totalBytes) },
    { label: '영상', value: `${materials.videoCount.toLocaleString()}건` },
  ];
  if (materials.code !== null && materials.code !== '') {
    items.push({ label: '코드', value: materials.code });
  }
  if (materials.version !== null && materials.version !== '') {
    items.push({ label: '버전', value: materials.version });
  }
  if (materials.variant !== null && materials.variant !== '') {
    items.push({ label: '구분', value: materials.variant });
  }
  items.push({ label: '준비 시각', value: formatPortalDateTime(materials.provisionedAt) });
  return items;
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

  const retryStart = () => void startMutation.mutateAsync().catch(() => undefined);

  // ── 주소가 가리키는 것이 데이터셋 식별자가 아니다 ────────────────────────────
  // 조용히 빈 화면으로 두지 않는다. 자동으로 다른 자리로 보내지도 않는다 — 왜 이 화면이 아닌지
  // 모른 채 다른 화면에 떨어지면 잘못된 링크라는 사실 자체가 드러나지 않는다.
  //
  // ★싸개가 `role="alert"` 를 갖는다 — 킷 빈 판은 `busy` 일 때만 읽어 주므로, 그대로 두면 이
  //   주소가 틀렸다는 사실이 보조기술에 한 마디도 가지 않는다(형제 화면 증강과 같은 처리).
  if (datasetId === undefined) {
    return (
      <div className="klid-authoring-pane">
        <div className="klid-authoring-block">
          <div role="alert" data-testid="materials-bad-address">
            <EmptyState
              icon={CircleAlert}
              title="이 주소로는 소재를 찾을 수 없습니다."
              desc="데이터셋 번호가 주소에 올바르게 실리지 않았습니다. 포털의 데이터셋 화면에서 다시 열어 주세요."
              action={
                /* ★**링크로 남긴다** — 모양만 킷 버튼이고 실체는 이동이다. 킷 `Button` 은 `as` 로
                   어떤 요소로든 설 수 있어(다형 부품) 라우터 `Link` 를 끼우면 시안의 생김새와
                   링크의 성질(가운데 클릭 · 새 탭 · 주소 복사)을 둘 다 갖는다.
                   ⚠ 킷 버튼은 **무엇으로 서든 `role="button"` 을 박는다** — 그대로 두면 보조기술이
                     「버튼」이라 읽어 링크로 남긴 뜻이 절반만 남는다. 역할을 되돌려 준다. */
                <Button as={Link} to={HOME_PATH} role="link" variant="secondary" size="medium">
                  {HOME_LABEL}
                </Button>
              }
            />
          </div>
        </div>
      </div>
    );
  }

  const isFailed = status?.state === PortalMaterialsState.FAILED;
  const notice = isFailed ? materialsFailureNotice(status.failureReason) : undefined;

  return (
    <div className="klid-authoring-pane">
      <section className="klid-authoring-block" aria-labelledby="portal-materials">
        <StepHeading
          size="md"
          id="portal-materials"
          title="소재 가져오기"
          /* 데이터셋 번호는 제목 옆 짧은 말이다 — 조작이 아니라 지금 어느 데이터셋을 다루는지
             되보여 주는 값이라 회색 한 단 아래로 물러난다. */
          note={`데이터셋 ${datasetId}`}
          desc="포털에서 고른 데이터셋의 배포본을 작업영역으로 가져옵니다. 용량이 커서 시간이 걸립니다."
        />

        {/* ── 조회 자체가 실패했다 ──
            조달의 실패 상태(본문)와 **다른 축**이라 따로 말한다. 이때는 상태를 하나도 모르므로
            아래 분기가 전부 서지 않는다 — 그래서 목록 자리를 그대로 채우는 빈 판이다.
            ★ 버튼이 이 싸개 **안**에 있어야 한다 — 시험이 띠 안에서 걸음을 찾는다. */}
        {statusQuery.isError && (
          <div role="alert" data-testid="materials-status-error">
            <EmptyState
              icon={CircleAlert}
              title="조달 상태를 확인하지 못했습니다."
              desc={extractBeMessage(
                statusQuery.error,
                '잠시 후 다시 시도해 주세요. 계속되면 운영자에게 알려 주세요.',
              )}
              action={
                <Button variant="secondary" size="medium" onClick={() => void statusQuery.refetch()}>
                  <RotateCcw aria-hidden />
                  다시 확인
                </Button>
              }
            />
          </div>
        )}

        {/* ── 착수 요청 자체가 거부됐다(대기열 포화 503 등) ──
            상태는 멀쩡히 읽히는 중이라 아래 미조달 판이 함께 선다. 그래서 이 자리는 판이 아니라
            **띠**다 — 판을 둘 세우면 어느 것이 지금 상태인지 갈리지 않는다.
            다시 거는 걸음은 아래 미조달 판이 하나만 갖는다(같은 이름의 버튼을 둘 두지 않는다).
            서버 안내 문장을 그대로 싣는다. */}
        {startMutation.isError && (
          <div data-testid="materials-start-error">
            <Alert tone="danger" title="조달을 시작하지 못했습니다.">
              {extractBeMessage(startMutation.error, '잠시 후 다시 시도해 주세요.')}
            </Alert>
          </div>
        )}

        {/* 불러오는 동안 자리를 지킨다 — 비워 두면 실패한 것으로 읽힌다. 도는 고리와 낭독은
            킷 빈 판이 `busy` 로 갖는다(`role="status"` · `aria-busy`). */}
        {statusQuery.isLoading && <EmptyState busy title="소재 상태를 확인하고 있습니다." />}

        {status?.state === PortalMaterialsState.NOT_PROVISIONED &&
          (startMutation.isError ? (
            /* 착수가 거부됐다 — 스스로 다시 걸지 않는다(확정 실패 사유에서 같은 요청이 끝없이
               나간다). 사람이 누르는 길만 남긴다. */
            <div role="status">
              <EmptyState
                icon={Inbox}
                title="아직 가져오지 않았습니다."
                desc="아래 걸음으로 다시 시작할 수 있습니다."
                action={
                  <Button size="medium" onClick={retryStart} disabled={startMutation.isPending}>
                    소재 가져오기
                  </Button>
                }
              />
            </div>
          ) : (
            <EmptyState busy title="소재 가져오기를 시작하고 있습니다." />
          ))}

        {/* 진행률을 그리지 않는다 — 응답에 진행률이 없다. 지어내면 남은 시간을 오해시킨다.
            ★자동 확인에는 예산이 있다(무한 폴링 금지). 예산이 다한 뒤에도 사람이 누르는 길은
              남겨 둔다 — 멈춤과 함께 조작까지 사라지면 새로고침 말고는 빠져나올 길이 없다. */}
        {status?.state === PortalMaterialsState.IN_PROGRESS && (
          <EmptyState
            busy
            title="소재를 가져오는 중입니다. 준비가 끝나면 이 화면이 바뀝니다."
            desc="창을 닫아도 작업은 계속됩니다. 나중에 같은 주소로 다시 들어오면 이어서 확인할 수 있습니다."
            action={
              <Button variant="secondary" size="medium" onClick={() => void statusQuery.refetch()}>
                <RotateCcw aria-hidden />
                상태 다시 확인
              </Button>
            }
          />
        )}

        {status?.state === PortalMaterialsState.READY && (
          <>
            {/* 완료 표식(✓)과 낭독은 띠가 tone 에서 꺼낸다 — 화면이 글리프를 고르지 않는다. */}
            <Alert tone="success" title="소재가 준비됐습니다." />

            {/* 요약은 **`READY` 인데도 비어 올 수 있다**(요약 파일을 읽지 못한 경우). 준비 완료
                판정은 그대로이므로 준비 전으로 되돌리지 않고, 요약만 없다고 말한다. */}
            {status.materials !== null ? (
              <div data-testid="materials-facts">
                <KeyValueList
                  items={materialsFactItems(status.materials)}
                  ariaLabel="가져온 소재 요약"
                />
              </div>
            ) : (
              <NoteList items={['가져온 내용의 요약은 확인할 수 없지만, 소재는 준비돼 있습니다.']} />
            )}
          </>
        )}

        {isFailed && notice !== undefined && (
          <div role="alert">
            <EmptyState
              icon={CircleAlert}
              title={notice.title}
              desc={notice.description}
              action={
                <div className="klid-authoring-row-actions">
                  <Button
                    variant={notice.retryWorthwhile ? 'primary' : 'secondary'}
                    size="medium"
                    onClick={retryStart}
                    disabled={startMutation.isPending}
                  >
                    <RotateCcw aria-hidden />
                    다시 시도
                  </Button>
                  <Button as={Link} to={HOME_PATH} role="link" variant="tertiary" size="medium">
                    {HOME_LABEL}
                  </Button>
                </div>
              }
            />
          </div>
        )}

        {/* 위 분기 넷이 지금 값역을 전부 덮는다. 이 자리는 **서버가 값역을 넓혔을 때**를 위한
            것이다 — 타입은 선언일 뿐 강제가 아니라서 런타임에는 모르는 값이 온다. 그때 이 자리가
            없으면 화면이 상태 칸만 통째로 빈 채로 선다(오류도 경고도 없이). */}
        {!statusQuery.isLoading &&
          !statusQuery.isError &&
          status !== undefined &&
          !isKnownMaterialsState(status.state) && (
            <div role="alert" data-testid="materials-unknown-state">
              <EmptyState
                icon={CircleAlert}
                title="소재 상태를 알 수 없습니다."
                desc="잠시 후 다시 확인해 주세요. 계속되면 운영자에게 알려 주세요."
                action={
                  <Button
                    variant="secondary"
                    size="medium"
                    onClick={() => void statusQuery.refetch()}
                  >
                    <RotateCcw aria-hidden />
                    상태 다시 확인
                  </Button>
                }
              />
            </div>
          )}

        {/* 불렛 한 줄 — 늘 떠 있는 규칙이라 표식이 할 말이 없고(띠가 아니다), 면·선 없이 회색
            글로 물러난다. 어디에 풀렸는지는 말하지 않는다. */}
        <NoteList items={[STORAGE_NOTE]} />
      </section>

      {/* 준비 완료일 때만 연다 — 그 전에 목록을 부르면 서버가 409 로 거부한다. */}
      {status?.state === PortalMaterialsState.READY && <DatasetVideoSection datasetId={datasetId} />}
    </div>
  );
}
