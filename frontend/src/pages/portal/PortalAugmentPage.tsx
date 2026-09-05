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
// 보안: 사용자 파일명·서버 실패 사유는 JSX 텍스트 노드로만 렌더한다(자동 escape). 남의 요청과
//   없는 요청은 서버가 한 코드로 묶어 거부하므로 화면이 둘을 가르려 하지 않는다.

import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Download, X } from 'lucide-react';

import { Alert } from '@/components/common/Alert';
import { ErrorState } from '@/components/common/ErrorState';
import { Pagination } from '@/components/common/Pagination';
import { StatusBadge } from '@/components/common/StatusBadge';
import { buildPortalUploadLabelPath } from '@/features/portal/labelingEntry';
import {
  EMPTY_CONDITION_TEXT,
  formatGenerationCondition,
  summarizeGenerationCondition,
} from '@/features/portal/augments/generationCondition';
import { usePortalAugment } from '@/features/portal/augments/hooks/usePortalAugment';
import { usePortalAugments } from '@/features/portal/augments/hooks/usePortalAugments';
import {
  PORTAL_AUGMENT_OUTCOME_BADGE,
  resolvePortalAugmentOutcome,
} from '@/features/portal/augments/outcome';
import type { PortalAugmentDetail } from '@/features/portal/augments/types';
import { downloadUploadExport, downloadUploadFile } from '@/features/portal/uploads/api';
import { useUploadDetail } from '@/features/portal/uploads/hooks/useUploadDetail';
import { useUploadFrameImage } from '@/features/portal/uploads/hooks/useUploadFrameImage';
import { formatDateTime } from '@/features/review/formatDateTime';
import { cn } from '@/lib/cn';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { useUiStore } from '@/stores/useUiStore';

/** 목록 한 페이지 건수. 서버 기본값과 같은 값을 명시해 보낸다(BE 기본값에 기대지 않는다). */
const PAGE_SIZE = 20;

/** 대상 영상을 고르러 가는 자리 — 요청 진입은 그 화면이 갖는다. */
const CHOOSE_VIDEO_PATH = '/portal/uploads';
const CHOOSE_VIDEO_LABEL = '증강할 영상 고르러 가기';

/** 표 헤더 셀 — DS-001 표 표면 관례(14px/600 토큰 + 대문자화). <th> 에 직접 건다. */
const TH_CLASS = 'px-3 py-2 text-left text-table-header uppercase tracking-wide text-gray-600';

const ACTION_BUTTON_CLASS =
  'inline-flex items-center gap-1 rounded-lg border border-gray-300 px-3 py-1.5 text-sub font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:opacity-50';

export function PortalAugmentPage() {
  const [page, setPage] = useState(0);
  // 고른 요청. 페이지를 옮기면 그 행이 더는 화면에 없으므로 함께 푼다(아래 effect).
  const [selectedAugSn, setSelectedAugSn] = useState<number | null>(null);

  const listQuery = usePortalAugments({ page, size: PAGE_SIZE });
  const rows = listQuery.data?.content ?? [];
  const totalPages = listQuery.data?.totalPages ?? 0;

  useEffect(() => {
    setSelectedAugSn(null);
  }, [page]);

  const isEmpty = !listQuery.isLoading && !listQuery.isError && rows.length === 0;

  return (
    <div className="mx-auto flex w-full max-w-5xl flex-col gap-6">
      <header>
        <h1 className="text-page-title text-gray-900">증강</h1>
        <p className="text-sub text-gray-600">
          업로드한 영상에 낸 증강 요청의 현황과 결과를 확인합니다.
        </p>
      </header>

      <section
        aria-label="증강 요청 현황"
        className="flex flex-col gap-3 rounded-lg border border-gray-200 bg-white p-5 shadow-sm"
      >
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-section-title text-gray-800">증강 요청 현황</h2>
          {/* 요청이 하나도 없을 때는 이 링크를 그리지 않는다 — 그 자리의 안내 블록이 같은 이름의
              링크를 대신 갖는다(같은 접근 이름의 링크가 한 화면에 둘이면 보조기술 사용자가
              어느 쪽인지 가릴 수 없다). */}
          {!isEmpty && (
            <Link
              to={CHOOSE_VIDEO_PATH}
              className={cn(
                'inline-flex items-center rounded-lg border border-gray-300 px-3 py-1.5 text-sub font-medium text-gray-700 transition-colors hover:bg-gray-50',
                KRDS_FOCUS,
              )}
            >
              {CHOOSE_VIDEO_LABEL}
            </Link>
          )}
        </div>

        {/* 대기 구간을 숨기지 않는다 — 비동기 위탁이라 누른 즉시 결과가 나오지 않는다. */}
        <p className="text-sub text-gray-600">
          요청한 즉시 결과가 나오지 않습니다 — 도착하면 목록의 상태가 바뀝니다.
        </p>

        {listQuery.isLoading ? (
          <p className="text-sub text-gray-600">불러오는 중…</p>
        ) : listQuery.isError ? (
          /* 「조회 실패」와 「실제로 0건」을 반드시 가른다 — 서버 오류를 요청이 사라진 것으로
             오해하면 사용자가 같은 요청을 다시 건다. */
          <ErrorState
            title="요청 현황을 불러올 수 없습니다"
            message="잠시 후 다시 시도해 주세요. 낸 요청이 사라진 것은 아닙니다."
            onRetry={() => void listQuery.refetch()}
          />
        ) : isEmpty ? (
          <EmptyRequests />
        ) : (
          <div className="overflow-hidden rounded-lg border border-gray-200 bg-white shadow-sm">
            <table className="w-full text-body-md" data-testid="portal-augment-table">
              <thead>
                {/* 배경은 헤더 행에, 타이포·색은 각 <th> 에 직접 건다 — <tr>/<thead> 에만 걸면
                    브라우저 UA 기본 `th { font-weight: bold }` 가 상속값을 이긴다. */}
                <tr className="border-b border-gray-200 bg-secondary-50">
                  <th scope="col" className={TH_CLASS}>
                    요청 일시
                  </th>
                  <th scope="col" className={TH_CLASS}>
                    대상 영상
                  </th>
                  <th scope="col" className={TH_CLASS}>
                    생성 조건
                  </th>
                  <th scope="col" className={TH_CLASS}>
                    상태
                  </th>
                  <th scope="col" className={TH_CLASS}>
                    결과 확인
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => {
                  const outcome = resolvePortalAugmentOutcome(row);
                  const badge = PORTAL_AUGMENT_OUTCOME_BADGE[outcome];
                  // 실패 사유는 실패한 행에만 보인다. 빈 값은 「아직 실패하지 않았다」는 뜻이라
                  // 판정과 표시가 같은 축을 쓴다(사유가 있어야 실패다).
                  const failReason = outcome === 'failed' ? (row.failRsnCn ?? '') : '';
                  const targetName = row.orgnlFileNm ?? `영상 ${row.uldSn}`;
                  const requestedAt = formatDateTime(row.requestedAt);
                  return (
                    <tr
                      key={row.augSn}
                      data-testid={`portal-augment-row-${row.augSn}`}
                      className="border-b border-gray-100 transition-colors hover:bg-rowHover"
                    >
                      <td className="px-3 py-2 text-gray-600">{requestedAt}</td>
                      {/* 사용자 파일명 — 텍스트 노드(자동 escape). */}
                      <td className="px-3 py-2 text-gray-700">{targetName}</td>
                      <td className="px-3 py-2 text-gray-700">
                        {summarizeGenerationCondition(row.generationCondition)}
                      </td>
                      {/* 상태 표시와 실패 사유를 **같은 칸**에 담는다 — 사유 전용 열을 두면
                          실패가 아닌 대다수 행에 빈 칸이 남아 목록이 넓어지고, 행을 펼치면 아래
                          행이 밀려 목록을 훑는 흐름이 끊긴다. 한 줄로 두고 넘치면 줄임표라
                          **어떤 행도 두 줄로 늘어나지 않는다**(행 높이가 모든 행에서 같다). */}
                      <td className="px-3 py-2">
                        <div className="flex items-center gap-2">
                          <StatusBadge status={badge.badgeStatus} label={badge.label} />
                          {failReason !== '' && (
                            <span
                              data-testid={`portal-augment-fail-${row.augSn}`}
                              /* 전문은 가리키면 뜨는 말풍선에 담는다. 가리켜 볼 수 없는
                                 환경에서는 그 행의 「결과 확인」으로 들어가 전문을 본다. */
                              title={failReason}
                              className="min-w-0 max-w-xs truncate text-sub text-danger"
                            >
                              {/* 서버 사유 — 텍스트 노드(자동 escape). */}
                              {failReason}
                            </span>
                          )}
                        </div>
                      </td>
                      <td className="px-3 py-2">
                        <button
                          type="button"
                          onClick={() => setSelectedAugSn(row.augSn)}
                          aria-pressed={selectedAugSn === row.augSn}
                          /* 같은 영상에 여러 요청이 공존할 수 있어(중복 요청이 정상 동선이다)
                             접근 이름에 **요청 일시까지** 붙인다 — 파일명만으로는 같은 이름의
                             버튼이 여럿 생겨 보조기술 사용자가 어느 요청인지 가릴 수 없다. */
                          aria-label={`${targetName} ${requestedAt} 요청 결과 확인`}
                          className={cn(ACTION_BUTTON_CLASS, KRDS_FOCUS)}
                        >
                          결과 확인
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {/* 전체가 한 페이지에 들어오면 페이저를 그리지 않는다. */}
        {totalPages > 1 && <Pagination page={page} totalPages={totalPages} onChange={setPage} />}
      </section>

      {selectedAugSn !== null && <AugmentResultSection augSn={selectedAugSn} />}
    </div>
  );
}

/**
 * 요청이 하나도 없을 때 목록 자리에 대신 놓이는 안내.
 *
 * 요청 진입이 이 화면에 없으므로, 빈 화면만 남기면 다음에 무엇을 해야 할지 찾을 수 없다.
 */
function EmptyRequests() {
  return (
    <div className="flex flex-col items-start gap-3 py-6">
      <p className="text-body text-gray-600">아직 요청한 증강이 없습니다.</p>
      <Link
        to={CHOOSE_VIDEO_PATH}
        className={cn(
          'inline-flex items-center rounded-lg bg-primary-600 px-3 py-1.5 text-sub font-medium text-white transition-colors hover:bg-primary-700',
          KRDS_FOCUS,
        )}
      >
        {CHOOSE_VIDEO_LABEL}
      </Link>
    </div>
  );
}

/** 고른 요청의 결과 확인 — 대기·실패·도착 셋을 각각 다른 모습으로 보인다. */
function AugmentResultSection({ augSn }: { augSn: number }) {
  const detailQuery = usePortalAugment(augSn);
  const detail = detailQuery.data;

  return (
    <section
      aria-label="증강 결과 확인"
      data-testid="portal-augment-result"
      className="flex flex-col gap-4 rounded-lg border border-gray-200 bg-white p-5 shadow-sm"
    >
      <h2 className="text-section-title text-gray-800">증강 결과 확인</h2>

      {detailQuery.isLoading ? (
        <p className="text-sub text-gray-600">불러오는 중…</p>
      ) : detailQuery.isError || !detail ? (
        <ErrorState
          title="요청 정보를 불러올 수 없습니다"
          message="잠시 후 다시 시도해 주세요."
          onRetry={() => void detailQuery.refetch()}
        />
      ) : (
        <AugmentResultBody detail={detail} />
      )}
    </section>
  );
}

function AugmentResultBody({ detail }: { detail: PortalAugmentDetail }) {
  const outcome = resolvePortalAugmentOutcome(detail);
  const conditions = formatGenerationCondition(detail.generationCondition);
  const targetName = detail.orgnlFileNm ?? `영상 ${detail.uldSn}`;

  return (
    <>
      {/* 요청 정보 — 대상 영상 · 생성 조건 · 요청 일시 · 결과 도착 일시. */}
      <dl className="grid grid-cols-1 gap-x-6 gap-y-2 md:grid-cols-2">
        <Row term="대상 영상" desc={targetName} />
        <Row
          term="생성 조건"
          desc={
            conditions.length === 0
              ? EMPTY_CONDITION_TEXT
              : conditions.map((c) => `${c.key}: ${c.value}`).join(' · ')
          }
        />
        <Row term="요청 일시" desc={formatDateTime(detail.requestedAt)} />
        <Row
          term="결과 도착 일시"
          /* 도착하지 않은 요청은 자리를 비운다 — `없음` 같은 문구를 지어내면 도착이 정해졌는데
             표기만 빈 것으로 읽힌다(만료 예정일 표기와 같은 관례). */
          desc={detail.resultArrivedAt === null ? '-' : formatDateTime(detail.resultArrivedAt)}
        />
      </dl>

      {outcome === 'waiting' && (
        <p data-testid="portal-augment-waiting" className="text-body text-gray-600">
          결과가 아직 도착하지 않았습니다.
        </p>
      )}

      {outcome === 'failed' && (
        <Alert variant="error" title="증강이 실패했습니다" data-testid="portal-augment-failed">
          {/* 서버가 준 사유를 그대로 텍스트로 싣는다 — 사실만 알리고 원인을 감추지 않는다. */}
          {detail.failRsnCn}
        </Alert>
      )}

      {outcome === 'ready' && detail.resultUldSn !== null && (
        <AugmentReadyResult resultUldSn={detail.resultUldSn} augSn={detail.augSn} name={targetName} />
      )}
    </>
  );
}

function Row({ term, desc }: { term: string; desc: string }) {
  return (
    <div className="flex flex-col gap-0.5">
      <dt className="text-sub text-gray-500">{term}</dt>
      <dd className="text-body text-gray-800">{desc}</dd>
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
    <div className="flex flex-col gap-4">
      <AugmentResultPreview resultUldSn={resultUldSn} />

      <div className="flex flex-col gap-2">
        <Link
          to={buildPortalUploadLabelPath(resultUldSn)}
          data-testid="portal-augment-labeling-entry"
          className={cn(
            'inline-flex w-fit items-center rounded-lg bg-primary-600 px-3 py-1.5 text-sub font-medium text-white transition-colors hover:bg-primary-700',
            KRDS_FOCUS,
          )}
        >
          라벨링 이어서 하기
        </Link>
        {/* 후속 작업 범위 — 이 경로에 없는 것을 기대하지 않도록 미리 알린다. */}
        <p className="text-sub text-gray-600">
          후속 작업에서는 바운딩 박스·폴리곤 수동 라벨링만 제공합니다.
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
    <div className="flex h-48 w-full items-center justify-center overflow-hidden rounded-lg border border-gray-200 bg-gray-50">
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
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={exportLabels}
          disabled={downloading !== null}
          aria-label="증강 결과물 라벨 내보내기"
          className={cn(ACTION_BUTTON_CLASS, KRDS_FOCUS)}
        >
          {/* 두 버튼 모두 '내려받기'라 같은 아이콘을 쓴다 — 구분은 라벨이 한다. */}
          <Download className="h-3.5 w-3.5" aria-hidden />
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
          className={cn(ACTION_BUTTON_CLASS, KRDS_FOCUS)}
        >
          <Download className="h-3.5 w-3.5" aria-hidden />
          원본 파일 내려받기
        </button>
        {downloading === 'file' && (
          <button
            type="button"
            onClick={() => abortController?.abort()}
            aria-label="증강 결과물 원본 파일 내려받기 취소"
            className={cn(ACTION_BUTTON_CLASS, KRDS_FOCUS)}
          >
            <X className="h-3.5 w-3.5" aria-hidden />
            내려받기 취소
          </button>
        )}
      </div>
      <p className="text-sub text-gray-600">본인이 낸 요청의 결과물만 내려받을 수 있습니다.</p>
    </div>
  );
}
