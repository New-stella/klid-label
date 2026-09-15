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
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 포털 저장소의 부품(`@portal/components/custom` · KRDS 킷)으로 짓는다. 짜임·문구는 포털 화면
// 스토리북 「워크스페이스 / 저작도구 / 내 작업」(KLID_Portal `AuthoringWorkView`)과 같게 둔다.
// 데이터 · 받기 · 취소 흐름은 이 화면의 것 그대로다.
//
// 보안: 사용자·서버가 준 이름은 JSX 텍스트 노드로만 렌더한다(자동 escape). 본인 데이터 격리는
//   서버가 토큰 주체로 강제한다(CWE-639).

import { useRef, useState } from 'react';
import { Badge, Button, Table, Tooltip } from 'krds-react';
import { Inbox, X } from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';

import { Alert, EmptyState, PageNav, ResultCount, StepHeading } from '@portal/components/custom';
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
  const navigate = useNavigate();
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
    <section className="klid-authoring-pane" aria-labelledby="portal-my-works">
      <div className="klid-authoring-block">
        <StepHeading
          size="md"
          id="portal-my-works"
          title="내 저장 작업"
          desc="내가 올린 자산과, 내가 라벨이나 메타를 더한 영상이 여기에 모입니다."
        />
        {/* 보존기간 안내 — 목록에 걸리는 규칙이라 목록 바로 위에 둔다. 늘 떠 있는 안내라 읽어 주지 않는다 */}
        <Alert tone="info" live="none">
          보존기간이 지나면 저장한 작업과 파일이 함께 삭제됩니다. 만료 예정일 전에 필요한 자료를
          내려받아 주세요.
        </Alert>

        {/* 받기 실패 — 다음 받기를 누를 때 사라진다 */}
        {downloadError !== null && (
          <Alert tone="danger" title="내려받지 못했습니다">
            {downloadError}
          </Alert>
        )}

        {isLoading ? (
          <EmptyState busy title="저장한 작업을 불러오고 있습니다." />
        ) : (
          <>
            {/* 건수는 목록 왼쪽 위에 선다 — 내 업로드 · 증강 목록과 같은 결과 머리 줄(아래 목록과 12)이다.
                불러오는 동안은 세우지 않는다 — 0 으로 읽힌다 */}
            <div className="klid-authoring-assets">
              <div className="klid-result-head">
                <ResultCount total={totalElements} />
              </div>
              {isEmpty ? (
                /* 행이 하나도 없을 때만 나온다 — 라벨 건수가 0인 행은 작업물을 가진 정상 행이다.
                   영상을 고르는 자리가 포털 화면이라 여기서 갈 곳이 없어 조작을 두지 않는다 */
                <EmptyState
                  icon={Inbox}
                  title="저장한 작업이 없습니다."
                  desc="포털에서 영상을 골라 라벨이나 메타를 저장하면 여기에 모입니다."
                />
              ) : (
            <div className="klid-table-shell">
              <Table scroll>
                <Table.Caption>내 저장 작업 목록</Table.Caption>
                {/* 짧은 값은 그 값만큼 주고 남는 폭은 전부 이름에 준다. 폭은 값의 글자 수가 정하는 치수라
                    토큰이 아니다 (포털 화면과 같은 값) */}
                <Table.Colgroup>
                  <Table.Col />
                  <Table.Col style={{ width: '14rem' }} />
                  <Table.Col style={{ width: '12rem' }} />
                  <Table.Col style={{ width: 'var(--klid-table-col-l)' }} />
                  <Table.Col style={{ width: 'var(--klid-table-col-l)' }} />
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
                    /* 진입 가부(열 프레임이 있는가)와 내려받기 가부(본인 저작물이 있는가)는 다른 축이라
                       함께 막지 않는다. 진입 주소 조립은 `buildPortalWorkLabelPath` 가 출처별로 가른다 */
                    const entryPath = buildPortalWorkLabelPath(
                      work.assetSource,
                      work.rawSn,
                      work.entrySrcSn,
                    );
                    const canDownload = hasAuthoredWork(work);
                    const expiresOn = formatExpiryDate(work.expiresOn);
                    const downloading = downloadingRawSn === work.rawSn;
                    const downloadBlocked = downloadingRawSn !== null && !downloading;

                    return (
                      <Table.Tr key={work.rawSn} data-testid={`portal-work-row-${work.rawSn}`}>
                        <Table.Td>
                          {/* 이름 옆 출처 배지. 이름은 한 줄로 두고 넘치면 말줄임, 손을 올리면 전체 이름 */}
                          <span className="klid-authoring-cell-title">
                            <span className="klid-authoring-cell-name" title={work.videoName}>
                              {work.videoName}
                            </span>
                            <Badge
                              variant="light"
                              color={work.assetSource === 'PORTAL_UPLOAD' ? 'primary' : 'gray'}
                              className="klid-badge-tint klid-badge-square"
                            >
                              {SOURCE_LABEL[work.assetSource]}
                            </Badge>
                          </span>
                          <span className="klid-authoring-cell-sub">#{work.rawSn}</span>
                        </Table.Td>
                        {/* 저장 이력 · 만료일이 없으면 「-」 */}
                        <Table.Td>
                          {work.lastSavedAt !== null ? formatPortalDateTime(work.lastSavedAt) : '-'}
                        </Table.Td>
                        <Table.Td>{expiresOn ?? '-'}</Table.Td>
                        <Table.Td>
                          {entryPath === null ? (
                            <Tooltip text={NO_ENTRY_REASON}>
                              <span>
                                {/* 킷 버튼의 잠김 모양만 입힌다 — 속성으로 잠그면 손을 올려도 사유가 안 뜬다 */}
                                <Button
                                  size="small"
                                  className="disabled"
                                  aria-disabled
                                  onClick={(e) => e.preventDefault()}
                                >
                                  이어서 작업
                                </Button>
                              </span>
                            </Tooltip>
                          ) : (
                            <Button size="small" onClick={() => navigate(entryPath)}>
                              이어서 작업
                            </Button>
                          )}
                        </Table.Td>
                        <Table.Td>
                          {downloading ? (
                            /* 받는 동안 — 「내려받기」 자리에 빨간 선 「✕ 취소」 하나만 선다 */
                            <Button
                              size="small"
                              variant="secondary"
                              className="klid-btn-danger-line"
                              aria-label={`${work.videoName} 작업 데이터 다운로드 취소`}
                              onClick={onCancelDownload}
                            >
                              <X aria-hidden />
                              취소
                            </Button>
                          ) : !canDownload || downloadBlocked ? (
                            <Tooltip
                              text={!canDownload ? NO_DOWNLOAD_REASON : '다른 작업을 내려받는 중입니다.'}
                            >
                              <span>
                                <Button
                                  size="small"
                                  variant="secondary"
                                  className="disabled"
                                  aria-disabled
                                  onClick={(e) => e.preventDefault()}
                                >
                                  내려받기
                                </Button>
                              </span>
                            </Tooltip>
                          ) : (
                            <Button size="small" variant="secondary" onClick={() => onDownload(work)}>
                              내려받기
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
            {/* 전체가 한 쪽에 들어오면 쪽 넘김을 그리지 않는다. 주소의 쪽은 0부터, 쪽 넘김 줄은 1부터 센다 */}
            {totalPages > 1 && (
              <PageNav
                totalPages={totalPages}
                currentPage={page + 1}
                onChange={(next: number) => handlePageChange(next - 1)}
              />
            )}
          </>
        )}
      </div>
    </section>
  );
}
