// SCR-PORTAL-001 — 포털 메인 페이지 (V1.x mock 시각 정합).
// ADR-013: 포털은 데이터마트 영상 선택·간편 라벨링 전용. 오토라벨링·업로드(TUS) 미제공.
// Phase B: 데이터마트 노출(검수 완료=APPROVED) 영상 목록 + 선택 → 라벨링 진입 동선.
// - hero 섹션 (orange gradient)
// - KPI 2개 (영상 수 · 라벨링 완료 수)
// - 영상 목록 (카드/리스트, 반응형) → 카드 선택 시 /portal/label/{firstSrcSn} 이동
// - 라벨링 카드 "시작하기" → 첫 영상 진입 (영상 0건이면 aria-disabled)
// - 영상 카드에 본인 저장 라벨의 만료 예정일 병기 + 작업 데이터 다운로드 버튼. @design SCREEN-028
//   ★ 다운로드는 카드 클릭(라벨링 진입) 영역과 분리한다.
//   ★ 만료 표기와 다운로드 가부는 **서로 다른 근거로 판정한다** — 아래 DatamartVideoCard 주석 참조.
//   ★ 구 V1.5 "포털 다운로드는 포털 자체 책임" 정책은 폐기됐다(저작도구가 제공한다).

import { useRef, useState } from 'react';
import { ChevronRight, Download, Play, Upload, X } from 'lucide-react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';

import { KpiCard } from '@/components/common/KpiCard';
import { Pagination } from '@/components/common/Pagination';
import { KRDS_FOCUS } from '@/lib/focusRing';
import { cn } from '@/lib/cn';
import { downloadDatamartVideoData, type DatamartVideo } from '@/features/portal/api';
import { datamartDownloadErrorMessage } from '@/features/portal/downloadError';
import { formatExpiryDate } from '@/features/portal/expiry';
import { useDatamartVideos } from '@/features/portal/hooks/useDatamartVideos';

const PAGE_SIZE = 20;

/**
 * 주소의 page 값(0부터)을 읽는다. 사용자가 주소를 직접 고칠 수 있으므로 음수·소수·비수치는
 * 첫 페이지로 가둔다 — 그대로 서버에 실어보내면 조회가 400 으로 떨어져 목록이 통째로 빈다.
 */
function parsePageParam(raw: string | null): number {
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0) return 0;
  return Math.floor(n);
}

export function PortalHomePage() {
  const navigate = useNavigate();
  /*
   * 페이지는 주소에 둔다 — 뒤로가기·북마크가 동작해야 하고, 내부 목록 화면(공지 등)이
   * 이미 같은 방식이다. 기본값(첫 페이지)일 때는 키를 넣지 않는다(공지 목록과 같은 관례).
   */
  const [searchParams, setSearchParams] = useSearchParams();
  const page = parsePageParam(searchParams.get('page'));

  const { data, isLoading } = useDatamartVideos({ page, size: PAGE_SIZE });

  const videos: DatamartVideo[] = data?.content ?? [];
  const totalVideos = data?.totalElements ?? 0;
  const hasVideos = videos.length > 0;
  const totalPages = data?.totalPages ?? 0;

  const handlePageChange = (next: number) => {
    const sp = new URLSearchParams(searchParams);
    if (next > 0) sp.set('page', String(next));
    else sp.delete('page');
    setSearchParams(sp, { replace: false });
  };

  // BE 가 프레임 0건 영상을 제외하므로 firstSrcSn 은 항상 존재. 첫 영상으로 진입.
  const firstEntry = videos[0]?.firstSrcSn;

  const goToLabel = (srcSn: number) => {
    navigate(`/portal/label/${srcSn}`);
  };

  const onStart = (e: React.MouseEvent) => {
    if (!hasVideos || firstEntry === undefined) {
      e.preventDefault();
      return;
    }
    goToLabel(firstEntry);
  };

  /*
   * 작업 데이터 다운로드 — 인증이 필요한 응답이라 직링크가 불가하다(형제 경로인 업로드 자산
   * 다운로드와 동일하게 apiClient 로 받아 브라우저 다운로드를 트리거한다).
   * 실패 사유는 뭉뚱그리지 않고 갈라 안내한다(요청량 초과 / 비식별 재처리 / 저장 라벨 없음 / 권한 /
   * 전송 중단). 특히 마지막 하나는 서버가 거부한 것이 아니라 **받다가 끊긴 것**이라 "잠시 후 다시"가
   * 답이 아니다 — 같은 문구로 합치면 사용자는 GB 급 전송을 반복해 유발하게 된다.
   *
   * 진행 표시는 버튼 하나로 끝낸다 — 누른 버튼은 '내려받는 중…' 으로 바뀌고 그 사이 다른 영상의
   * 버튼도 함께 잠긴다(동시 실행 방지). 별도 진행률 UI 는 사양(SCREEN-028)에 없으므로 두지 않는다.
   *
   * ★ 취소 — 이 요청은 GB 급이라 한 번 시작하면 오래 붙잡힌다. 진행 중에만 취소 조작을 띄우고,
   *   누르면 전송을 실제로 중단한다(중단 신호를 요청에 실어 보낸다).
   *
   * ★★ **사용자 취소는 오류가 아니라 정상 종료다 — 안내를 띄우지 않는다.**
   *   중단하면 응답이 오지 않아 `ApiError(status 0)` 으로 올라오는데, 그 자리는 «전송이 끊겼습니다 …
   *   연결이 안정적인 환경에서 다시» 를 안내하는 분기다(`datamartDownloadErrorMessage`). 갈라 놓지
   *   않으면 스스로 멈춘 사용자에게 회선을 탓하는 거짓 안내가 뜨고, «다시» 라는 권유까지 붙는다.
   *   ⚠ 그렇다고 «응답 없는 실패» 통합(중단·네트워크 단절·제한시간 초과를 한 문구로 묶은 것)을
   *     뒤집지 않는다 — 그건 «사용자가 할 일이 같다» 는 의도된 결정이다(downloadError.ts 머리말).
   *     여기서는 **사용자 취소만** 그 판정보다 **앞에서** 갈라낸다.
   *   ⚠ 판정 근거로 오류 객체를 쓰지 않는 이유 — 공용 클라이언트가 `ApiError` 로 감싸며 취소 표식을
   *     남기지 않아, 오류만 봐서는 취소와 회선 단절이 **구분되지 않는다**. 반면 화면은 자기가 중단을
   *     걸었는지 알고 있으므로 그 사실(`controller.signal.aborted`)로 판정한다. 공용 오류 타입을
   *     넓히지 않으므로 다른 호출부에 영향이 없다.
   */
  const [downloadingRawSn, setDownloadingRawSn] = useState<number | null>(null);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  // 진행 중인 요청의 중단 컨트롤러. 취소 버튼이 이것을 통해 전송을 끊는다.
  const downloadAbortRef = useRef<AbortController | null>(null);

  const onDownload = (rawSn: number) => {
    if (downloadingRawSn !== null) return;
    const controller = new AbortController();
    downloadAbortRef.current = controller;
    setDownloadingRawSn(rawSn);
    setDownloadError(null);
    // ref 가 아니라 지역 변수를 닫아 쓴다 — 다음 다운로드가 ref 를 덮어써도 이 catch 는 자기
    // 요청의 중단 여부를 본다(ref 를 읽으면 뒤늦게 도착한 실패가 엉뚱한 판정을 받는다).
    void downloadDatamartVideoData(rawSn, controller.signal)
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

  return (
    <div className="flex flex-col">
      {/* Hero 섹션 — KRDS Don't(그라데이션·brand 대면적 금지) 준수: 단색 neutral 배경 */}
      <section className="border-b border-gray-200 bg-gray-100 px-6 py-10">
        <div className="mx-auto max-w-4xl">
          <h1 className="mb-2 text-page-title text-gray-900">AI 학습데이터 작성 포털</h1>
          <p className="text-body text-gray-600">데이터마트 영상 선택, 간편 라벨링</p>
          <Link
            to="/portal/uploads"
            className={`mt-4 inline-flex items-center gap-1.5 rounded-lg border border-primary-300 bg-white px-3 py-2 text-sub font-medium text-primary-700 transition-colors hover:bg-primary-50 ${KRDS_FOCUS}`}
          >
            <Upload className="h-4 w-4" aria-hidden />
            내 업로드
          </Link>
        </div>
      </section>

      <div className="mx-auto flex w-full max-w-4xl flex-col gap-6 px-4 py-6">
        {/* KPI 2개 */}
        <section aria-label="요약" className="grid grid-cols-1 gap-3 md:grid-cols-2">
          <KpiCard label="영상 수" value={totalVideos} unit="건" />
          <KpiCard label="라벨링 완료" value={0} unit="건" />
        </section>

        {/* 라벨링 카드 */}
        <section aria-label="이용 방법" className="flex flex-col gap-3">
          <h2 className="text-sub font-semibold uppercase tracking-wide text-gray-600">이용 방법</h2>
          <article className="flex flex-col gap-3 rounded-xl border border-gray-200 bg-white p-5 shadow-sm">
            {/* 제목 옆 장식 아이콘은 두지 않는다 — 제목 텍스트를 되풀이할 뿐이다. */}
            <h2 className="text-section-title text-gray-800">라벨링</h2>
            <p className="text-sub text-gray-500">선택한 영상에 라벨을 추가하세요</p>
            <p className="text-body text-gray-600">
              라벨링 가능{' '}
              <span className="font-semibold text-primary-700">{totalVideos}건</span>
            </p>
            {/* WCAG 2.1.1 키보드 접근성: 영상이 없으면 진입 대상이 없어 비활성이지만, native
                `disabled` 는 Tab 순서에서 제거된다(R5 지적). `aria-disabled` 로 포커스 순서는 유지하되
                활성화만 차단한다. 영상이 있으면 첫 영상으로 진입. */}
            <button
              type="button"
              aria-disabled={!hasVideos || undefined}
              onClick={onStart}
              className={
                'mt-auto flex w-full items-center justify-center gap-1.5 rounded-lg bg-primary-600 px-3 py-2 text-sub font-medium text-white transition-colors hover:bg-primary-700 ' +
                (!hasVideos
                  ? 'cursor-not-allowed opacity-50 aria-disabled:hover:bg-primary-600'
                  : '')
              }
            >
              <Play className="h-3.5 w-3.5" aria-hidden />
              시작하기
              {/* 진행 방향 표식 — 장식이라 aria-hidden. 버튼 이름은 "시작하기" 텍스트가 정한다. */}
              <ChevronRight className="h-3.5 w-3.5" aria-hidden />
            </button>
          </article>
        </section>

        {/* 영상 목록 — 데이터마트 노출(검수 완료) 영상. 카드 선택 시 라벨링 진입. */}
        <section aria-label="데이터마트 영상" className="flex flex-col gap-3">
          <h2 className="text-sub font-semibold uppercase tracking-wide text-gray-600">
            데이터마트 영상
          </h2>
          {downloadError !== null && (
            <p role="alert" data-testid="datamart-download-error" className="text-sub text-danger">
              {downloadError}
            </p>
          )}
          {isLoading ? (
            <p className="text-sub text-gray-600">영상 목록을 불러오는 중…</p>
          ) : !hasVideos ? (
            <p className="text-sub text-gray-600">선택 가능한 영상이 없습니다.</p>
          ) : (
            <ul className="grid grid-cols-1 gap-3 md:grid-cols-2">
              {videos.map((v) => (
                <DatamartVideoCard
                  key={v.rawSn}
                  video={v}
                  onOpen={() => goToLabel(v.firstSrcSn)}
                  onDownload={() => onDownload(v.rawSn)}
                  onCancelDownload={onCancelDownload}
                  downloading={downloadingRawSn === v.rawSn}
                  downloadBlocked={downloadingRawSn !== null && downloadingRawSn !== v.rawSn}
                />
              ))}
            </ul>
          )}
          {/* 전체가 한 페이지에 들어오면 페이저를 그리지 않는다(사양 SCREEN-028). */}
          {totalPages > 1 && (
            <Pagination page={page} totalPages={totalPages} onChange={handlePageChange} />
          )}
        </section>

        <p className="text-sub text-gray-600">
          ※ 선택한 영상은 본인만 조회/라벨링할 수 있으며, 저장한 작업 데이터는 보존기간이 지나면
          삭제됩니다.
        </p>
      </div>
    </div>
  );
}

interface DatamartVideoCardProps {
  video: DatamartVideo;
  /** 카드 본문 선택 — 라벨링 진입. */
  onOpen: () => void;
  /** 작업 데이터 다운로드. */
  onDownload: () => void;
  /** 진행 중인 다운로드 취소 — 전송을 실제로 중단한다. */
  onCancelDownload: () => void;
  /** 이 영상을 내려받는 중. */
  downloading: boolean;
  /** 다른 영상을 내려받는 중 — 동시 실행을 막는다. */
  downloadBlocked: boolean;
}

/**
 * 데이터마트 영상 카드 — 라벨링 진입(카드 본문)과 작업 데이터 다운로드(별도 버튼)를 함께 둔다.
 *
 * ★ 다운로드 버튼은 **카드 클릭 영역 밖**에 둔다. 카드 안에 넣으면 ①버튼 안의 버튼이라 마크업이
 *   성립하지 않고 ②클릭이 위로 전파돼 내려받으려던 사용자가 라벨링 화면으로 끌려간다.
 *   그래서 형제 요소로 두고 클릭 전파도 함께 끊는다(감싸는 컨테이너가 생겨도 새지 않게).
 *
 * ★ 만료 예정일은 서버가 준 값이 있을 때만 그린다. 만료가 없으면 **없는 것**이지 미정이 아니므로
 *   `-` 같은 문구를 지어내지 않고 자리를 비운다.
 *
 * ★ **만료 표기와 다운로드 가부는 근거가 다르다 — 만료 부재를 "저장 라벨 없음" 으로 단정하지 않는다.**
 *   서버가 만료를 비우는 이유는 둘이다(`PortalRetentionPolicy`): ①본인 저장 라벨이 없다
 *   ②보존기간 설정이 없거나 비정상값이라 만료를 **판정할 수 없다**. ②는 저장 라벨이 멀쩡히 있는
 *   상태이고 서버도 정상 응답을 준다 — 그런데 만료만 보고 버튼을 막으면 **화면이 정상 다운로드를
 *   먼저 차단하고 거짓 사유("저장된 라벨이 없습니다")까지 댄다.**
 *   저장 라벨 유무를 알려주는 필드는 목록 응답(`DatamartVideoResponse`)에 **없고** 화면이 그것을
 *   추정할 근거도 없다. 반면 서버는 저장 라벨 0건을 **410 으로 따로 구분해** 돌려주므로, 막지 않고
 *   눌렀을 때 그 판정을 그대로 안내한다(`DOWNLOAD_ERROR_NO_LABEL`). 비활성은 **동시 실행 방지**
 *   라는, 화면이 실제로 아는 사실에만 쓴다.
 */
function DatamartVideoCard({
  video,
  onOpen,
  onDownload,
  onCancelDownload,
  downloading,
  downloadBlocked,
}: DatamartVideoCardProps) {
  const expiresOn = formatExpiryDate(video.myLabelExpiresAt);
  const disabled = downloading || downloadBlocked;

  return (
    <li className="flex flex-col gap-2 rounded-xl border border-gray-200 bg-white p-4 shadow-sm">
      <button
        type="button"
        data-testid="datamart-video-item"
        onClick={onOpen}
        className={`flex w-full flex-col gap-1 rounded-lg text-left ${KRDS_FOCUS}`}
      >
        <span className="text-section-title text-gray-800">{video.title}</span>
        <span className="text-sub text-gray-600">
          {video.eventName ?? '-'} · 프레임 {video.frameCount}건
        </span>
      </button>
      <div className="flex items-center justify-between gap-2">
        {/* 만료가 없는 상태에서는 자리를 비운다(빈 span 으로 정렬만 유지). */}
        <span data-testid="datamart-video-expiry" className="text-sub text-gray-500">
          {expiresOn !== null ? `만료: ${expiresOn}` : ''}
        </span>
        {/* 취소는 **진행 중일 때만** 나타난다. 멈출 것이 없는데 떠 있으면 무엇을 멈추는지 알 수 없다.
            다운로드 버튼 바로 옆(진행 표시가 일어나는 자리)에 두고, 카드 클릭 영역 밖이라는 성질은
            다운로드 버튼과 동일하다(이 행 전체가 카드 버튼의 형제다). */}
        {downloading && (
          <button
            type="button"
            data-testid="datamart-download-cancel"
            aria-label={`${video.title} 작업 데이터 다운로드 취소`}
            onClick={(e) => {
              // 카드(라벨링 진입) 로 전파되지 않게 한다 — 멈추려던 사용자가 화면을 떠나면 안 된다.
              e.stopPropagation();
              onCancelDownload();
            }}
            className={cn(
              'ml-auto inline-flex shrink-0 items-center gap-1 rounded-lg border border-gray-300 px-3 py-1.5 text-sub font-medium text-gray-700 transition-colors hover:bg-gray-50',
              KRDS_FOCUS,
            )}
          >
            <X className="h-3.5 w-3.5" aria-hidden />
            취소
          </button>
        )}
        <button
          type="button"
          data-testid="datamart-download-button"
          aria-label={`${video.title} 작업 데이터 다운로드`}
          /* 버튼 이름을 aria-label 이 정하므로 바뀐 본문('내려받는 중…')은 보조기술에 읽히지
             않는다. 이 요청은 GB 급이라 오래 걸릴 수 있어 진행 중이라는 사실만은 전달해야 한다. */
          aria-busy={downloading || undefined}
          disabled={disabled}
          onClick={(e) => {
            // 카드(라벨링 진입) 로 전파되지 않게 한다.
            e.stopPropagation();
            onDownload();
          }}
          className={cn(
            'inline-flex shrink-0 items-center gap-1 rounded-lg border border-gray-300 px-3 py-1.5 text-sub font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50',
            KRDS_FOCUS,
          )}
        >
          <Download className="h-3.5 w-3.5" aria-hidden />
          {downloading ? '내려받는 중…' : '다운로드'}
        </button>
      </div>
    </li>
  );
}
