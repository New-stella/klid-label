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
// ⚠ 행 마크업을 별도 컴포넌트로 빼지 않는다 — 표 표면 관례 가드가 `<table>` ~ `</table>` **구간의
//   소스 문자열**로 행 hover 토큰을 판정해서, 행을 다른 함수로 옮기면 그 축이 구조적으로 검사
//   밖이 된다(형제 화면 `PortalAugmentPage` 도 같은 이유로 행을 인라인으로 둔다).
//
// 보안: 사용자·서버가 준 이름은 JSX 텍스트 노드로만 렌더한다(자동 escape). 본인 데이터 격리는
//   서버가 토큰 주체로 강제한다(CWE-639).

import { useRef, useState } from 'react';
import { Download, X } from 'lucide-react';
import { Link, useSearchParams } from 'react-router-dom';

import { Badge } from '@/components/common/Badge';
import { Pagination } from '@/components/common/Pagination';
import { KRDS_FOCUS } from '@/lib/focusRing';
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
import { formatDateTime } from '@/features/review/formatDateTime';

const PAGE_SIZE = 20;

/** 표 헤더 셀 — 표 표면 관례(14px/600 토큰 + 대문자화). `<th>` 에 직접 건다. */
const TH_CLASS = 'px-3 py-2 text-left text-table-header uppercase tracking-wide text-gray-600';

const ACTION_BUTTON_CLASS =
  'inline-flex shrink-0 items-center gap-1 rounded-lg border border-gray-300 px-3 py-1.5 text-sub font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50';

const CONTINUE_BUTTON_CLASS =
  'inline-flex shrink-0 items-center rounded-lg bg-primary-600 px-3 py-1.5 text-sub font-medium text-white transition-colors';

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
 *   ⚠ 구 근거도 폐기 — *"화면이 아는 사실은 「열 프레임이 없다」 하나뿐이다"*. **경고 자체는 여전히
 *     옳고**(확인하지 않은 원인을 단정하면 안 된다) 바뀐 것은 그 전제다 — 지금은 화면이 아는 것이
 *     「지금 열 수 없다」뿐이고 그 이유는 아예 알 수 없다.
 *
 * ⚠ **잠정 문구다** — 시안이 이 문구와 그 자리를 아직 확정하지 않았다(사양 SCREEN-028 이 그렇게
 *   적는다). 확정 전까지 **최소한의 평이한 표기**로 둔다. 꾸미지 말 것.
 */
const NO_ENTRY_REASON = '지금은 이어서 작업할 수 없습니다.';

/** 내려받을 저작물이 없는 행의 사유 문구. */
const NO_DOWNLOAD_REASON = '아직 저장한 작업이 없어 내려받을 것이 없습니다.';

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
    <div className="mx-auto flex w-full max-w-5xl flex-col gap-6">
      <section aria-labelledby="portal-my-works" className="flex flex-col gap-3">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 id="portal-my-works" className="text-section-title text-gray-800">
            내 저장 작업
          </h2>
          {!isLoading && (
            <span data-testid="portal-work-count" className="text-sub text-gray-600">
              {totalElements}건
            </span>
          )}
        </div>

        {downloadError !== null && (
          <p role="alert" data-testid="portal-work-download-error" className="text-sub text-danger">
            {downloadError}
          </p>
        )}

        {isLoading ? (
          <p role="status" className="text-sub text-gray-600">
            저장한 작업을 불러오는 중입니다.
          </p>
        ) : isEmpty ? (
          /* 행이 하나도 없을 때만 나온다 — 라벨 건수가 0인 행은 작업물을 가진 정상 행이라
             이 안내로 대신하지 않는다(사양 SCREEN-028). */
          <p role="status" data-testid="portal-work-empty" className="text-sub text-gray-600">
            저장한 작업이 없습니다.
          </p>
        ) : (
          <div className="overflow-x-auto rounded-lg border border-gray-200 bg-white shadow-sm">
            <table className="w-full text-body-md" data-testid="portal-work-table">
              <caption className="sr-only">
                본인이 저장한 작업 목록. 대상 영상, 저장 시각, 만료 예정일, 작업 순서로 이루어집니다.
              </caption>
              <thead>
                {/* 배경은 헤더 행에, 타이포·색은 각 <th> 에 직접 건다 — <tr>/<thead> 에만 걸면
                    브라우저 UA 기본 `th { font-weight: bold }` 가 상속값을 이긴다. */}
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
                      <td className="px-3 py-3">
                        <div className="flex min-w-0 flex-col gap-1">
                          <div className="flex flex-wrap items-center gap-2">
                            {/* 서버가 준 이름 — 텍스트 노드(자동 escape). */}
                            <span className="truncate font-medium text-gray-800">
                              {work.videoName}
                            </span>
                            <Badge
                              variant={work.assetSource === 'PORTAL_UPLOAD' ? 'info' : 'neutral'}
                              label={SOURCE_LABEL[work.assetSource]}
                              data-testid={`portal-work-source-${work.rawSn}`}
                            />
                          </div>
                          <span className="text-caption text-gray-500">#{work.rawSn}</span>
                        </div>
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
                          className="text-sub text-gray-600"
                        >
                          {work.lastSavedAt !== null ? formatDateTime(work.lastSavedAt) : ''}
                        </span>
                      </td>
                      {/* 만료도 값이 없으면 자리를 비운다. 만료가 가까운 행을 색·아이콘으로
                          강조하지 않는다(사양이 명시 거부). */}
                      <td className="px-3 py-3">
                        <span
                          data-testid={`portal-work-expiry-${work.rawSn}`}
                          className="text-sub text-gray-500"
                        >
                          {expiresOn !== null ? `만료: ${expiresOn}` : ''}
                        </span>
                      </td>
                      <td className="px-3 py-3">
                        <div className="flex flex-wrap items-center gap-2">
                          {entryPath !== null ? (
                            <Link
                              to={entryPath}
                              data-testid={`portal-work-continue-${work.rawSn}`}
                              className={cn(
                                CONTINUE_BUTTON_CLASS,
                                'hover:bg-primary-700',
                                KRDS_FOCUS,
                              )}
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
                              className={cn(
                                CONTINUE_BUTTON_CLASS,
                                'cursor-not-allowed opacity-50',
                                KRDS_FOCUS,
                              )}
                            >
                              이어서 작업
                            </button>
                          )}
                          {entryPath === null && (
                            <span id={entryReasonId} className="text-sub text-gray-600">
                              {NO_ENTRY_REASON}
                            </span>
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
                              className={cn(ACTION_BUTTON_CLASS, KRDS_FOCUS)}
                            >
                              <X className="h-3.5 w-3.5" aria-hidden />
                              취소
                            </button>
                          )}

                          <button
                            type="button"
                            data-testid={`portal-work-download-${work.rawSn}`}
                            aria-label={`${work.videoName} 작업 데이터 내려받기`}
                            /* 버튼 이름을 aria-label 이 정하므로 바뀐 본문('내려받는 중…')은
                               보조기술에 읽히지 않는다. 이 요청은 GB 급일 수 있어 진행 중이라는
                               사실만은 전달해야 한다. */
                            aria-busy={downloading || undefined}
                            aria-describedby={canDownload ? undefined : downloadReasonId}
                            disabled={downloading || downloadBlocked || !canDownload}
                            onClick={() => onDownload(work)}
                            className={cn(ACTION_BUTTON_CLASS, KRDS_FOCUS)}
                          >
                            <Download className="h-3.5 w-3.5" aria-hidden />
                            {downloading ? '내려받는 중…' : '내려받기'}
                          </button>
                          {!canDownload && (
                            <span id={downloadReasonId} className="text-sub text-gray-600">
                              {NO_DOWNLOAD_REASON}
                            </span>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {/* 전체가 한 쪽에 들어오면 페이저를 그리지 않는다(사양 SCREEN-028). */}
        {totalPages > 1 && (
          <Pagination page={page} totalPages={totalPages} onChange={handlePageChange} />
        )}

        <p className="text-sub text-gray-600">
          ※ 저장한 작업 데이터는 보존기간이 지나면 저장 행과 파일이 함께 삭제됩니다. 만료 예정일은
          조회 시점 설정으로 계산되어 응답에 실려 옵니다.
        </p>
      </section>
    </div>
  );
}
