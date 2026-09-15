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
// <h3>모양 — DS-002(포털 채널) 축</h3>
// 관제 공통 부품(`components/common/*`)을 쓰지 않고 포털 전용 계층(`components/portal/ui/*`)을 쓴다.
// 그 부품들은 관제 화면 여럿이 함께 쓰므로 포털 모양을 넣으면 관제 화면이 같이 바뀐다
// (사용자 확정 구속: **관제향 화면·컴포넌트 불변**). 색·크기는 채널이 산출 시점에 정한다.
//
// ⚠ 행 마크업을 별도 컴포넌트로 빼지 않는다 — 표 표면 관례 가드가 `<table>` ~ `</table>` **구간의
//   소스 문자열**로 행 hover 토큰을 판정해서, 행을 다른 함수로 옮기면 그 축이 구조적으로 검사
//   밖이 된다(형제 화면 `PortalAugmentPage` 도 같은 이유로 행을 인라인으로 둔다).
//
// 보안: 사용자·서버가 준 이름은 JSX 텍스트 노드로만 렌더한다(자동 escape). 본인 데이터 격리는
//   서버가 토큰 주체로 강제한다(CWE-639).

import { useRef, useState } from 'react';
import { Download, FileBraces, Inbox, X } from 'lucide-react';
import { Link, useSearchParams } from 'react-router-dom';

import { Pagination } from '@/components/common/Pagination';
import { PortalAlert } from '@/components/portal/ui/PortalAlert';
import { PortalBadge } from '@/components/portal/ui/PortalBadge';
import { PortalCard } from '@/components/portal/ui/PortalCard';
import { PortalEmptyState } from '@/components/portal/ui/PortalEmptyState';
import { PortalListSkeleton } from '@/components/portal/ui/PortalListSkeleton';
import { PortalSectionHead } from '@/components/portal/ui/PortalSectionHead';
import { portalButtonSm } from '@/components/portal/ui/portalControl';
import { cn } from '@/lib/cn';
import {
  downloadDatamartVideoData,
  type PortalUserWork,
  type PortalWorkAssetSource,
} from '@/features/portal/api';
import { datamartDownloadErrorMessage } from '@/features/portal/downloadError';
import { formatExpiryDate } from '@/features/portal/expiry';
import { buildPortalWorkLabelPath } from '@/features/portal/labelingEntry';
import { downloadUploadExport } from '@/features/portal/uploads/api';
import { useUserWorks } from '@/features/portal/hooks/useUserWorks';
import { formatPortalDateTime } from '@/features/portal/formatDateTime';

const PAGE_SIZE = 20;

/**
 * 표 헤더 셀 — DS-002 표 표면 관례.
 *
 * ★굵기가 아니라 **색으로 죽인다** — `text-table-header` 가 채널마다 굵기를 정한다(포털 500 /
 *  관제 600). 이 한 칸이 두 채널이 정면으로 갈리는 자리다(`DS-002.do_rules`).
 * ⚠ 글자색은 **600 이다. 500 으로 내리지 말 것** — DS-002 본문은 표 헤더를 slate-500 로 적지만
 *   이 배경(`bg-secondary-50`) 위에서 500 은 4.23:1 로 AA 미달이고 600 이 통과한다. 두 채널
 *   모두에서 그 경계를 못박은 회귀 가드가 있다(`tableSurfaceConvention`).
 * ⚠ `<th>` 에 직접 건다 — `<tr>`/`<thead>` 에만 걸면 브라우저 기본 `th { font-weight: bold }` 가
 *   상속값을 이긴다.
 */
const TH_CLASS = 'px-3 py-2 text-left text-table-header uppercase tracking-wide text-gray-600';

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
 *   주지 않는다**(인지·수용한 대가로 창구 사양·응답 필드 설명·서비스 주석 세 자리에 명시돼 있다).
 *   그래서 **두 사유 모두에 참인 표현**만 쓴다.
 *   ⚠ 구 문구 폐기 — *"열 수 있는 프레임이 없어 …"*. ②에서는 **프레임이 멀쩡히 있는데** 없다고
 *     말하게 되어, 사용자가 프레임이 사라진 줄 알고 엉뚱한 회복 경로로 간다.
 *
 * ⚠ **잠정 문구다** — 시안이 이 문구와 그 자리를 아직 확정하지 않았다(사양 SCREEN-028 이 그렇게
 *   적는다). 확정 전까지 **최소한의 평이한 표기**로 둔다. 꾸미지 말 것.
 */
const NO_ENTRY_REASON = '지금은 이어서 작업할 수 없습니다.';

/** 내려받을 저작물이 없는 행의 사유 문구. */
const NO_DOWNLOAD_REASON = '아직 저장한 작업이 없어 내려받을 것이 없습니다.';

/**
 * 내려받기 버튼 문구 — **받는 형식을 밝힌다**[SCREEN-028].
 * 같은 자리의 버튼이 출처마다 다른 파일을 내려주므로(데이터마트 = 프레임 이미지·문서·비식별 영상 ZIP,
 * 업로드 = 라벨 JSON 한 파일) 문구가 같으면 누르기 전에는 무엇을 받는지 알 수 없다.
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

  const handlePageChange = (next: number) => {
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
   *   예측할 수 없다 — 그래서 업로드 내보내기 창구에도 중단 신호를 싣는다(그쪽 함수에 선택 인자를
   *   더했다). 사양(SCREEN-028)이 「같은 자리에서 전송을 멈출 수 있어야 한다」를 행 구분 없이 요구한다.
   *
   * ★★ **사용자 취소는 오류가 아니라 정상 종료다 — 안내를 띄우지 않는다.** 중단하면 응답이 오지
   *   않아 `ApiError(status 0)` 으로 올라오는데, 그 자리는 «전송이 끊겼습니다 … 연결이 안정적인
   *   환경에서 다시» 를 안내하는 분기다. 갈라 놓지 않으면 스스로 멈춘 사용자에게 회선을 탓하는 거짓
   *   안내가 뜨고 «다시» 라는 권유까지 붙는다.
   *   ⚠ 판정 근거로 오류 객체를 쓰지 않는다 — 공용 클라이언트가 취소 표식을 남기지 않아 오류만
   *     봐서는 취소와 회선 단절이 **구분되지 않는다**. 반면 화면은 자기가 중단을 걸었는지 알고
   *     있으므로 그 사실(`controller.signal.aborted`)로 판정한다.
   *   ⚠ 그렇다고 «응답 없는 실패» 통합(중단·네트워크 단절·제한시간 초과를 한 문구로 묶은 것)을
   *     뒤집지 않는다 — 그건 «사용자가 할 일이 같다» 는 의도된 결정이다(downloadError.ts 머리말).
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
    <div className="flex w-full flex-col gap-column">
      <section aria-labelledby="portal-my-works" className="flex flex-col gap-in-component">
        <PortalSectionHead
          id="portal-my-works"
          title="내 저장 작업"
          lead="내가 올린 자산과, 내가 라벨이나 메타를 더한 영상이 여기에 모입니다."
          count={
            isLoading ? undefined : (
              <span data-testid="portal-work-count">{totalElements}건</span>
            )
          }
        />

        {downloadError !== null && (
          <PortalAlert
            tone="error"
            title="내려받지 못했습니다"
            description={downloadError}
            live
            data-testid="portal-work-download-error"
          />
        )}

        {isLoading ? (
          <>
            <p role="status" className="text-body-sm text-gray-600">
              저장한 작업을 불러오고 있습니다.
            </p>
            <PortalListSkeleton />
          </>
        ) : isEmpty ? (
          /* 행이 하나도 없을 때만 나온다 — 라벨 건수가 0인 행은 작업물을 가진 정상 행이라
             이 안내로 대신하지 않는다(사양 SCREEN-028).
             ★조작을 두지 않는다 — 영상을 고르는 자리가 **이 배포본 바깥**(Host 화면)이라
               여기서 갈 수 있는 곳이 없다. 누를 수 없는 버튼을 두면 막다른 길이 하나 더 는다. */
          <PortalEmptyState
            data-testid="portal-work-empty"
            icon={Inbox}
            title="저장한 작업이 없습니다."
            description="포털에서 영상을 골라 라벨이나 메타를 저장하면 여기에 모입니다."
          />
        ) : (
          <PortalCard ariaLabel="내 저장 작업 목록" bodyClassName="overflow-x-auto p-0">
            {/*
              * ★**열 폭을 표에 맡기지 않는다.** 자동 배분에 두면 브라우저가 「가장 잘 접히는 열」을
              *   최소 폭까지 눌러 그 칸만 여러 줄로 흘러내린다(형제 화면 증강 목록에서 실제로
              *   생성 조건 칸이 한 글자씩 세로로 접혔다). 폭을 못박고 넘치는 이름은 줄임표로 자른다.
              * ⚠ 대상 영상만 남은 폭을 갖는다(`w-auto`) — 이름 길이가 제각각이라 고정 폭을 주면
              *   짧은 이름에서 빈 자리가, 긴 이름에서 과도한 절단이 생긴다.
              * ⚠ 작업 열은 조작 둘에 **못 누르는 사유 두 줄**까지 들어갈 수 있어 가장 넓다.
              *   그 사유를 지우면 왜 못 누르는지 알 길이 사라지므로 폭으로 받아 준다.
              */}
            <table
              className="w-full min-w-[52rem] table-fixed text-body-md"
              data-testid="portal-work-table"
            >
              <caption className="sr-only">
                본인이 저장한 작업 목록. 대상 영상, 저장 시각, 만료 예정일, 작업 순서로 이루어집니다.
              </caption>
              <colgroup>
                <col />
                <col className="w-[9.5rem]" />
                <col className="w-[10.5rem]" />
                <col className="w-[21rem]" />
              </colgroup>
              <thead>
                <tr className="border-b border-gray-200 bg-secondary-50">
                  <th scope="col" className={TH_CLASS}>
                    대상 영상
                  </th>
                  <th scope="col" className={TH_CLASS}>
                    저장 시각
                  </th>
                  <th scope="col" className={TH_CLASS}>
                    만료 예정일
                  </th>
                  <th scope="col" className={TH_CLASS}>
                    작업
                  </th>
                </tr>
              </thead>
              <tbody>
                {works.map((work) => {
                  /*
                   * ★ 진입 주소 조립은 화면이 문자열로 하지 않는다 — 단일 진실원
                   *   `buildPortalWorkLabelPath` 가 출처별로 가른다. 화면이 직접 조립하다
                   *   업로드 축 행까지 데이터마트 경로로 보낸 결함이 실제로 있었다.
                   * ★ **진입 가부와 내려받기 가부는 서로 다른 축이라 함께 막지 않는다.**
                   *   진입은 「열 프레임이 있는가」(`entrySrcSn`), 내려받기는 「본인 저작물이
                   *   있는가」(`lastSavedAt`)로 갈린다. 들어갈 수 없는 행에도 내려받을 것이 있을
                   *   수 있고 그 행의 만료 예정일도 그대로 보인다 — 그것이 그 행을 목록에 남기는
                   *   이유다(삭제 대상인데 화면에서 사라지면 안 된다).
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
                    <tr
                      key={work.rawSn}
                      data-testid={`portal-work-row-${work.rawSn}`}
                      className="border-b border-gray-100 transition-colors last:border-b-0 hover:bg-rowHover"
                    >
                      {/*
                        * ⚠ 시안(SD-024)은 이 칸을 `th scope=row` 로 그렸으나 **`td` 로 둔다.**
                        *   표 표면 관례 가드가 파일 안의 `<th>` 를 **전부 열 머리로 보고** 헤더
                        *   타이포·대문자화·굵기를 요구해서, 본문 행 머리를 `th` 로 두면 그 칸이
                        *   열 머리 규격 위반으로 잡힌다(가드가 `scope` 를 가르지 않는다).
                        *   형제 화면(포털 업로드 목록)도 같은 이유로 `td` 다 — 한 채널 안에서
                        *   표 구조가 갈리지 않게 맞춘다. 가드가 `scope="col"` 만 보도록 좁히는 것은
                        *   별건이며, 그때 시안대로 되돌린다.
                        */}
                      <td className="px-3 py-3">
                        <span className="flex min-w-0 flex-col gap-tight">
                          <span className="flex flex-wrap items-center gap-inline">
                            {/* 서버가 준 이름 — 텍스트 노드(자동 escape). */}
                            <span
                              className="min-w-0 truncate text-body-md font-medium text-gray-900"
                              title={work.videoName}
                            >
                              {work.videoName}
                            </span>
                            <PortalBadge
                              tone={work.assetSource === 'PORTAL_UPLOAD' ? 'primary' : 'outline'}
                              data-testid={`portal-work-source-${work.rawSn}`}
                            >
                              {SOURCE_LABEL[work.assetSource]}
                            </PortalBadge>
                          </span>
                          {/* 식별자는 자리폭 고정 글꼴로 — 숫자를 오독하지 않게. */}
                          <span className="text-mono text-gray-400">#{work.rawSn}</span>
                        </span>
                      </td>
                      {/*
                       * 저장 이력이 없으면 **자리를 비운다** — 정렬에는 자산이 생긴 시각이 대신
                       * 쓰이지만 그 대체값을 표시로 끌어오지 않는다(표시 값과 정렬 값이 다른 것이
                       * 의도다 — 사양 SCREEN-028). `-`·`없음` 을 지어내면 값이 정해졌는데 표기만
                       * 빠진 것으로 읽힌다.
                       */}
                      <td className="px-3 py-3">
                        <span
                          data-testid={`portal-work-saved-${work.rawSn}`}
                          className="text-body-sm tabular-nums whitespace-nowrap text-gray-600"
                        >
                          {work.lastSavedAt !== null ? formatPortalDateTime(work.lastSavedAt) : ''}
                        </span>
                      </td>
                      {/* 만료도 값이 없으면 자리를 비운다. 만료가 가까운 행을 색·아이콘으로
                          강조하지 않는다(사양이 명시 거부). */}
                      <td className="px-3 py-3">
                        <span
                          data-testid={`portal-work-expiry-${work.rawSn}`}
                          className="text-body-sm tabular-nums whitespace-nowrap text-gray-500"
                        >
                          {expiresOn !== null ? `만료: ${expiresOn}` : ''}
                        </span>
                      </td>
                      <td className="px-3 py-3">
                        <span className="flex flex-col items-start gap-tight">
                          <span className="flex flex-wrap items-center gap-inline">
                            {entryPath !== null ? (
                              <Link
                                to={entryPath}
                                data-testid={`portal-work-continue-${work.rawSn}`}
                                className={portalButtonSm('primary')}
                              >
                                이어서 작업
                              </Link>
                            ) : (
                              /* WCAG 2.1.1 — native `disabled` 는 Tab 순서에서 제거되어 왜 못 누르는지
                                 알 길이 사라진다. `aria-disabled` 로 포커스 순서는 유지하되 활성화만
                                 막고, 사유를 `aria-describedby` 로 이어 보조기술에도 읽히게 한다. */
                              <button
                                type="button"
                                aria-disabled="true"
                                aria-describedby={entryReasonId}
                                data-testid={`portal-work-continue-${work.rawSn}`}
                                onClick={(e) => e.preventDefault()}
                                className={portalButtonSm('primary')}
                              >
                                이어서 작업
                              </button>
                            )}

                            {/* 취소는 **진행 중일 때만** 나타난다. 멈출 것이 없는데 떠 있으면 무엇을
                                멈추는지 알 수 없다. 진행 중 상호 비활성 대상에서 제외된다 — 취소는
                                눌러야 동작한다. */}
                            {downloading && (
                              <button
                                type="button"
                                data-testid={`portal-work-download-cancel-${work.rawSn}`}
                                aria-label={`${work.videoName} 작업 데이터 다운로드 취소`}
                                onClick={onCancelDownload}
                                className={portalButtonSm('danger')}
                              >
                                <X className="size-3.5" strokeWidth={2} aria-hidden />
                                취소
                              </button>
                            )}

                            <button
                              type="button"
                              data-testid={`portal-work-download-${work.rawSn}`}
                              aria-label={`${work.videoName} ${DOWNLOAD_LABEL[work.assetSource]}`}
                              /* 버튼 이름을 aria-label 이 정하므로 바뀐 본문('내려받는 중…')은
                                 보조기술에 읽히지 않는다. 이 요청은 GB 급일 수 있어 진행 중이라는
                                 사실만은 전달해야 한다. */
                              aria-busy={downloading || undefined}
                              aria-describedby={canDownload ? undefined : downloadReasonId}
                              disabled={downloading || downloadBlocked || !canDownload}
                              onClick={() => onDownload(work)}
                              className={portalButtonSm('secondary')}
                            >
                              {/* 아이콘도 형식을 따른다 — 라벨 JSON 은 업로드 목록과 같은 중괄호 문서 모양. */}
                              {work.assetSource === 'PORTAL_UPLOAD' ? (
                                <FileBraces className="size-3.5" strokeWidth={2} aria-hidden />
                              ) : (
                                <Download className="size-3.5" strokeWidth={2} aria-hidden />
                              )}
                              {downloading ? '내려받는 중…' : DOWNLOAD_LABEL[work.assetSource]}
                            </button>
                          </span>

                          {/* 못 누르는 사유는 버튼 아래 한 줄로 — 조작과 같은 칸에 나란히 두면
                              행 하나가 두 줄로 벌어져 목록을 훑는 흐름이 끊긴다. */}
                          {entryPath === null && (
                            <span id={entryReasonId} className="text-caption text-gray-500">
                              {NO_ENTRY_REASON}
                            </span>
                          )}
                          {!canDownload && (
                            <span id={downloadReasonId} className="text-caption text-gray-500">
                              {NO_DOWNLOAD_REASON}
                            </span>
                          )}
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </PortalCard>
        )}

        {/* 전체가 한 쪽에 들어오면 페이저를 그리지 않는다(사양 SCREEN-028). */}
        {totalPages > 1 && (
          <Pagination page={page} totalPages={totalPages} onChange={handlePageChange} />
        )}

        {/*
         * 보존기간 안내.
         * ★**구현 말투를 쓰지 않는다** — 구 문구의 «저장 행»·«조회 시점 설정으로 계산되어 응답에
         *   실려 옵니다» 는 서버 사정이라 이용자가 할 일을 알려 주지 않는다. 알려야 할 것은
         *   「기한이 지나면 사라진다」와 「그 전에 내려받아 두라」 둘이다(사양 SCREEN-028 이 이
         *   컴포넌트의 note 로 그렇게 규정한다).
         */}
        <p className={cn('text-body-sm text-pretty text-gray-500')}>
          보존기간이 지나면 저장한 작업과 파일이 함께 삭제됩니다. 만료 예정일 전에 필요한 자료를
          내려받아 두세요.
        </p>
      </section>
    </div>
  );
}
