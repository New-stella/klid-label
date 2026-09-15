// 포털 증강 화면 — 본인이 올린 영상에 낸 증강 요청의 **현황 · 결과 확인 · 후속 작업 진입 ·
// 결과물 내려받기**. 요청을 거는 자리는 여기가 아니다. [@design SCREEN-044]
//
// ★ **요청 진입을 여기 두지 않는다.** 증강을 거는 자리는 포털 업로드 화면(SCREEN-033)의 자산별
//   액션이고, 이 화면에는 **대상 영상을 고르러 가는 링크만** 둔다 — 같은 행위의 진입이 둘이 되면
//   어느 쪽이 정본인지 알 수 없고, 목록·조건·상태가 두 화면에서 갈린다.
//
// ★★ **채택·반려가 없다.** 이 경로에는 검수가 없어 결과물을 가르는 결정 단계 자체가 없다.
//   관제 증강의 검수자 결정 축(채택/반려)을 여기 재사용하면 그 승인 행이 영영 생기지 않아
//   포털 파생본이 통째로 사라진다. 확인한 결과물은 그대로 후속 작업과 내려받기로 이어진다.
//
// ★★★ **결과가 도착한 요청에만** 후속 작업 진입과 내려받기를 노출한다. 기다리는 중이거나 실패한
//   요청에는 노출하지 않는다 — 눌러 봐야 거절되는 자리를 만들면 회복 경로를 잘못 안내한다
//   (포털 업로드 목록이 「할 수 없는 액션은 비활성이 아니라 미노출」로 이미 세운 관례).
//
// <h3>모양 — DS-002(포털 채널) 축</h3>
// 관제 공통 부품(`components/common/*`)을 쓰지 않고 포털 전용 계층(`components/portal/ui/*`)을 쓴다.
// 그 부품들은 관제 화면 여럿이 함께 쓰므로 포털 모양을 넣으면 관제 화면이 같이 바뀐다
// (사용자 확정 구속: **관제향 화면·컴포넌트 불변**).
//
// ★ **자기 페이지 제목(`h1`)을 두지 않는다** — Host 머리 영역이 서비스 이름을, 본문 상단 이동
//   탭의 활성 항목이 화면 이름을 이미 말한다(형제 화면 SCREEN-028 시안이 같은 이유로 제목 밴드를
//   걷어냈다). 구 동작 폐기 — 이 화면에는 `증강` 이라는 제목이 탭과 나란히 두 번 떠 있었다.
//
// ★★ **표가 아니라 행 카드다 (2026-09-08 반전).** 한 행이 요구하는 최소 폭이
//   `일시 144 + 파일명 400 + 생성 조건 444 + 상태 534 + 조작 114 ≈ 1,636px` 인데 본문 최대 폭은
//   **1,200px** 이라 436px 이 구조적으로 모자란다. 그래서 열 폭을 어떻게 나눠도 어느 칸이든 반드시
//   접히거나 잘렸다 — 실제로 생성 조건 칸이 최소 폭까지 눌려 **한 글자씩 세로로 흘러내렸다.**
//   ⚠ 구 처리 폐기 — *줄임표로 자르고 전문은 말풍선에 담는다*. 말줄임은 폭 부족분을 이용자에게
//     떠넘긴 것이고(사유를 읽으려면 매번 「결과 확인」을 눌러야 했다), 시안(SD-026)은 이 화면
//     계열에서 그 처리와 `title` 보완을 **둘 다 이미 거부**했다(터치 환경에서 뜨지 않고 게시본
//     정리기가 그 속성을 지운다). **되살리지 말 것.**
//   ⇒ 행 카드는 **줄바꿈이 손해가 아닌 구조**라 부족분 자체가 사라진다. 생성 조건은 칩으로 흩어
//     항목마다 따로 읽히고, 실패 사유는 전문이 그대로 보인다.
//
// 보안: 사용자 파일명·서버 실패 사유는 JSX 텍스트 노드로만 렌더한다(자동 escape). 남의 요청과
// 없는 요청은 서버가 한 코드로 묶어 거부하므로 화면이 둘을 가르려 하지 않는다.

import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { CircleAlert, Download, Sparkles, X } from 'lucide-react';

import { Pagination } from '@/components/common/Pagination';
import { PortalAlert } from '@/components/portal/ui/PortalAlert';
import { PortalBadge, type PortalBadgeTone } from '@/components/portal/ui/PortalBadge';
import {
  PortalFactChip,
  PortalRecordList,
  PortalRecordRow,
} from '@/components/portal/ui/PortalRecordRow';
import { PortalCard } from '@/components/portal/ui/PortalCard';
import { PortalEmptyState } from '@/components/portal/ui/PortalEmptyState';
import { PortalListSkeleton } from '@/components/portal/ui/PortalListSkeleton';
import { PortalSectionHead } from '@/components/portal/ui/PortalSectionHead';
import {
  PORTAL_TILE,
  portalButton,
  portalButtonSm,
} from '@/components/portal/ui/portalControl';
import { buildPortalUploadLabelPath } from '@/features/portal/labelingEntry';
import {
  EMPTY_CONDITION_TEXT,
  formatGenerationCondition,
} from '@/features/portal/augments/generationCondition';
import { usePortalAugment } from '@/features/portal/augments/hooks/usePortalAugment';
import { usePortalAugments } from '@/features/portal/augments/hooks/usePortalAugments';
import {
  PORTAL_AUGMENT_OUTCOME_BADGE,
  resolvePortalAugmentOutcome,
  type PortalAugmentOutcome,
} from '@/features/portal/augments/outcome';
import type { PortalAugmentDetail } from '@/features/portal/augments/types';
import { downloadUploadExport, downloadUploadFile } from '@/features/portal/uploads/api';
import { useUploadDetail } from '@/features/portal/uploads/hooks/useUploadDetail';
import { useUploadFrameImage } from '@/features/portal/uploads/hooks/useUploadFrameImage';
import { formatPortalDateTime } from '@/features/portal/formatDateTime';
import { cn } from '@/lib/cn';
import { useUiStore } from '@/stores/useUiStore';

/** 목록 한 페이지 건수. 서버 기본값과 같은 값을 명시해 보낸다(BE 기본값에 기대지 않는다). */
const PAGE_SIZE = 20;

/** 대상 영상을 고르러 가는 자리 — 요청 진입은 그 화면이 갖는다. */
const CHOOSE_VIDEO_PATH = '/portal/uploads';
const CHOOSE_VIDEO_LABEL = '증강할 영상 고르러 가기';

/**
 * 귀결 → 배지 톤.
 *
 * ★판정(`resolvePortalAugmentOutcome`)과 **표기 문구**(`PORTAL_AUGMENT_OUTCOME_BADGE`)는 그대로
 *  쓰고 여기서는 **색만** 정한다 — 그 모듈은 관제 공통 배지의 톤 키를 나르는데, 포털 배지는
 *  자기 톤 이름을 갖는다. 문구를 여기 복제하면 두 번째 진실원이 된다.
 */
const OUTCOME_TONE: Record<PortalAugmentOutcome, PortalBadgeTone> = {
  waiting: 'gray',
  ready: 'success',
  failed: 'danger',
};

export function PortalAugmentPage() {
  const [page, setPage] = useState(0);
  // 고른 요청. 페이지를 옮기면 그 행이 더는 화면에 없으므로 함께 푼다(아래 effect).
  const [selectedAugSn, setSelectedAugSn] = useState<number | null>(null);

  const listQuery = usePortalAugments({ page, size: PAGE_SIZE });
  const rows = listQuery.data?.content ?? [];
  const totalElements = listQuery.data?.totalElements ?? 0;
  const totalPages = listQuery.data?.totalPages ?? 0;

  useEffect(() => {
    setSelectedAugSn(null);
  }, [page]);

  const isEmpty = !listQuery.isLoading && !listQuery.isError && rows.length === 0;
  const hasRows = !listQuery.isLoading && !listQuery.isError && rows.length > 0;

  return (
    <div className="flex w-full flex-col gap-column">
      <section aria-labelledby="portal-augment-list" className="flex flex-col gap-in-component">
        <PortalSectionHead
          id="portal-augment-list"
          title="증강 요청 현황"
          /* 대기 구간을 숨기지 않는다 — 비동기 위탁이라 누른 즉시 결과가 나오지 않는다.
             ★부정으로 시작하지 않는다: 구 문구 «요청한 즉시 결과가 나오지 않습니다» 는 무엇이
               잘못된 것처럼 읽혔다. 사실은 같고 말투만 바꾼다(사양 SCREEN-044). */
          lead="증강은 시간이 걸립니다. 결과가 도착하면 목록의 상태가 바뀝니다."
          count={hasRows ? `${totalElements}건` : undefined}
          /* 요청이 하나도 없을 때는 이 링크를 그리지 않는다 — 그 자리의 빈 상태가 같은 이름의
             링크를 대신 갖는다(같은 접근 이름의 링크가 한 화면에 둘이면 보조기술 사용자가
             어느 쪽인지 가릴 수 없다). */
          action={
            hasRows ? (
              <Link to={CHOOSE_VIDEO_PATH} className={portalButton('secondary')}>
                {CHOOSE_VIDEO_LABEL}
              </Link>
            ) : undefined
          }
        />

        {listQuery.isLoading ? (
          <>
            <p role="status" className="text-body-sm text-gray-600">
              요청 현황을 불러오고 있습니다.
            </p>
            <PortalListSkeleton />
          </>
        ) : listQuery.isError ? (
          /* 「조회 실패」와 「실제로 0건」을 반드시 가른다 — 서버 오류를 요청이 사라진 것으로
             오해하면 사용자가 같은 요청을 다시 건다. */
          <PortalAlert
            tone="error"
            title="요청 현황을 불러올 수 없습니다"
            description="잠시 후 다시 시도해 주세요. 낸 요청이 사라진 것은 아닙니다."
            live
            action={
              <button
                type="button"
                onClick={() => void listQuery.refetch()}
                className={portalButtonSm('secondary')}
              >
                다시 시도
              </button>
            }
          />
        ) : isEmpty ? (
          <PortalEmptyState
            icon={Sparkles}
            title="아직 요청한 증강이 없습니다."
            description="내 업로드에서 준비가 끝난 영상을 골라 증강을 요청할 수 있습니다."
            action={
              <Link to={CHOOSE_VIDEO_PATH} className={portalButton('primary')}>
                {CHOOSE_VIDEO_LABEL}
              </Link>
            }
          />
        ) : (
          <PortalRecordList aria-label="증강 요청 목록" data-testid="portal-augment-list">
            {rows.map((row) => {
              const outcome = resolvePortalAugmentOutcome(row);
              const badge = PORTAL_AUGMENT_OUTCOME_BADGE[outcome];
              // 실패 사유는 실패한 행에만 보인다. 빈 값은 「아직 실패하지 않았다」는 뜻이라
              // 판정과 표시가 같은 축을 쓴다(사유가 있어야 실패다).
              const failReason = outcome === 'failed' ? (row.failRsnCn ?? '') : '';
              const targetName = row.orgnlFileNm ?? `영상 ${row.uldSn}`;
              const requestedAt = formatPortalDateTime(row.requestedAt);
              const conditions = formatGenerationCondition(row.generationCondition);
              const selected = selectedAugSn === row.augSn;
              return (
                <li key={row.augSn}>
                  <PortalRecordRow
                    data-testid={`portal-augment-row-${row.augSn}`}
                    selected={selected}
                    /* 사용자 파일명 — 텍스트 노드(자동 escape). 자르지 않고 줄바꿈한다. */
                    title={targetName}
                    titleAside={<PortalBadge tone={OUTCOME_TONE[outcome]}>{badge.label}</PortalBadge>}
                    body={
                      <>
                        {/*
                          ★생성 조건을 **칩으로 흩는다.** 다섯 항목을 `시간대: 밤 · 계절: 겨울 · …`
                            한 덩이로 이으면 폭이 모자랄 때 통째로 잘려 어느 값이 사라졌는지조차
                            알 수 없다. 칩은 줄바꿈이 자연스럽고 항목마다 따로 읽힌다.
                          ★**빠짐없이 보인다** — 아는 다섯 뒤에 모르는 항목도 받은 그대로 잇는다
                            (`formatGenerationCondition` 가 그 차례를 정한다). 화면이 거르지 않는다.
                        */}
                        {conditions.length === 0 ? (
                          <span className="text-body-sm text-gray-500">
                            생성 조건 {EMPTY_CONDITION_TEXT}
                          </span>
                        ) : (
                          <span className="flex flex-wrap items-center gap-tight">
                            {conditions.map((c) => (
                              <PortalFactChip key={c.key} label={c.label} value={c.value} />
                            ))}
                          </span>
                        )}

                        {/*
                          ★실패 사유는 **전문을 보인다.** 말줄임으로 자르면 무엇을 고쳐 다시
                            요청해야 하는지가 사라져, 사유를 읽으려고 매번 「결과 확인」을 눌러야
                            했다. 행 카드는 줄이 늘어나도 손해가 아니다.
                          서버 사유 — 텍스트 노드(자동 escape).
                        */}
                        {failReason !== '' && (
                          <span
                            data-testid={`portal-augment-fail-${row.augSn}`}
                            className="flex items-start gap-tight text-body-sm text-pretty text-danger-700"
                          >
                            <CircleAlert
                              className="mt-0.5 size-4 shrink-0"
                              strokeWidth={2}
                              aria-hidden
                            />
                            {failReason}
                          </span>
                        )}
                      </>
                    }
                    meta={
                      <>
                        <span>요청 {requestedAt}</span>
                        {row.resultArrivedAt !== null && (
                          <span>도착 {formatPortalDateTime(row.resultArrivedAt)}</span>
                        )}
                      </>
                    }
                    actions={
                      <button
                        type="button"
                        onClick={() => setSelectedAugSn(row.augSn)}
                        aria-pressed={selected}
                        /* 같은 영상에 여러 요청이 공존할 수 있어(중복 요청이 정상 동선이다)
                           접근 이름에 **요청 일시까지** 붙인다 — 파일명만으로는 같은 이름의
                           버튼이 여럿 생겨 보조기술 사용자가 어느 요청인지 가릴 수 없다. */
                        aria-label={`${targetName} ${requestedAt} 요청 결과 확인`}
                        className={portalButtonSm(selected ? 'primary' : 'secondary')}
                      >
                        결과 확인
                      </button>
                    }
                  />
                </li>
              );
            })}
          </PortalRecordList>
        )}

        {/* 전체가 한 페이지에 들어오면 페이저를 그리지 않는다. */}
        {totalPages > 1 && <Pagination page={page} totalPages={totalPages} onChange={setPage} />}
      </section>

      {selectedAugSn !== null && <AugmentResultSection augSn={selectedAugSn} />}
    </div>
  );
}

/** 고른 요청의 결과 확인 — 대기·실패·도착 셋을 각각 다른 모습으로 보인다. */
function AugmentResultSection({ augSn }: { augSn: number }) {
  const detailQuery = usePortalAugment(augSn);
  const detail = detailQuery.data;

  return (
    <PortalCard
      title="증강 결과 확인"
      ariaLabel="증강 결과 확인"
      className="scroll-mt-4"
      bodyClassName="flex flex-col gap-in-component"
    >
      <div data-testid="portal-augment-result" className="flex flex-col gap-in-component">
        {detailQuery.isLoading ? (
          <p role="status" className="text-body-sm text-gray-600">
            요청 정보를 불러오고 있습니다.
          </p>
        ) : detailQuery.isError || !detail ? (
          <PortalAlert
            tone="error"
            title="요청 정보를 불러올 수 없습니다"
            description="잠시 후 다시 시도해 주세요."
            live
            action={
              <button
                type="button"
                onClick={() => void detailQuery.refetch()}
                className={portalButtonSm('secondary')}
              >
                다시 시도
              </button>
            }
          />
        ) : (
          <AugmentResultBody detail={detail} />
        )}
      </div>
    </PortalCard>
  );
}

function AugmentResultBody({ detail }: { detail: PortalAugmentDetail }) {
  const outcome = resolvePortalAugmentOutcome(detail);
  const conditions = formatGenerationCondition(detail.generationCondition);
  const targetName = detail.orgnlFileNm ?? `영상 ${detail.uldSn}`;

  return (
    <>
      {/* 요청 정보 — 대상 영상 · 생성 조건 · 요청 일시 · 결과 도착 일시. */}
      <dl className={cn(PORTAL_TILE, 'grid grid-cols-1 gap-x-column gap-y-in-component p-in-component md:grid-cols-2')}>
        <Row term="대상 영상" desc={targetName} />
        <Row
          term="생성 조건"
          desc={
            conditions.length === 0
              ? EMPTY_CONDITION_TEXT
              : conditions.map((c) => `${c.key}: ${c.value}`).join(' · ')
          }
        />
        <Row term="요청 일시" desc={formatPortalDateTime(detail.requestedAt)} />
        <Row
          term="결과 도착 일시"
          /* 도착하지 않은 요청은 자리를 비운다 — `없음` 같은 문구를 지어내면 도착이 정해졌는데
             표기만 빈 것으로 읽힌다(만료 예정일 표기와 같은 관례). */
          desc={detail.resultArrivedAt === null ? '-' : formatPortalDateTime(detail.resultArrivedAt)}
        />
      </dl>

      {outcome === 'waiting' && (
        <p data-testid="portal-augment-waiting" className="text-body-md text-gray-600">
          아직 결과를 기다리는 중입니다. 잠시 뒤 다시 확인해 주세요.
        </p>
      )}

      {outcome === 'failed' && (
        <div data-testid="portal-augment-failed">
          <PortalAlert
            tone="error"
            title="증강이 실패했습니다"
            /* 서버가 준 사유를 그대로 텍스트로 싣는다 — 사실만 알리고 원인을 감추지 않는다. */
            description={detail.failRsnCn}
          />
        </div>
      )}

      {outcome === 'ready' && detail.resultUldSn !== null && (
        <AugmentReadyResult resultUldSn={detail.resultUldSn} augSn={detail.augSn} name={targetName} />
      )}
    </>
  );
}

function Row({ term, desc }: { term: string; desc: string }) {
  return (
    <div className="flex flex-col gap-tight">
      <dt className="text-caption font-medium text-gray-500">{term}</dt>
      <dd className="text-body-md text-gray-800">{desc}</dd>
    </div>
  );
}

/**
 * 결과가 도착한 요청의 본문 — 미리보기 · 후속 작업 진입 · 내려받기.
 *
 * ★ 세 조작 모두 계약이 준 **결과물 자산 식별자 하나**로 이어진다. 화면이 자산 식별자를 따로
 *   유추하지 않는다(계약이 *"후속 작업 진입과 내려받기 창구가 받는 자산 식별자와 같은 축"* 이라고
 *   못박았다).
 */
function AugmentReadyResult({
  resultUldSn,
  augSn,
  name,
}: {
  resultUldSn: number;
  augSn: number;
  name: string;
}) {
  return (
    <div className="flex flex-col gap-in-component">
      <AugmentResultPreview resultUldSn={resultUldSn} />

      <div className="flex flex-col gap-tight">
        <Link
          to={buildPortalUploadLabelPath(resultUldSn)}
          data-testid="portal-augment-labeling-entry"
          className={portalButton('primary', 'w-fit')}
        >
          라벨링 이어서 하기
        </Link>
        {/* 후속 작업 범위 — 이 경로에 없는 것을 기대하지 않도록 미리 알린다. */}
        <p className="text-body-sm text-gray-500">
          이어지는 작업에서는 바운딩 박스와 폴리곤을 직접 그려 라벨을 답니다.
        </p>
      </div>

      <AugmentResultDownloads resultUldSn={resultUldSn} augSn={augSn} name={name} />
    </div>
  );
}

/**
 * 결과물 미리보기.
 *
 * ⚠ **미리보기 이미지를 조달할 전용 창구가 계약에 없다.** 단건 조회는 결과물의 *위치*(자산
 *   식별자)만 알려 주고 파일을 내보내지 않는다. 그래서 그 자산이 **포털 업로드 자산과 같은 축**
 *   이라는 계약 문장에 기대어, 이미 있는 포털 업로드 자산 조회·프레임 이미지 경로를 그대로 쓴다.
 *
 * ⚠⚠ **실패해도 조용히 비운다.** 결과물에 프레임이 없거나 조회가 거절돼도 오류 배너를 띄우지
 *   않는다 — 미리보기는 보조 정보이고, 그것 때문에 후속 작업·내려받기가 못 쓰는 것처럼 보이면
 *   안 된다. (이 구역이 계약 공백 위에 서 있다는 사실을 보고에 남긴다.)
 */
function AugmentResultPreview({ resultUldSn }: { resultUldSn: number }) {
  const assetQuery = useUploadDetail(resultUldSn);
  const firstFrameSn = assetQuery.data?.frames?.[0]?.uldFrmeSn;
  const { url } = useUploadFrameImage(firstFrameSn);

  if (!url) return null;

  // 높이를 고정해 이미지가 늦게 붙어도 레이아웃이 밀리지 않게 한다(CLS).
  //
  // ⚠ **이미 다른 화면이 쓰고 있는 높이 단계만 고른다.** 스타일시트는 두 채널이 한 벌을 쓰므로,
  //   포털 화면에서만 쓰는 새 유틸리티 클래스 하나가 **관제 산출물의 CSS 까지 바꾼다**(한 단계 큰
  //   높이를 골랐더니 관제 CSS 가 39바이트 늘어난 것을 빌드로 실측했다).
  // ⚠⚠ **그 클래스 이름을 주석에도 적지 말 것.** Tailwind 는 소스 파일의 **본문 전체**를 훑어
  //   클래스 후보를 찾으므로, 코드에서 걷어내도 주석에 남아 있으면 그대로 다시 생성된다
  //   (되돌린 뒤에도 관제 CSS 가 그대로여서 이 사실을 알았다).
  return (
    <div className="flex h-48 w-full items-center justify-center overflow-hidden rounded-tile border border-gray-200 bg-gray-50">
      <img
        src={url}
        alt="증강 결과물 미리보기"
        loading="lazy"
        data-testid="portal-augment-preview"
        className="h-full w-full object-contain"
      />
    </div>
  );
}

/**
 * 결과물 내려받기 두 갈래 — 라벨 내보내기(JSON) · 원본 파일.
 *
 * ★ 결과물 자산 식별자를 그대로 포털 업로드 자산 내려받기 창구에 넘긴다(API-157 · API-159).
 *   같은 조작을 위해 새 창구를 만들지 않는다.
 *
 * ★★ **취소는 원본 파일에만 둔다.** 원본은 매우 커질 수 있어 한 번 시작하면 오래 붙잡히지만,
 *   라벨 내보내기는 작아서 취소 버튼이 뜨기도 전에 끝난다(포털 업로드 목록이 세운 관례).
 *   사용자가 누른 취소는 오류가 아니므로 실패 안내를 띄우지 않는다 — 판정 근거는 오류 객체가
 *   아니라 **화면이 스스로 중단을 걸었는지**다(공용 클라이언트가 취소 표식을 남기지 않는다).
 */
function AugmentResultDownloads({
  resultUldSn,
  augSn,
  name,
}: {
  resultUldSn: number;
  augSn: number;
  name: string;
}) {
  const pushToast = useUiStore((s) => s.pushToast);
  const [downloading, setDownloading] = useState<'export' | 'file' | null>(null);
  const [abortController, setAbortController] = useState<AbortController | null>(null);

  const exportLabels = () => {
    if (downloading) return;
    setDownloading('export');
    downloadUploadExport(resultUldSn)
      .catch(() => pushToast({ variant: 'error', message: '내보내기에 실패했습니다.' }))
      .finally(() => setDownloading(null));
  };

  const downloadFile = () => {
    if (downloading) return;
    const controller = new AbortController();
    setAbortController(controller);
    setDownloading('file');
    // ref/state 가 아니라 지역 변수를 닫아 쓴다 — 다음 요청이 덮어써도 이 catch 는 자기 요청의
    // 중단 여부를 본다.
    downloadUploadFile(resultUldSn, `augment-${augSn}-${name}`, controller.signal)
      .catch(() => {
        if (controller.signal.aborted) return; // 사용자가 스스로 멈춘 것 — 정상 종료
        pushToast({ variant: 'error', message: '원본 다운로드에 실패했습니다.' });
      })
      .finally(() => {
        setAbortController((cur) => (cur === controller ? null : cur));
        setDownloading(null);
      });
  };

  return (
    <div className="flex flex-col gap-tight">
      <div className="flex flex-wrap items-center gap-inline">
        <button
          type="button"
          onClick={exportLabels}
          disabled={downloading !== null}
          aria-label="증강 결과물 라벨 내보내기"
          className={portalButtonSm('secondary')}
        >
          {/* 두 버튼 모두 '내려받기'라 같은 아이콘을 쓴다 — 구분은 라벨이 한다. */}
          <Download className="size-3.5" strokeWidth={2} aria-hidden />
          라벨 내보내기
        </button>
        <button
          type="button"
          onClick={downloadFile}
          disabled={downloading !== null}
          aria-label="증강 결과물 원본 파일 내려받기"
          /* 진행 사실은 보조기술에도 전달한다 — 영상은 오래 걸릴 수 있어 «눌렸는데 아무 일도
             없다» 로 보이면 안 된다. */
          aria-busy={downloading === 'file' || undefined}
          className={portalButtonSm('secondary')}
        >
          <Download className="size-3.5" strokeWidth={2} aria-hidden />
          원본 파일 내려받기
        </button>
        {downloading === 'file' && (
          <button
            type="button"
            onClick={() => abortController?.abort()}
            aria-label="증강 결과물 원본 파일 내려받기 취소"
            className={portalButtonSm('danger')}
          >
            <X className="size-3.5" strokeWidth={2} aria-hidden />
            내려받기 취소
          </button>
        )}
      </div>
      <p className="text-body-sm text-gray-500">내가 낸 요청의 결과물만 내려받을 수 있습니다.</p>
    </div>
  );
}
