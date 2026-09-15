// 포털 증강 화면 — 본인이 올린 영상에 낸 증강 요청의 **현황 · 결과 확인 · 후속 작업 진입 ·
// 결과물 내려받기**. 요청을 거는 자리는 여기가 아니다. [@design SCREEN-044]
//
// ★ **요청 진입을 여기 두지 않는다.** 증강을 거는 자리는 포털 업로드 화면(SCREEN-033)의 자산별
//   액션이고, 이 화면에는 **대상 영상을 고르러 가는 길만** 둔다 — 같은 행위의 진입이 둘이 되면
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
// ★ **머리 영역·좌측 주 메뉴·자기 제목 밴드를 두지 않는다** — Host 가 머리 영역과 좌측 메뉴를,
//   본문 상단 이동 탭(레이아웃이 그린다)의 활성 항목이 화면 이름을 이미 말한다.
//
// ★★ **표가 아니라 줄 카드다.** 한 행이 요구하는 최소 폭(일시 · 파일명 · 생성 조건 · 상태 · 조작)이
//   본문 최대 폭을 구조적으로 넘어, 표로 두면 어느 칸이든 반드시 접히거나 잘렸다. 줄 카드는
//   줄바꿈이 손해가 아닌 구조라 부족분 자체가 사라진다. 생성 조건은 칩으로 흩어 항목마다 따로
//   읽히고, 실패 사유는 전문이 그대로 보인다.
//   ⚠ 구 처리 폐기 — *줄임표로 자르고 전문은 말풍선에 담는다*. **되살리지 말 것.**
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 저장소의 부품(`@portal/components/custom` · KRDS 킷)과 저작도구 조각(`ConditionChips`)으로
// 짓는다. 짜임·문구는 포털 화면 스토리북 「워크스페이스 / 저작도구 / 증강」(KLID_Portal
// `AuthoringAugmentView`)과 같게 둔다. 결과 확인은 목록 아래 구역이 아니라 **창(모달)** 으로 뜬다.
// 데이터 · 폴링 · 받기 · 취소 흐름은 이 화면의 것 그대로다.
//
// 보안: 사용자 파일명·서버 실패 사유는 JSX 텍스트 노드로만 렌더한다(자동 escape). 남의 요청과
// 없는 요청은 서버가 한 코드로 묶어 거부하므로 화면이 둘을 가르려 하지 않는다.

import { useEffect, useState } from 'react';
import { Badge, Button } from 'krds-react';
import { CircleAlert, Download, Hourglass, RotateCcw, Sparkles, X } from 'lucide-react';
import { useNavigate } from 'react-router-dom';

import {
  Alert,
  EmptyState,
  KeyValueList,
  Modal,
  MoreLink,
  PageNav,
  RecordRow,
  ResultCount,
  StepHeading,
  Toaster,
  useToasts,
} from '@portal/components/custom';
import { ConditionChips } from '@portal/pages/workspace/authoring/ConditionChips';
import '@portal/pages/workspace/authoring/AuthoringAugmentView.css';
import { buildPortalUploadLabelPath } from '@/features/portal/labelingEntry';
import {
  formatGenerationCondition,
  summarizeGenerationCondition,
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

/** 목록 한 페이지 건수. 서버 기본값과 같은 값을 명시해 보낸다(BE 기본값에 기대지 않는다). */
const PAGE_SIZE = 20;

/** 대상 영상을 고르러 가는 자리 — 요청 진입은 그 화면이 갖는다. */
const CHOOSE_VIDEO_PATH = '/portal/uploads';
const CHOOSE_VIDEO_LABEL = '증강할 영상 고르러 가기';

/**
 * 귀결 → 배지 색.
 *
 * ★판정(`resolvePortalAugmentOutcome`)과 **표기 문구**(`PORTAL_AUGMENT_OUTCOME_BADGE`)는 그대로
 *  쓰고 여기서는 **색만** 정한다. 문구를 여기 복제하면 두 번째 진실원이 된다.
 */
const OUTCOME_BADGE_COLOR = {
  waiting: 'gray',
  ready: 'success',
  failed: 'danger',
} as const satisfies Record<PortalAugmentOutcome, string>;

type PushToast = ReturnType<typeof useToasts>['push'];

export function PortalAugmentPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  // 결과 확인 창을 띄운 요청. 페이지를 옮기면 그 행이 더는 화면에 없으므로 함께 푼다(아래 effect).
  const [selectedAugSn, setSelectedAugSn] = useState<number | null>(null);
  // 받기 실패 알림 — 창을 닫아도 남아야 하므로 화면이 쥔다.
  const toasts = useToasts();

  const listQuery = usePortalAugments({ page, size: PAGE_SIZE });
  const rows = listQuery.data?.content ?? [];
  const totalElements = listQuery.data?.totalElements ?? 0;
  const totalPages = listQuery.data?.totalPages ?? 0;

  useEffect(() => {
    setSelectedAugSn(null);
  }, [page]);

  const listReady = !listQuery.isLoading && !listQuery.isError;
  const isEmpty = listReady && rows.length === 0;
  const hasRows = listReady && rows.length > 0;

  return (
    <section className="klid-authoring-pane" aria-labelledby="portal-augment-list">
      <div className="klid-authoring-block">
        {/* 대기 구간을 숨기지 않는다 — 비동기 위탁이라 누른 즉시 결과가 나오지 않는다.
            ★부정으로 시작하지 않는다: 구 문구 «요청한 즉시 결과가 나오지 않습니다» 는 무엇이
              잘못된 것처럼 읽혔다(사양 SCREEN-044). */}
        <StepHeading
          size="md"
          id="portal-augment-list"
          title="증강 요청 현황"
          desc="증강은 시간이 걸립니다. 결과가 도착하면 목록의 상태가 바뀝니다."
        />

        {listQuery.isLoading && <EmptyState busy title="요청 현황을 불러오고 있습니다." />}

        {/* 「조회 실패」와 「실제로 0건」을 반드시 가른다 — 서버 오류를 요청이 사라진 것으로
            오해하면 사용자가 같은 요청을 다시 건다. 판은 빈 목록과 같지만 느낌표 · 제목 · 다시 시도가 가른다 */}
        {listQuery.isError && (
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
        )}

        {/* 빈 화면의 걸음은 보조(secondary)다 — 포털의 빈 화면 걸음이 전부 이 급이다 */}
        {isEmpty && (
          <EmptyState
            icon={Sparkles}
            title="아직 요청한 증강이 없습니다."
            desc="내 업로드에서 준비가 끝난 영상을 골라 증강을 요청할 수 있습니다."
            action={
              <Button variant="secondary" size="medium" onClick={() => navigate(CHOOSE_VIDEO_PATH)}>
                {CHOOSE_VIDEO_LABEL}
              </Button>
            }
          />
        )}

        {hasRows && (
          /* 건수는 목록 바로 위다 — 내 업로드 목록과 같은 자리. 「증강할 영상 고르러 가기」 는 건수와
             한 줄 오른쪽 끝에 선다. **목록에 줄이 있을 때만** 선다 — 0건이면 빈 화면 안내 안의
             같은 걸음이 대신한다(같은 이름의 걸음이 한 화면에 둘이면 보조기술 사용자가 가릴 수 없다) */
          <div className="klid-authoring-assets">
            <div className="klid-result-head">
              <ResultCount total={totalElements} />
              <MoreLink label={CHOOSE_VIDEO_LABEL} jump onClick={() => navigate(CHOOSE_VIDEO_PATH)} />
            </div>

            {/* 요청 하나가 카드 한 장. 최근 요청이 위다(서버가 그 차례로 준다) */}
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
                /* ★생성 조건을 **칩으로 흩는다** — 한 덩이로 이으면 폭이 모자랄 때 통째로 잘려 어느
                     값이 사라졌는지조차 알 수 없다. ★**빠짐없이 보인다** — 아는 다섯 뒤에 모르는
                     항목도 받은 그대로 잇는다(`formatGenerationCondition` 가 차례를 정한다) */
                const conditions = formatGenerationCondition(row.generationCondition);
                return (
                  <li key={row.augSn} data-testid={`portal-augment-row-${row.augSn}`}>
                    <RecordRow
                      /* 사용자 파일명 — 텍스트 노드(자동 escape). 자르지 않고 줄바꿈한다 */
                      title={targetName}
                      /* 왼쪽은 타이틀 → 생성 조건 → 일시 한 줄, 오른쪽은 위에 실패 사유 · 아래 끝에 걸음 */
                      titleSize="large"
                      factsInline
                      factsBelow
                      badge={
                        <Badge
                          variant="light"
                          color={OUTCOME_BADGE_COLOR[outcome]}
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
                      /* ★실패 사유는 **전문을 보인다** — 무엇을 고쳐 다시 요청할지 판단하는 근거다.
                         서버 사유 — 텍스트 노드(자동 escape) */
                      actionNote={
                        failReason !== '' && (
                          <span data-testid={`portal-augment-fail-${row.augSn}`}>{failReason}</span>
                        )
                      }
                      action={
                        <div className="klid-authoring-row-actions" data-align="end">
                          <Button
                            size="small"
                            variant="secondary"
                            onClick={() => setSelectedAugSn(row.augSn)}
                            /* 같은 영상에 여러 요청이 공존할 수 있어(중복 요청이 정상 동선이다)
                               접근 이름에 **요청 일시까지** 붙인다 */
                            aria-label={`${targetName} ${requestedAt} 요청 결과 확인`}
                          >
                            결과 확인
                          </Button>
                        </div>
                      }
                    >
                      <ConditionChips label="생성 조건" items={conditions} empty="생성 조건 -" />
                    </RecordRow>
                  </li>
                );
              })}
            </ul>
            {/* 전체가 한 쪽에 들어오면 쪽 넘김을 그리지 않는다. 화면의 쪽은 0부터, 쪽 넘김 줄은 1부터 센다 */}
            {totalPages > 1 && (
              <PageNav
                totalPages={totalPages}
                currentPage={page + 1}
                onChange={(next: number) => setPage(next - 1)}
              />
            )}
          </div>
        )}
      </div>

      {selectedAugSn !== null && (
        <AugmentResultModal
          augSn={selectedAugSn}
          onClose={() => setSelectedAugSn(null)}
          onLabel={(resultUldSn) => navigate(buildPortalUploadLabelPath(resultUldSn))}
          pushToast={toasts.push}
        />
      )}

      <Toaster items={toasts.items} onDismiss={toasts.dismiss} />
    </section>
  );
}

/**
 * 증강 결과 확인 — 목록 줄의 「결과 확인」이 여는 창. 대기·실패·도착 셋을 각각 다른 모습으로 보인다.
 *
 * 「라벨링 이어서 하기」 는 아랫동 메인 걸음이고 그 안내는 아랫동 왼쪽 안내문이다. 결과가 도착해
 * 불러온 뒤에만 선다 — 그 밖의 상태는 아랫동 없이 X 로 닫는다.
 * 넓은 창(lg)이다 — 요청 정보의 생성 조건 값이 길어 기본 폭에서는 이름표까지 두 줄로 꺾인다.
 */
function AugmentResultModal({
  augSn,
  onClose,
  onLabel,
  pushToast,
}: {
  augSn: number;
  onClose: () => void;
  onLabel: (resultUldSn: number) => void;
  pushToast: PushToast;
}) {
  const detailQuery = usePortalAugment(augSn);
  const detail = detailQuery.data;
  const failedToLoad = !detailQuery.isLoading && (detailQuery.isError || !detail);
  // 후속 작업 진입은 결과물 자산 식별자 하나로 이어진다 — 화면이 식별자를 따로 유추하지 않는다.
  const resultUldSn =
    detail && resolvePortalAugmentOutcome(detail) === 'ready' ? detail.resultUldSn : null;

  return (
    <Modal
      size="lg"
      open
      onOpenChange={(open) => !open && onClose()}
      title="증강 결과 확인"
      /* 후속 작업 범위 — 이 경로에 없는 것을 기대하지 않도록 미리 알린다 */
      note="이어지는 작업에서는 바운딩 박스와 폴리곤을 직접 그려 라벨을 답니다."
      main={
        resultUldSn !== null
          ? { label: '라벨링 이어서 하기', onClick: () => onLabel(resultUldSn) }
          : undefined
      }
    >
      {detailQuery.isLoading && <EmptyState busy size="sm" title="요청 정보를 불러오고 있습니다." />}

      {failedToLoad && (
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
      )}

      {!detailQuery.isLoading && detail && !detailQuery.isError && (
        <AugmentResultBody detail={detail} pushToast={pushToast} />
      )}
    </Modal>
  );
}

function AugmentResultBody({
  detail,
  pushToast,
}: {
  detail: PortalAugmentDetail;
  pushToast: PushToast;
}) {
  const outcome = resolvePortalAugmentOutcome(detail);
  const targetName = detail.orgnlFileNm ?? `영상 ${detail.uldSn}`;
  const resultUldSn = outcome === 'ready' ? detail.resultUldSn : null;

  return (
    <>
      {/* 상태 안내 띠는 창 맨 위다 — 요청 정보보다 먼저 읽힌다.
          기다리는 중 — 모래시계다: 실패가 아니라 기다리는 중이라는 것을 성격 글리프가 못 말한다 */}
      {outcome === 'waiting' && (
        <Alert tone="info" live="none" icon={<Hourglass />}>
          아직 결과를 기다리는 중입니다. 잠시 뒤 다시 확인해 주세요.
        </Alert>
      )}

      {/* 서버가 준 사유를 그대로 텍스트로 싣는다 — 사실만 알리고 원인을 감추지 않는다 */}
      {outcome === 'failed' && (
        <Alert tone="danger" live="none" title="증강이 실패했습니다">
          {detail.failRsnCn}
        </Alert>
      )}

      {resultUldSn !== null && (
        <AugmentResultDownloads
          resultUldSn={resultUldSn}
          augSn={detail.augSn}
          name={targetName}
          pushToast={pushToast}
        />
      )}

      <KeyValueList
        surface="muted"
        ariaLabel="요청 정보"
        items={[
          { label: '대상 영상', value: targetName },
          { label: '생성 조건', value: summarizeGenerationCondition(detail.generationCondition) },
          { label: '요청 일시', value: formatPortalDateTime(detail.requestedAt) },
          {
            label: '결과 도착 일시',
            /* 도착하지 않은 요청은 자리를 비운다 — `없음` 같은 문구를 지어내면 도착이 정해졌는데
               표기만 빈 것으로 읽힌다(만료 예정일 표기와 같은 관례) */
            value:
              detail.resultArrivedAt === null ? '-' : formatPortalDateTime(detail.resultArrivedAt),
          },
        ]}
      />

      {resultUldSn !== null && <AugmentResultPreview resultUldSn={resultUldSn} />}
    </>
  );
}

/**
 * 결과물 미리보기 — 결과물의 첫 장면 한 장.
 *
 * ⚠ **미리보기 이미지를 조달할 전용 창구가 계약에 없다.** 단건 조회는 결과물의 *위치*(자산
 *   식별자)만 알려 주고 파일을 내보내지 않는다. 그래서 그 자산이 **포털 업로드 자산과 같은 축**
 *   이라는 계약 문장에 기대어, 이미 있는 포털 업로드 자산 조회·프레임 이미지 경로를 그대로 쓴다.
 *
 * ⚠⚠ **실패해도 조용히 비운다** — 못 불러오면 자리째 보이지 않는다. 미리보기는 보조 정보이고,
 *   그것 때문에 후속 작업·내려받기가 못 쓰는 것처럼 보이면 안 된다. 그림이 늦게 와도 자리가
 *   먼저 서도록 판 비율은 포털 CSS(`.klid-augment-preview`)가 잡는다.
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
      alt="증강 결과물 첫 장면"
      loading="lazy"
      data-testid="portal-augment-preview"
    />
  );
}

/**
 * 결과물 내려받기 줄 — 창 맨 위. 안내 글이 왼쪽, 걸음(라벨 내보내기 · 원본 파일)이 오른쪽 끝.
 *
 * ★ 결과물 자산 식별자를 그대로 포털 업로드 자산 내려받기 창구에 넘긴다(API-157 · API-159).
 *   같은 조작을 위해 새 창구를 만들지 않는다.
 *
 * ★★ **취소는 원본 파일에만 둔다.** 원본은 매우 커질 수 있어 한 번 시작하면 오래 붙잡히지만,
 *   라벨 내보내기는 작아서 취소 버튼이 뜨기도 전에 끝난다(포털 업로드 목록이 세운 관례).
 *   사용자가 누른 취소는 오류가 아니므로 실패 알림을 띄우지 않는다 — 판정 근거는 오류 객체가
 *   아니라 **화면이 스스로 중단을 걸었는지**다(공용 클라이언트가 취소 표식을 남기지 않는다).
 */
function AugmentResultDownloads({
  resultUldSn,
  augSn,
  name,
  pushToast,
}: {
  resultUldSn: number;
  augSn: number;
  name: string;
  pushToast: PushToast;
}) {
  const [downloading, setDownloading] = useState<'export' | 'file' | null>(null);
  const [abortController, setAbortController] = useState<AbortController | null>(null);

  const exportLabels = () => {
    if (downloading) return;
    setDownloading('export');
    downloadUploadExport(resultUldSn)
      .catch(() => pushToast({ tone: 'danger', message: '내보내기에 실패했습니다.' }))
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
        pushToast({ tone: 'danger', message: '원본 다운로드에 실패했습니다.' });
      })
      .finally(() => {
        setAbortController((cur) => (cur === controller ? null : cur));
        setDownloading(null);
      });
  };

  return (
    <div className="klid-augment-action-row">
      <p className="form-hint">내가 낸 요청의 결과물만 내려받을 수 있습니다.</p>
      {/* 받는 동안에는 두 받기가 함께 잠긴다. 원본 파일은 오래 걸릴 수 있어 취소 걸음이 선다 */}
      <div className="klid-augment-action-steps">
        <Button
          size="small"
          variant="secondary"
          disabled={downloading !== null}
          onClick={exportLabels}
          aria-label="증강 결과물 라벨 내보내기"
        >
          {/* 두 버튼 모두 '내려받기'라 같은 아이콘을 쓴다 — 구분은 라벨이 한다 */}
          <Download aria-hidden />
          라벨 내보내기
        </Button>
        <Button
          size="small"
          variant="secondary"
          disabled={downloading !== null}
          onClick={downloadFile}
          aria-label="증강 결과물 원본 파일 내려받기"
          /* 진행 사실은 보조기술에도 전달한다 — 영상은 오래 걸릴 수 있어 «눌렸는데 아무 일도
             없다» 로 보이면 안 된다 */
          aria-busy={downloading === 'file' || undefined}
        >
          <Download aria-hidden />
          원본 파일 내려받기
        </Button>
        {downloading === 'file' && (
          <Button
            size="small"
            variant="secondary"
            className="klid-btn-danger-line"
            onClick={() => abortController?.abort()}
            aria-label="증강 결과물 원본 파일 내려받기 취소"
          >
            <X aria-hidden />
            {/* 「취소」 두 글자다 — 옆 안내 글이 두 줄로 꺾이지 않게 */}
            취소
          </Button>
        )}
      </div>
    </div>
  );
}
