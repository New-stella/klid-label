// 포털 채널 진입 화면 — **내 저장 작업 목록**. [@design SCREEN-028] [@design API-225] [@design API-203]
//
// ★★ **이 화면은 데이터마트 카탈로그가 아니다.** 데이터마트 영상 전체를 훑어보고 고르는 목록은
//   포털(Host)이 자기 화면에서 제공한다. 저작도구가 그리는 것은 「내가 저장한 작업」이며, 거기에
//   본인이 올린 업로드 자산이 함께 실린다.
//   ⚠ 구 동작 폐기 — 이 화면은 승인 영상을 **거르지 않고 전부** 그리고 있었다(`useDatamartVideos`).
//     그래서 ①같은 목록이 Host 와 여기 두 번 보이고 ②작업하지 않은 영상에도 내려받기가 있었고
//     ③진입이 언제나 첫 프레임이라 **이어쓰기가 되지 않았다**. 되살리지 말 것.
//
// ★ **모집단은 두 축이고 싣는 기준이 다르다**(서버가 판정한다 — 화면은 거르지 않는다):
//   업로드 자산은 **저작 여부와 무관하게 전부**, 데이터마트 영상은 **본인 저작물이 있는 것만**.
//   그래서 `labelCount` 가 0 인 행이 정상으로 존재한다(라벨 없이 메타·이벤트 어노테이션만 고친 행,
//   아직 아무것도 저장하지 않은 업로드 행). **그 행을 빼거나 빈 상태로 취급하지 않는다** — 빼면
//   보존기간 삭제 대상인 작업물을 사용자가 볼 수조차 없다.
//
// ★ **머리 영역·좌측 주 메뉴를 그리지 않는다** — Host 가 둘 다 소유한다(SHELL-002). 목적지 이동은
//   본문 상단 탭(`PortalContentTabs`, 레이아웃이 그린다)이 맡으므로 이 화면은 자기 제목 밴드도 두지
//   않는다(서비스 제목은 Host 머리 영역이, 화면 이름은 활성 탭이 이미 말한다).
//
// <h3>모양 — 부모 포털의 저작도구 화면을 그대로 입혔다 (2026-09-16)</h3>
// 부모 포털(KLID_Portal)이 이 화면을 **자기 부품으로 다시 그려** 「저작도구 쪽에 넘기는 기준」으로
// 삼았고(`pages/workspace/authoring/AuthoringWorkView.tsx`), 그 짜임을 여기에 옮겼다.
//   · 판·블록·목록 짜임 = `klid-authoring-*` (components/portal/authoring/authoring.css)
//   · 부품 = 포털 킷(`components/portal/kit`) + KRDS 킷(`krds-react`)
//   · 보존기간 안내가 **목록 바로 위 회색 띠**로 올라왔다 — 표를 읽기 전에 먼저 보이게
//     (구 동작: 페이지 맨 아래 회색 글 한 줄)
//   · 내려받기가 **제 칸으로 떨어져 나왔다** — 「이어서 작업」 옆에 붙어 있으면 머리글로 갈리지 않았다
//   · 못 누르는 사유는 **말풍선**으로 옮겼다. 다만 말풍선만 두면 보조기술이 못 읽으므로
//     `aria-describedby` + 화면 밖 글을 **함께** 남긴다(아래 `LockedAction` 주석)
//
// ⚠ 관제 공통 부품(`components/common/*`)을 쓰지 않는다 — 관제 화면 여럿이 함께 쓰므로 포털 모양을
//   넣으면 관제 화면이 같이 바뀐다(사용자 확정 구속: **관제향 화면·컴포넌트 불변**).
//
// 보안: 사용자·서버가 준 이름은 JSX 텍스트 노드로만 렌더한다(자동 escape). 본인 데이터 격리는
//   서버가 토큰 주체로 강제한다(CWE-639).

import { useRef, useState } from 'react';
import { Badge, Button, Table, Tooltip } from 'krds-react';
import { Download, FileJson, Inbox, X } from 'lucide-react';
import { Link, useSearchParams } from 'react-router-dom';

import { Alert, EmptyState, PageNav, ResultCount, StepHeading } from '@/components/portal/kit';
import {
  downloadDatamartVideoData,
  type PortalUserWork,
  type PortalWorkAssetSource,
} from '@/features/portal/api';
import { datamartDownloadErrorMessage } from '@/features/portal/downloadError';
import { formatExpiryDate } from '@/features/portal/expiry';
import { formatPortalDateTime } from '@/features/portal/formatDateTime';
import { useUserWorks } from '@/features/portal/hooks/useUserWorks';
import { buildPortalWorkLabelPath } from '@/features/portal/labelingEntry';
import { downloadUploadExport } from '@/features/portal/uploads/api';

import '@/components/portal/authoring/authoring.css';

const PAGE_SIZE = 20;

/** 자산 출처 표기 — 두 축이 한 목록에 섞이므로 행마다 어느 축인지 읽혀야 한다(사양 SCREEN-028). */
const SOURCE_LABEL: Record<PortalWorkAssetSource, string> = {
  DATAMART: '데이터마트',
  PORTAL_UPLOAD: '내 업로드',
};

/**
 * 이어서 작업할 수 없는 행의 안내 문구.
 *
 * ★ **그 행을 목록에서 빼지 않는다** — 빼면 삭제 대상인 작업물이 화면에서 사라져, 이 화면이
 *   고치려는 바로 그 실패를 되살린다. 대신 버튼만 누를 수 없게 하고 **누를 수 없다는 사실**을 보여 준다.
 *
 * ★★ **원인을 단정하지 않는다 — 사유가 둘인데 창구는 값 하나로만 말한다.**
 *   진입 자리(`entrySrcSn`)가 비는 사유는 ①열 프레임이 없다(업로드인데 마킹·추출 전)
 *   ②진입이 허용되지 않는다(데이터마트 영상이 노출 조건을 잃었다) 둘이고, **응답은 그 둘을 구분해
 *   주지 않는다**. 그래서 **두 사유 모두에 참인 표현**만 쓴다.
 *   ⚠ 구 문구 폐기 — *"열 수 있는 프레임이 없어 …"*. ②에서는 **프레임이 멀쩡히 있는데** 없다고
 *     말하게 되어, 사용자가 프레임이 사라진 줄 알고 엉뚱한 회복 경로로 간다.
 */
const NO_ENTRY_REASON = '지금은 이어서 작업할 수 없습니다.';

/** 내려받을 저작물이 없는 행의 사유 문구. */
const NO_DOWNLOAD_REASON = '아직 저장한 작업이 없어 내려받을 것이 없습니다.';

/** 다른 행을 받는 중이라 잠긴 사유 문구 — 한 번에 하나만 받는다. */
const DOWNLOAD_BUSY_REASON = '다른 작업을 내려받는 중입니다.';

/**
 * 내려받기 버튼 문구 — **받는 형식을 밝힌다**[SCREEN-028].
 * 같은 자리의 버튼이 출처마다 다른 파일을 내려주므로(데이터마트 = 프레임 이미지·문서·비식별 영상 ZIP,
 * 업로드 = 라벨 JSON 한 파일) 문구가 같으면 누르기 전에는 무엇을 받는지 알 수 없다.
 *
 * ⚠ 부모 포털 시안은 이 자리를 「내려받기」 한 마디로 그렸다(그쪽 목 데이터에는 형식 차이가 없다).
 *   형식을 밝히는 것은 **우리 쪽에만 있는 사실**이라 유지한다 — 열 머리는 시안대로 「내려받기」다.
 */
const DOWNLOAD_LABEL: Record<PortalWorkAssetSource, string> = {
  DATAMART: 'ZIP 내려받기',
  PORTAL_UPLOAD: 'JSON 내려받기',
};

/**
 * 주소의 page 값(0부터)을 읽는다. 사용자가 주소를 직접 고칠 수 있으므로 음수·소수·비수치는
 * 첫 페이지로 가둔다 — 그대로 서버에 실어보내면 조회가 400 으로 떨어져 목록이 통째로 빈다.
 */
function parsePageParam(raw: string | null): number {
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0) return 0;
  return Math.floor(n);
}

/**
 * 이 행에 **본인 저작물이 있는가** — 내려받기 가부의 단일 판정.
 *
 * ★★ **`labelCount` 로 판정하지 않는다.** 라벨을 하나도 만들지 않고 메타나 이벤트 어노테이션만
 *   고쳐도 그것은 저작물이고 묶음에 담겨 나간다. `labelCount` 로 가르면 **그런 행의 내려받기가
 *   통째로 막힌다**(그 행은 목록에 정상적으로 실리는데도). 저장 이력을 나르는 필드는
 *   `lastSavedAt` 이며, 서버가 「아직 아무 저작물도 없다」를 그 값의 `null` 로 표현한다.
 */
function hasAuthoredWork(work: PortalUserWork): boolean {
  return work.lastSavedAt !== null;
}

/**
 * 잠긴 걸음 — 버튼 모양은 그대로 두고 누르기만 막는다. 부모 포털의 「잠긴 걸음」 처리다.
 *
 * ★ **속성으로 잠그지 않는다**(`disabled` 미사용). WCAG 2.1.1 — native `disabled` 는 Tab 순서에서
 *   빠져 **왜 못 누르는지 알 길이 사라진다.** `aria-disabled` 로 초점은 남기고 활성화만 막는다.
 * ★★ **말풍선만으로 끝내지 않는다.** 부모 포털 시안은 사유를 `Tooltip` 하나로 옮겼는데, 말풍선은
 *   손을 올려야 뜨므로 **보조기술과 손가락 입력에는 사유가 전달되지 않는다.** 그래서 말풍선(눈)과
 *   화면 밖 글 + `aria-describedby`(보조기술)를 **함께** 둔다 — 시안의 생김새는 그대로이고
 *   접근성만 되살린다.
 */
function LockedAction({
  reason,
  reasonId,
  testId,
  variant,
  children,
}: {
  reason: string;
  reasonId: string;
  testId: string;
  variant?: 'secondary';
  children: string;
}) {
  return (
    <Tooltip text={reason}>
      <span>
        {/* 킷 버튼의 잠김 모양(disabled 클래스)만 입힌다 — 속성으로 잠그면 사유가 안 뜬다.
            시험 후크는 **실제 조작 요소인 이 버튼**에 단다 — 바깥 싸개에 달면 잠김 여부를
            그 후크로 확인할 수 없다. */}
        <Button
          size="small"
          variant={variant}
          className="disabled"
          aria-disabled
          aria-describedby={reasonId}
          data-testid={testId}
          onClick={(e) => e.preventDefault()}
        >
          {children}
        </Button>
        {/* ★말풍선만으로는 보조기술에 닿지 않는다 — 킷 말풍선 본문은 `aria-hidden` 이다(실측).
            그래서 같은 문구를 화면 밖 글로 한 벌 더 두고 버튼이 그것을 가리킨다.
            ⚠ 그 결과 같은 문구가 문서에 **두 번** 나온다 — 의도이며, 하나는 눈 하나는 귀다. */}
        <span id={reasonId} className="sr-only">
          {reason}
        </span>
      </span>
    </Tooltip>
  );
}

export function PortalHomePage() {
  /*
   * 페이지는 주소에 둔다 — 뒤로가기·북마크가 동작해야 하고, 내부 목록 화면(공지 등)이 이미 같은
   * 방식이다. 기본값(첫 페이지)일 때는 키를 넣지 않는다(공지 목록과 같은 관례).
   */
  const [searchParams, setSearchParams] = useSearchParams();
  const page = parsePageParam(searchParams.get('page'));

  const { data, isLoading } = useUserWorks({ page, size: PAGE_SIZE });

  const works: PortalUserWork[] = data?.content ?? [];
  const totalElements = data?.totalElements ?? 0;
  const totalPages = data?.totalPages ?? 0;

  /** 킷 페이저는 1부터 센다 — 우리 창구는 0부터라 경계에서 한 번만 옮긴다. */
  const handlePageChange = (oneBased: number) => {
    const next = Math.max(0, oneBased - 1);
    const sp = new URLSearchParams(searchParams);
    if (next > 0) sp.set('page', String(next));
    else sp.delete('page');
    setSearchParams(sp, { replace: false });
  };

  /*
   * 작업 데이터 내려받기 — 인증이 필요한 응답이라 직링크가 불가하다(apiClient 로 받아 브라우저
   * 다운로드를 트리거한다).
   *
   * ★ **창구가 출처마다 다르다** — 데이터마트 축은 작업 데이터 묶음 창구, 업로드 축은 그 자산의
   *   내보내기 창구다. 담기는 것도 다르다(본인 업로드 자산에는 비식별 영상이 없다). 그것은 정상이며
   *   화면이 두 축을 한 창구로 합치려 하지 않는다.
   *
   * ★ **취소는 두 축 모두에서 동작해야 한다.** 행에 따라 취소가 되기도 안 되기도 하면 사용자가
   *   예측할 수 없다 — 그래서 업로드 내보내기 창구에도 중단 신호를 싣는다.
   *
   * ★★ **사용자 취소는 오류가 아니라 정상 종료다 — 안내를 띄우지 않는다.** 중단하면 응답이 오지
   *   않아 `ApiError(status 0)` 으로 올라오는데, 그 자리는 «전송이 끊겼습니다 … 연결이 안정적인
   *   환경에서 다시» 를 안내하는 분기다. 갈라 놓지 않으면 스스로 멈춘 사용자에게 회선을 탓하는 거짓
   *   안내가 뜨고 «다시» 라는 권유까지 붙는다.
   *   ⚠ 판정 근거로 오류 객체를 쓰지 않는다 — 공용 클라이언트가 취소 표식을 남기지 않아 오류만
   *     봐서는 취소와 회선 단절이 **구분되지 않는다**. 반면 화면은 자기가 중단을 걸었는지 알고
   *     있으므로 그 사실(`controller.signal.aborted`)로 판정한다.
   */
  const [downloadingRawSn, setDownloadingRawSn] = useState<number | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  // 진행 중인 요청의 중단 컨트롤러. 취소 버튼이 이것을 통해 전송을 끊는다.
  const downloadAbortRef = useRef<AbortController | null>(null);

  const onDownload = (work: PortalUserWork) => {
    if (downloadingRawSn !== null) return;
    const controller = new AbortController();
    downloadAbortRef.current = controller;
    setDownloadingRawSn(work.rawSn);
    setDownloadError(null);
    /*
     * 업로드 축에서 `rawSn` 이 곧 자산 식별자다(공용 원장에 앉아 있다 — ADR-058).
     * ref 가 아니라 지역 변수를 닫아 쓴다 — 다음 다운로드가 ref 를 덮어써도 이 catch 는 자기
     * 요청의 중단 여부를 본다(ref 를 읽으면 뒤늦게 도착한 실패가 엉뚱한 판정을 받는다).
     */
    const request =
      work.assetSource === 'PORTAL_UPLOAD'
        ? downloadUploadExport(work.rawSn, undefined, controller.signal)
        : downloadDatamartVideoData(work.rawSn, controller.signal);

    void request
      .catch((e: unknown) => {
        if (controller.signal.aborted) return; // 사용자가 스스로 멈춘 것 — 정상 종료
        setDownloadError(datamartDownloadErrorMessage(e));
      })
      .finally(() => {
        if (downloadAbortRef.current === controller) downloadAbortRef.current = null;
        setDownloadingRawSn(null);
      });
  };

  const onCancelDownload = () => {
    downloadAbortRef.current?.abort();
  };

  const isEmpty = !isLoading && works.length === 0;

  return (
    <section className="klid-authoring-pane" aria-labelledby="portal-my-works">
      <div className="klid-authoring-block">
        <StepHeading
          size="md"
          id="portal-my-works"
          title="내 저장 작업"
          desc="내가 올린 자산과, 내가 라벨이나 메타를 더한 영상이 여기에 모입니다."
        />

        {/* 목록에 걸리는 규칙이라 목록 바로 위에 둔다 — 표를 읽기 전에 먼저 보이게. 늘 떠 있는
            안내라 읽어 주지 않는다(`live="none"`). 회색(info)이다 — 지금 이 화면의 사정이 아니라
            한 번 알아 두면 되는 규칙이라서다.
            ★**구현 말투를 쓰지 않는다** — 알려야 할 것은 「기한이 지나면 사라진다」와 「그 전에
              내려받아 두라」 둘이다(사양 SCREEN-028). */}
        <Alert tone="info" live="none">
          보존기간이 지나면 저장한 작업과 파일이 함께 삭제됩니다. 만료 예정일 전에 필요한 자료를
          내려받아 주세요.
        </Alert>

        {downloadError !== null && (
          <Alert tone="danger" title="내려받지 못했습니다" className="klid-alert">
            <span data-testid="portal-work-download-error">{downloadError}</span>
          </Alert>
        )}

        {isLoading ? (
          /* 불러오는 동안 자리를 지킨다 — 비워 두면 실패한 것으로 읽힌다. 빈 목록과 같은 판이다. */
          <EmptyState busy title="저장한 작업을 불러오고 있습니다." />
        ) : (
          <>
            {/* 건수는 목록 왼쪽 위에 선다 — 목록을 설명하는 줄이라 목록과 한 덩어리로 묶어 12 로
                붙인다. 불러오는 동안은 세우지 않는다 — 0 으로 읽힌다. */}
            <div className="klid-authoring-list">
              <span data-testid="portal-work-count" className="sr-only">
                {totalElements}건
              </span>
              <ResultCount total={totalElements} />

              {isEmpty ? (
                /* 행이 하나도 없을 때만 나온다 — 라벨 건수가 0인 행은 작업물을 가진 정상 행이라
                   이 안내로 대신하지 않는다(사양 SCREEN-028).
                   ★조작을 두지 않는다 — 영상을 고르는 자리가 **이 배포본 바깥**(Host 화면)이라
                     여기서 갈 수 있는 곳이 없다. 누를 수 없는 버튼을 두면 막다른 길이 하나 더 는다. */
                <div data-testid="portal-work-empty">
                  <EmptyState
                    icon={Inbox}
                    title="저장한 작업이 없습니다."
                    desc="포털에서 영상을 골라 라벨이나 메타를 저장하면 여기에 모입니다."
                  />
                </div>
              ) : (
                <div className="klid-table-shell">
                  <Table scroll data-testid="portal-work-table">
                    <Table.Caption>
                      본인이 저장한 작업 목록. 대상 영상, 저장 시각, 만료 예정일, 작업, 내려받기
                      순서로 이루어집니다.
                    </Table.Caption>
                    {/* 칸 폭을 못 박는다 — 자동 배분에 맡기면 값이 짧은 칸(시각·날짜)이 이름과 같은
                        폭을 받아 파일명만 여러 줄로 접힌다. 짧은 값은 그 값만큼 주고 **남는 폭은
                        전부 이름에** 준다. 폭은 값의 글자 수가 정하는 치수라 토큰이 아니다.
                        ⚠ 내려받기 칸만 시안(`--klid-table-col-l`)보다 한 급 넓다 — 우리 버튼 문구가
                          받는 형식까지 밝혀서(「ZIP 내려받기」) 시안 폭에 들어가지 않는다. */}
                    <Table.Colgroup>
                      <Table.Col />
                      <Table.Col style={{ width: '14rem' }} />
                      <Table.Col style={{ width: '12rem' }} />
                      <Table.Col style={{ width: 'var(--klid-table-col-l)' }} />
                      <Table.Col style={{ width: 'var(--klid-table-col-xl)' }} />
                    </Table.Colgroup>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th scope="col" className="klid-th-title">
                          대상 영상
                        </Table.Th>
                        <Table.Th scope="col">저장 시각</Table.Th>
                        <Table.Th scope="col">만료 예정일</Table.Th>
                        <Table.Th scope="col">작업</Table.Th>
                        <Table.Th scope="col">내려받기</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {works.map((work) => {
                        /*
                         * ★ 진입 주소 조립은 화면이 문자열로 하지 않는다 — 단일 진실원
                         *   `buildPortalWorkLabelPath` 가 출처별로 가른다. 화면이 직접 조립하다
                         *   업로드 축 행까지 데이터마트 경로로 보낸 결함이 실제로 있었다.
                         * ★ **진입 가부와 내려받기 가부는 서로 다른 축이라 함께 막지 않는다.**
                         *   진입은 「열 프레임이 있는가」(`entrySrcSn`), 내려받기는 「본인 저작물이
                         *   있는가」(`lastSavedAt`)로 갈린다.
                         */
                        const entryPath = buildPortalWorkLabelPath(
                          work.assetSource,
                          work.rawSn,
                          work.entrySrcSn,
                        );
                        const canDownload = hasAuthoredWork(work);
                        const expiresOn = formatExpiryDate(work.expiresOn);
                        const downloading = downloadingRawSn === work.rawSn;
                        const downloadBlocked = downloadingRawSn !== null && !downloading;
                        const entryReasonId = `portal-work-no-entry-${work.rawSn}`;
                        const downloadReasonId = `portal-work-no-download-${work.rawSn}`;

                        return (
                          <Table.Tr key={work.rawSn} data-testid={`portal-work-row-${work.rawSn}`}>
                            <Table.Td>
                              {/* 이름 옆 출처 배지 — 어디서 온 영상인지. 이름은 한 줄로 두고
                                  넘치면 말줄임, 손을 올리면 전체 이름. */}
                              <span className="klid-authoring-cell-title">
                                <span className="klid-authoring-cell-name" title={work.videoName}>
                                  {work.videoName}
                                </span>
                                <Badge
                                  variant="light"
                                  color={work.assetSource === 'PORTAL_UPLOAD' ? 'primary' : 'gray'}
                                  className="klid-badge-tint klid-badge-square"
                                  data-testid={`portal-work-source-${work.rawSn}`}
                                >
                                  {SOURCE_LABEL[work.assetSource]}
                                </Badge>
                              </span>
                              <span className="klid-authoring-cell-sub">#{work.rawSn}</span>
                            </Table.Td>
                            {/* 값이 없으면 「-」로 — 지어내지 않되 칸이 빈 채로 남지도 않게
                                (부모 포털 시안의 표기. 구 동작은 빈 칸이었다). */}
                            <Table.Td>
                              <span data-testid={`portal-work-saved-${work.rawSn}`}>
                                {work.lastSavedAt !== null
                                  ? formatPortalDateTime(work.lastSavedAt)
                                  : '-'}
                              </span>
                            </Table.Td>
                            {/* 만료가 가까운 행을 색·아이콘으로 강조하지 않는다(사양이 명시 거부). */}
                            <Table.Td>
                              <span data-testid={`portal-work-expiry-${work.rawSn}`}>
                                {expiresOn !== null ? expiresOn : '-'}
                              </span>
                            </Table.Td>
                            <Table.Td>
                              {entryPath !== null ? (
                                /* ★**링크로 남긴다** — 모양만 킷 버튼이고 실체는 이동이다.
                                   킷 `Button` 은 `as` 로 어떤 요소로든 설 수 있어(다형 부품)
                                   라우터 `Link` 를 끼우면 시안의 생김새와 링크의 성질
                                   (가운데 클릭 · 새 탭 · 주소 복사 · 보조기술의 「링크」 안내)을
                                   **둘 다** 갖는다. 버튼으로 바꾸면 그 성질이 통째로 사라진다. */
                                <Button
                                  as={Link}
                                  to={entryPath}
                                  size="small"
                                  data-testid={`portal-work-continue-${work.rawSn}`}
                                >
                                  이어서 작업
                                </Button>
                              ) : (
                                <LockedAction
                                  reason={NO_ENTRY_REASON}
                                  reasonId={entryReasonId}
                                  testId={`portal-work-continue-${work.rawSn}`}
                                >
                                  이어서 작업
                                </LockedAction>
                              )}
                            </Table.Td>
                            {/* 내려받기는 제 칸으로 떼어 낸다 — 「이어서 작업」 버튼 옆에 붙어
                                있으면 무슨 버튼인지 머리글로 안 갈렸다. */}
                            <Table.Td>
                              {downloading ? (
                                <>
                                  {/* ★받는 동안 눈에 보이는 것은 「✕ 취소」 하나뿐이다(시안) —
                                      「내려받는 중…」 버튼을 따로 세우면 칸 폭 척도를 넘는다.
                                      그러면 **진행 중이라는 사실이 보조기술에 전혀 전달되지 않으므로**
                                      같은 칸에 화면 밖 상태 줄을 둔다. 이 요청은 GB 급일 수 있어
                                      「지금 받고 있다」는 사실만은 반드시 알려야 한다. */}
                                  <span role="status" className="sr-only">
                                    {work.videoName} 내려받는 중…
                                  </span>
                                  {/* 취소는 눌러야 동작하므로 상호 잠금 대상에서 빠진다. */}
                                  <Button
                                    size="small"
                                    variant="secondary"
                                    className="klid-btn-danger-line"
                                    data-testid={`portal-work-download-cancel-${work.rawSn}`}
                                    aria-label={`${work.videoName} 작업 데이터 다운로드 취소`}
                                    onClick={onCancelDownload}
                                  >
                                    <X aria-hidden />
                                    취소
                                  </Button>
                                </>
                              ) : !canDownload || downloadBlocked ? (
                                /* 받을 것이 없거나 다른 줄을 받는 중이면 잠근다 — 걸음을 지우지
                                   않고 왜 못 하는지를 남긴다(한 번에 하나만 받는다). */
                                <LockedAction
                                  reason={canDownload ? DOWNLOAD_BUSY_REASON : NO_DOWNLOAD_REASON}
                                  reasonId={downloadReasonId}
                                  testId={`portal-work-download-${work.rawSn}`}
                                  variant="secondary"
                                >
                                  {DOWNLOAD_LABEL[work.assetSource]}
                                </LockedAction>
                              ) : (
                                <Button
                                  size="small"
                                  variant="secondary"
                                  data-testid={`portal-work-download-${work.rawSn}`}
                                  aria-label={`${work.videoName} ${DOWNLOAD_LABEL[work.assetSource]}`}
                                  onClick={() => onDownload(work)}
                                >
                                  {/* 아이콘도 형식을 따른다 — 라벨 JSON 은 중괄호 문서 모양. */}
                                  {work.assetSource === 'PORTAL_UPLOAD' ? (
                                    <FileJson aria-hidden />
                                  ) : (
                                    <Download aria-hidden />
                                  )}
                                  {DOWNLOAD_LABEL[work.assetSource]}
                                </Button>
                              )}
                            </Table.Td>
                          </Table.Tr>
                        );
                      })}
                    </Table.Tbody>
                  </Table>
                </div>
              )}
            </div>

            {/* 전체가 한 쪽에 들어오면 페이저를 그리지 않는다(사양 SCREEN-028). */}
            {totalPages > 1 && (
              <PageNav
                totalPages={totalPages}
                currentPage={page + 1}
                onChange={handlePageChange}
              />
            )}
          </>
        )}
      </div>
    </section>
  );
}
