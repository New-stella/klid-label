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
// <h3>모양 — 부모 포털의 저작도구 증강 탭을 그대로 입혔다 (2026-09-16)</h3>
// 부모 포털(KLID_Portal)이 이 화면을 **자기 부품으로 다시 그려** 「저작도구 쪽에 넘기는 기준」으로
// 삼았고(`pages/workspace/authoring/AuthoringAugmentView.tsx`), 그 짜임을 여기에 옮겼다.
//   · 판·블록·줄 카드 짜임 = `klid-authoring-*` · 창 안 미리보기·걸음 줄 = `klid-augment-*`
//     (styles/portal/authoring-layout.css · augment-view.css). ⚠ 화면이 그 CSS 를 스스로
//     import 하지 않는다 — 포털 채널 스타일 로드의 단일 지점은 `styles/portalLook.ts` 이고,
//     화면마다 import 를 흩으면 채널별로 스타일이 갈린다(회귀 가드 bootstrapSingleSource).
//   · 부품 = 포털 킷(`components/portal/kit`) + KRDS 킷(`krds-react`)
//   · ★**결과 확인이 목록 아래 구역에서 창(모달)으로 옮겨 왔다** — 구 동작 폐기. 구역이던 시절엔
//     행을 고르면 목록 아래에 펼쳐져, 긴 목록에서는 펼친 내용이 화면 밖에 있었다.
//   · 생성 조건 칩은 전용 부품(`ConditionChips`)이 그린다 — 이름 한 단 흐리게 · 값 진하게.
//   · 조회 실패·0건이 **띠가 아니라 빈 판**으로 선다(EmptyState) — 목록이 올 자리를 그대로 채운다.
//
// ⚠ 관제 공통 부품(`components/common/*`)을 쓰지 않는다 — 관제 화면 여럿이 함께 쓰므로 포털 모양을
//   넣으면 관제 화면이 같이 바뀐다(사용자 확정 구속: **관제향 화면·컴포넌트 불변**).
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

import { useEffect, useState, type MouseEvent, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Badge, Button, Tooltip, type BadgeProps } from 'krds-react';
import {
  ArrowUpRight,
  CircleAlert,
  Download,
  FileBraces,
  Clock,
  RotateCcw,
  Sparkles,
  X,
} from 'lucide-react';

import {
  Alert,
  EmptyState,
  KeyValueList,
  Modal,
  PageNav,
  RecordRow,
  ResultCount,
  StepHeading,
} from '@/components/portal/kit';
import { ConditionChips } from '@/components/portal/authoring';
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
import { useUiStore } from '@/stores/useUiStore';

/** 목록 한 페이지 건수. 서버 기본값과 같은 값을 명시해 보낸다(BE 기본값에 기대지 않는다). */
const PAGE_SIZE = 20;

/** 대상 영상을 고르러 가는 자리 — 요청 진입은 그 화면이 갖는다. */
const CHOOSE_VIDEO_PATH = '/portal/uploads';
const CHOOSE_VIDEO_LABEL = '증강할 영상 고르러 가기';

/** 한 번에 하나만 받는다 — 다른 받기가 도는 동안 잠기는 사유. */
const DOWNLOAD_BUSY_REASON = '내려받는 중입니다. 끝난 뒤에 다시 누를 수 있습니다.';

/**
 * 귀결 → 배지 색.
 *
 * ★판정(`resolvePortalAugmentOutcome`)과 **표기 문구**(`PORTAL_AUGMENT_OUTCOME_BADGE`)는 그대로
 *  쓰고 여기서는 **색만** 정한다 — 그 모듈은 관제 공통 배지의 톤 키를 나르는데, 포털 배지는
 *  KRDS 킷 색 이름을 갖는다. 문구를 여기 복제하면 두 번째 진실원이 된다.
 */
const OUTCOME_COLOR: Record<PortalAugmentOutcome, NonNullable<BadgeProps['color']>> = {
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
    <section className="klid-authoring-pane" aria-labelledby="portal-augment-list">
      <div className="klid-authoring-block">
        <StepHeading
          size="md"
          id="portal-augment-list"
          title="증강 요청 현황"
          /* 대기 구간을 숨기지 않는다 — 비동기 위탁이라 누른 즉시 결과가 나오지 않는다.
             ★부정으로 시작하지 않는다: 구 문구 «요청한 즉시 결과가 나오지 않습니다» 는 무엇이
               잘못된 것처럼 읽혔다. 사실은 같고 말투만 바꾼다(사양 SCREEN-044). */
          desc="증강은 시간이 걸립니다. 결과가 도착하면 목록의 상태가 바뀝니다."
        />

        {/* 불러오는 동안 목록이 올 자리를 그대로 지킨다 — 비워 두면 실패한 것으로 읽힌다.
            빈 목록과 같은 판이라 도는 고리만 다르다(킷이 `role="status"` 로 읽어 준다). */}
        {listQuery.isLoading && <EmptyState busy title="요청 현황을 불러오고 있습니다." />}

        {/* 「조회 실패」와 「실제로 0건」을 반드시 가른다 — 서버 오류를 요청이 사라진 것으로
            오해하면 사용자가 같은 요청을 다시 건다. 판은 빈 목록과 같고 느낌표·제목·다시 시도가 가른다.
            ★싸개가 `role="alert"` 를 갖는다 — 킷 빈 판은 `busy` 일 때만 읽어 주므로, 이 자리에
              그대로 두면 **조회가 실패했다는 사실이 보조기술에 한 마디도 가지 않는다.**
              시안은 생김새만 바꾼 것이고 알림까지 걷으라는 뜻이 아니다. */}
        {listQuery.isError && (
          <div role="alert">
            <EmptyState
              icon={CircleAlert}
              title="요청 현황을 불러올 수 없습니다"
              desc="잠시 후 다시 시도해 주세요. 낸 요청이 사라진 것은 아닙니다."
              action={
                <Button variant="secondary" size="medium" onClick={() => void listQuery.refetch()}>
                  <RotateCcw aria-hidden />
                  다시 시도
                </Button>
              }
            />
          </div>
        )}

        {/* 빈 화면의 걸음은 보조(secondary)다 — 포털의 빈 화면 걸음이 전부 이 급이라
            (내 학습데이터 · 즐겨찾기) 이 자리만 채운 버튼이면 다른 무게로 읽힌다.
            ★조작을 둔다 — 증강을 거는 자리가 **이 배포본 안**(내 업로드)이라 갈 수 있다.
              형제 화면(내 작업)은 갈 곳이 바깥이라 조작을 두지 않는다. 차이는 의도다.
            ★싸개가 `role="status"` 를 갖는다 — 조회가 끝나고 0건이라는 것은 **방금 일어난 일**이라
              알린다(`alert` 가 아니다 — 끼어들 만큼 급한 소식이 아니다). 구 부품이 갖고 있던
              성질이고 킷 빈 판에는 없어 싸개가 대신 든다. */}
        {isEmpty && (
          <div role="status">
            <EmptyState
              icon={Sparkles}
              title="아직 요청한 증강이 없습니다."
              desc="내 업로드에서 준비가 끝난 영상을 골라 증강을 요청할 수 있습니다."
              action={
                /* ★**링크로 남긴다** — 모양만 킷 버튼이고 실체는 이동이다. 킷 `Button` 은 `as` 로
                   어떤 요소로든 설 수 있어(다형 부품) 라우터 `Link` 를 끼우면 시안의 생김새와
                   링크의 성질(가운데 클릭 · 새 탭 · 주소 복사)을 둘 다 갖는다.
                   ⚠ 그런데 킷 버튼은 **무엇으로 서든 `role="button"` 을 박는다** — 그대로 두면
                     보조기술이 「버튼」이라 읽어, 눈에 보이는 것(주소가 있는 링크)과 귀에
                     들리는 것이 갈린다. 그래서 역할을 되돌려 준다(킷이 `role` 을 받는다). */
                <Button
                  as={Link}
                  to={CHOOSE_VIDEO_PATH}
                  role="link"
                  variant="secondary"
                  size="medium"
                >
                  {CHOOSE_VIDEO_LABEL}
                </Button>
              }
            />
          </div>
        )}

        {hasRows && (
          /* 건수는 제목 옆이 아니라 목록 바로 위다 — 내 업로드 목록과 같은 자리.
             「증강할 영상 고르러 가기」 는 건수와 한 줄 오른쪽 끝에 선다. 조작이 아니라 내 업로드로
             건너뛰는 길이라 바로가기 모양이다(내 업로드의 「증강 요청 현황·결과」 와 같은 규칙).
             **목록에 줄이 있을 때만** 선다 — 0건이면 빈 화면 안내 안의 같은 걸음이 대신한다.
             같은 접근 이름의 링크가 한 화면에 둘이면 보조기술 사용자가 어느 쪽인지 가릴 수 없다. */
          <div className="klid-authoring-assets">
            <div className="klid-result-head">
              <ResultCount total={totalElements} />
              {/* ★킷 `MoreLink` 를 쓰지 않고 같은 모양을 라우터 링크로 세운다 — 그 부품은
                  `<a href>` 를 직접 그리는데, 포털 채널 산출물은 **Host 마운트 경로를 라우터
                  basename 으로** 가지므로 앱 경로를 그대로 실은 `href` 는 그 경로를 잃고 엉뚱한
                  주소로 나간다. `Link` 는 basename 을 자동으로 붙이고 화면 안에서 옮긴다.
                  표식은 시안 그대로 **건너뛰기(대각선 화살표)** 다. */}
              <Link to={CHOOSE_VIDEO_PATH} className="klid-more-link">
                {CHOOSE_VIDEO_LABEL}
                <ArrowUpRight aria-hidden />
              </Link>
            </div>

            {/* 요청 하나가 카드 한 장. 최근 요청이 위다 */}
            <ul
              className="klid-authoring-records"
              aria-label="증강 요청 목록"
              data-testid="portal-augment-list"
            >
              {rows.map((row) => {
                const outcome = resolvePortalAugmentOutcome(row);
                const badge = PORTAL_AUGMENT_OUTCOME_BADGE[outcome];
                // 실패 사유는 실패한 행에만 보인다. 빈 값은 「아직 실패하지 않았다」는 뜻이라
                // 판정과 표시가 같은 축을 쓴다(사유가 있어야 실패다).
                const failReason = outcome === 'failed' ? (row.failRsnCn ?? '') : '';
                const targetName = row.orgnlFileNm ?? `영상 ${row.uldSn}`;
                const requestedAt = formatPortalDateTime(row.requestedAt);
                const conditions = formatGenerationCondition(row.generationCondition);
                return (
                  <li key={row.augSn} data-testid={`portal-augment-row-${row.augSn}`}>
                    <RecordRow
                      /* 줄 짜임은 내 업로드 목록과 같다 — 왼쪽은 타이틀 → 생성 조건 → 일시 한 줄,
                         오른쪽은 위에 실패 사유 · 아래 끝에 걸음 */
                      titleSize="large"
                      factsInline
                      factsBelow
                      /* 사용자 파일명 — 텍스트 노드(자동 escape). 자르지 않고 줄바꿈한다. */
                      title={targetName}
                      badge={
                        <Badge
                          variant="light"
                          color={OUTCOME_COLOR[outcome]}
                          className="klid-badge-tint"
                        >
                          {badge.label}
                        </Badge>
                      }
                      facts={[
                        `요청 ${requestedAt}`,
                        ...(row.resultArrivedAt !== null
                          ? [`도착 ${formatPortalDateTime(row.resultArrivedAt)}`]
                          : []),
                      ]}
                      /*
                        ★실패 사유는 **전문을 보인다.** 말줄임으로 자르면 무엇을 고쳐 다시
                          요청해야 하는지가 사라져, 사유를 읽으려고 매번 「결과 확인」을 눌러야
                          했다. 행 카드는 줄이 늘어나도 손해가 아니다.
                        서버 사유 — 텍스트 노드(자동 escape). 경고 표식은 줄 카드가 붙인다.
                      */
                      actionNote={
                        failReason !== '' ? (
                          <span data-testid={`portal-augment-fail-${row.augSn}`}>{failReason}</span>
                        ) : undefined
                      }
                      action={
                        <div className="klid-authoring-row-actions" data-align="end">
                          <Button
                            size="small"
                            variant="secondary"
                            /* 같은 영상에 여러 요청이 공존할 수 있어(중복 요청이 정상 동선이다)
                               접근 이름에 **요청 일시까지** 붙인다 — 파일명만으로는 같은 이름의
                               버튼이 여럿 생겨 보조기술 사용자가 어느 요청인지 가릴 수 없다.
                               ⚠ 구 처리 폐기 — `aria-pressed` 로 눌린 상태를 말하던 자리다.
                                 결과가 목록 아래 구역으로 펼쳐지던 시절의 표기이고, 지금은 창이
                                 뜨므로 「눌린 채로 남는 토글」이 아니다. */
                            aria-label={`${targetName} ${requestedAt} 요청 결과 확인`}
                            onClick={() => setSelectedAugSn(row.augSn)}
                          >
                            결과 확인
                          </Button>
                        </div>
                      }
                    >
                      {/*
                        ★생성 조건을 **칩으로 흩는다.** 다섯 항목을 `시간대: 밤 · 계절: 겨울 · …`
                          한 덩이로 이으면 폭이 모자랄 때 통째로 잘려 어느 값이 사라졌는지조차
                          알 수 없다. 칩은 줄바꿈이 자연스럽고 항목마다 따로 읽힌다.
                        ★**빠짐없이 보인다** — 아는 다섯 뒤에 모르는 항목도 받은 그대로 잇는다
                          (`formatGenerationCondition` 가 그 차례를 정한다). 화면이 거르지 않는다.
                      */}
                      <ConditionChips
                        label="생성 조건"
                        items={conditions}
                        empty={`생성 조건 ${EMPTY_CONDITION_TEXT}`}
                      />
                    </RecordRow>
                  </li>
                );
              })}
            </ul>

            {/* 전체가 한 페이지에 들어오면 페이저를 그리지 않는다. */}
            {totalPages > 1 && (
              <PageNav
                totalPages={totalPages}
                /* 킷 페이저는 1부터 센다 — 우리 창구는 0부터라 경계에서 한 번만 옮긴다. */
                currentPage={page + 1}
                onChange={(oneBased) => setPage(Math.max(0, oneBased - 1))}
              />
            )}
          </div>
        )}
      </div>

      {selectedAugSn !== null && (
        <AugmentResultModal augSn={selectedAugSn} onClose={() => setSelectedAugSn(null)} />
      )}
    </section>
  );
}

/**
 * 고른 요청의 결과 확인 — 대기·실패·도착 셋을 각각 다른 모습으로 보인다.
 *
 * ★ **구역이 아니라 창이다 (2026-09-16).** 종전에는 목록 아래에 카드로 펼쳤는데, 목록이 길면
 *   펼친 내용이 화면 밖에 있어 무엇이 열렸는지 보이지 않았다. 창은 열린 것 하나에 초점을 모은다.
 * ★★ 넓은 창(lg)이다 — 요청 정보의 생성 조건 값이 길어 기본 폭에서는 이름표까지 두 줄로 꺾인다.
 */
function AugmentResultModal({ augSn, onClose }: { augSn: number; onClose: () => void }) {
  const detailQuery = usePortalAugment(augSn);
  const detail = detailQuery.data;

  return (
    <Modal size="lg" open onOpenChange={(open) => !open && onClose()} title="증강 결과 확인">
      {/* 시험 후크만 갖는 싸개라 짜임에서는 없는 셈 친다 — `display: contents` 로 두어야 창
          본문의 세로 리듬(조각 사이 16)이 안쪽 조각들에 그대로 걸린다. */}
      <div data-testid="portal-augment-result" style={{ display: 'contents' }}>
        {detailQuery.isLoading ? (
          <EmptyState busy size="sm" title="요청 정보를 불러오고 있습니다." />
        ) : detailQuery.isError || !detail ? (
          <EmptyState
            size="sm"
            icon={CircleAlert}
            title="요청 정보를 불러올 수 없습니다"
            desc="잠시 후 다시 시도해 주세요."
            action={
              <Button variant="secondary" size="medium" onClick={() => void detailQuery.refetch()}>
                <RotateCcw aria-hidden />
                다시 시도
              </Button>
            }
          />
        ) : (
          <AugmentResultBody detail={detail} />
        )}
      </div>
    </Modal>
  );
}

function AugmentResultBody({ detail }: { detail: PortalAugmentDetail }) {
  const outcome = resolvePortalAugmentOutcome(detail);
  const conditions = formatGenerationCondition(detail.generationCondition);
  const targetName = detail.orgnlFileNm ?? `영상 ${detail.uldSn}`;

  return (
    <>
      {/* 상태 안내 띠는 창 맨 위다 — 요청 정보보다 먼저 읽힌다.
          기다리는 중 — 모래시계다: 실패가 아니라 기다리는 중이라는 것을 성격 글리프가 못 말한다. */}
      {outcome === 'waiting' && (
        <div data-testid="portal-augment-waiting">
          {/* ⚠ 시안은 모래시계를 쓰지만 **우리는 시계다** — 이 저장소는 「대기」 글리프를
                    `Clock` 하나로 통일했고 `Hourglass` 는 그때 걷어낸 쪽이다(회귀 가드 보유).
                    시안이 모래시계를 고른 까닭은 *"실패가 아니라 기다리는 중"* 을 말하기
                    위해서인데 시계도 그 뜻을 그대로 전한다 — 통일을 깨면서까지 바꿀 이유가 없다. */}
                <Alert tone="info" live="none" icon={<Clock />}>
            아직 결과를 기다리는 중입니다. 잠시 뒤 다시 확인해 주세요.
          </Alert>
        </div>
      )}

      {outcome === 'failed' && (
        <div data-testid="portal-augment-failed">
          {/* 서버가 준 사유를 그대로 텍스트로 싣는다 — 사실만 알리고 원인을 감추지 않는다. */}
          <Alert tone="danger" title="증강이 실패했습니다">
            {detail.failRsnCn}
          </Alert>
        </div>
      )}

      {/* 내려받기 줄은 창 위쪽이다 — 안내 글이 왼쪽, 걸음이 오른쪽 끝. */}
      {outcome === 'ready' && detail.resultUldSn !== null && (
        <AugmentResultDownloads
          resultUldSn={detail.resultUldSn}
          augSn={detail.augSn}
          name={targetName}
        />
      )}

      {/* 요청 정보 — 대상 영상 · 생성 조건 · 요청 일시 · 결과 도착 일시.
          창 안에 이미 구획 선이 있어 흰 판을 또 세우지 않는다(옅은 회색 요약 상자). */}
      <KeyValueList
        surface="muted"
        ariaLabel="요청 정보"
        items={[
          { label: '대상 영상', value: targetName },
          {
            label: '생성 조건',
            value:
              conditions.length === 0
                ? EMPTY_CONDITION_TEXT
                : conditions.map((c) => `${c.label}: ${c.value}`).join(' · '),
          },
          { label: '요청 일시', value: formatPortalDateTime(detail.requestedAt) },
          {
            label: '결과 도착 일시',
            /* 도착하지 않은 요청은 자리를 비운다 — `없음` 같은 문구를 지어내면 도착이 정해졌는데
               표기만 빈 것으로 읽힌다(만료 예정일 표기와 같은 관례). */
            value:
              detail.resultArrivedAt === null ? '-' : formatPortalDateTime(detail.resultArrivedAt),
          },
        ]}
      />

      {outcome === 'ready' && detail.resultUldSn !== null && (
        <>
          <AugmentResultPreview resultUldSn={detail.resultUldSn} />
          <AugmentLabelingEntry resultUldSn={detail.resultUldSn} />
        </>
      )}
    </>
  );
}

/**
 * 후속 작업 진입 — 안내 글이 왼쪽, 걸음이 오른쪽 끝.
 *
 * ⚠ **시안은 이 걸음을 창 아랫동(`main`)에 세우고 안내를 그 왼쪽(`note`)에 둔다. 그대로 쓰지
 *   못했다** — 킷 창의 아랫동 걸음은 주소를 받으면 `<a href>` 를 **직접** 그리는데, 포털 채널
 *   산출물은 Host 마운트 경로를 라우터 basename 으로 가지므로 앱 경로를 그대로 실은 `href` 가
 *   그 경로를 잃는다(=깨진 링크). 그래서 **같은 짜임(안내 왼쪽 · 걸음 오른쪽 끝)을 본문 맨
 *   아래에** 세우고 라우터 링크를 쓴다. 생김새와 읽는 차례는 시안과 같다.
 *
 * ★ **링크로 남긴다** — 모양만 킷 버튼이고 실체는 이동이다. 킷 `Button` 은 `as` 로 어떤 요소로든
 *   설 수 있어(다형 부품) 라우터 `Link` 를 끼우면 시안의 생김새와 링크의 성질(가운데 클릭 ·
 *   새 탭 · 주소 복사 · 보조기술의 「링크」 안내)을 **둘 다** 갖는다.
 *
 * ★★ 진입 주소는 계약이 준 **결과물 자산 식별자 하나**로 조립한다. 화면이 자산 식별자를 따로
 *   유추하지 않는다(계약이 *"후속 작업 진입과 내려받기 창구가 받는 자산 식별자와 같은 축"* 이라고
 *   못박았다).
 */
function AugmentLabelingEntry({ resultUldSn }: { resultUldSn: number }) {
  return (
    <div className="klid-augment-action-row">
      {/* 후속 작업 범위 — 이 경로에 없는 것을 기대하지 않도록 미리 알린다. */}
      <p className="form-hint">
        이어지는 작업에서는 바운딩 박스와 폴리곤을 직접 그려 라벨을 답니다.
      </p>
      <div className="klid-augment-action-steps">
        <Button
          as={Link}
          to={buildPortalUploadLabelPath(resultUldSn)}
          /* ⚠ 킷 버튼은 무엇으로 서든 `role="button"` 을 박는다 — 주소가 있는 이동이므로
             역할을 링크로 되돌린다(위 빈 화면 걸음과 같은 처리). */
          role="link"
          size="medium"
          data-testid="portal-augment-labeling-entry"
        >
          라벨링 이어서 하기
        </Button>
      </div>
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
 *
 * 자리는 부품이 갖는다 — 16:9 로 먼저 서므로 그림이 늦게 와도 창이 밀리지 않는다(CLS).
 */
function AugmentResultPreview({ resultUldSn }: { resultUldSn: number }) {
  const assetQuery = useUploadDetail(resultUldSn);
  const firstFrameSn = assetQuery.data?.frames?.[0]?.uldFrmeSn;
  const { url } = useUploadFrameImage(firstFrameSn);

  if (!url) return null;

  return (
    <img
      className="klid-augment-preview"
      src={url}
      alt="증강 결과물 미리보기"
      loading="lazy"
      data-testid="portal-augment-preview"
    />
  );
}

/**
 * 잠긴 걸음 — 버튼 모양은 그대로 두고 누르기만 막는다.
 *
 * ★ **속성으로 잠그지 않는다**(`disabled` 미사용). WCAG 2.1.1 — native `disabled` 는 Tab 순서에서
 *   빠져 **왜 못 누르는지 알 길이 사라진다.** `aria-disabled` 로 초점은 남기고 활성화만 막는다
 *   (실제 차단은 눌림 처리 쪽의 «받는 중이면 아무것도 하지 않는다» 가 이미 하고 있다).
 * ★★ **말풍선만으로 끝내지 않는다.** 킷 말풍선 본문은 `aria-hidden` 이라 보조기술에 닿지 않고,
 *   손가락 입력에서는 뜨지도 않는다. 그래서 말풍선(눈)과 화면 밖 글 + `aria-describedby`(귀)를
 *   **함께** 둔다 — 같은 문구가 문서에 두 번 나오는 것은 의도다. 내 저장 작업 목록과 같은 처리다.
 */
function LockedDownload({
  reason,
  reasonId,
  label,
  busy,
  children,
}: {
  reason: string;
  reasonId: string;
  label: string;
  /** 지금 받는 중인 걸음인가 — 그 사실을 보조기술에도 알린다(영상은 오래 걸릴 수 있다). */
  busy?: boolean;
  children: ReactNode;
}) {
  return (
    <Tooltip text={reason}>
      <span>
        <Button
          size="small"
          variant="secondary"
          className="disabled"
          aria-disabled
          aria-busy={busy || undefined}
          aria-label={label}
          aria-describedby={reasonId}
          onClick={(e: MouseEvent) => e.preventDefault()}
        >
          {children}
        </Button>
        <span id={reasonId} className="sr-only">
          {reason}
        </span>
      </span>
    </Tooltip>
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

  const locked = downloading !== null;
  const exportLabel = '증강 결과물 라벨 내보내기';
  const fileLabel = '증강 결과물 원본 파일 내려받기';
  /* 라벨 JSON 은 업로드 목록과 같은 중괄호 문서 모양을 쓴다 — 같은 조작 = 같은 아이콘.
     ⚠ 시안은 두 걸음에 같은 받기 표식을 썼다(그쪽 목 데이터에는 형식 차이가 없다). 형식을
       가리는 것은 **우리 쪽에만 있는 사실**이라 유지한다. */
  const exportFace = (
    <>
      <FileBraces aria-hidden />
      라벨 내보내기
    </>
  );
  const fileFace = (
    <>
      <Download aria-hidden />
      원본 파일 내려받기
    </>
  );

  return (
    <div className="klid-augment-action-row">
      <p className="form-hint">내가 낸 요청의 결과물만 내려받을 수 있습니다.</p>
      <div className="klid-augment-action-steps">
        {locked ? (
          <LockedDownload
            reason={DOWNLOAD_BUSY_REASON}
            reasonId={`portal-augment-export-locked-${augSn}`}
            label={exportLabel}
            busy={downloading === 'export'}
          >
            {exportFace}
          </LockedDownload>
        ) : (
          <Button size="small" variant="secondary" aria-label={exportLabel} onClick={exportLabels}>
            {exportFace}
          </Button>
        )}

        {locked ? (
          <LockedDownload
            reason={DOWNLOAD_BUSY_REASON}
            reasonId={`portal-augment-file-locked-${augSn}`}
            label={fileLabel}
            /* 진행 사실은 보조기술에도 전달한다 — 영상은 오래 걸릴 수 있어 «눌렸는데 아무 일도
               없다» 로 보이면 안 된다. */
            busy={downloading === 'file'}
          >
            {fileFace}
          </LockedDownload>
        ) : (
          <Button size="small" variant="secondary" aria-label={fileLabel} onClick={downloadFile}>
            {fileFace}
          </Button>
        )}

        {downloading === 'file' && (
          /* 취소는 눌러야 동작하므로 상호 잠금 대상에서 빠진다.
             ⚠ 보이는 글은 「취소」 두 글자다 — 옆 안내 글이 두 줄로 꺾이지 않게(시안).
               접근 이름은 무엇을 취소하는지까지 말한다. */
          <Button
            size="small"
            variant="secondary"
            className="klid-btn-danger-line"
            aria-label={`${fileLabel} 취소`}
            onClick={() => abortController?.abort()}
          >
            <X aria-hidden />
            취소
          </Button>
        )}
      </div>
    </div>
  );
}
