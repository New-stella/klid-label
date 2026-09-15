// 포털 라벨링 편집기 — 포털판(`portalMode`)이 그리는 본문 전체. [@design SCREEN-029]
//
// <h3>이 파일의 자리</h3>
// 라벨링 화면(`pages/label/LabelingPage.tsx`)은 관제판과 포털판이 **같이 쓴다.** 흐름(데이터 · 저장 ·
// 단축키 · 캔버스 · 확인 절차)은 그 파일이 한 벌로 갖고, 포털판이면 모든 훅이 끝난 뒤 이 뷰로
// 갈라 그린다. 그래서 이 뷰는 **이미 계산된 상태와 핸들러를 받아 그리기만** 한다 — 판정을 다시 세우지
// 않는다(다시 세우면 두 벌이 되어 한쪽만 갱신된다).
//
// <h3>모양 — 포털 저작도구 화면이 정본</h3>
// 짜임 · 부품 · 문구는 포털 화면 스토리북 「워크스페이스 / 저작도구 / 내 업로드 · 라벨링」
// (KLID_Portal `AuthoringLabelingView`)과 같다 —
//   · 편집 면: `EditorLayout` 한 판(머리 줄 · 도구 줄 · 도구 칸 · 캔버스 · 속성 칸 · 프레임 줄 · 재생 줄)
//   · 화면 전체 안내(불러오는 중 · 들어갈 수 없음 · 조회 실패 · 자산 안내): 편집기 대신 빈 안내 판 하나
//   · 확인 창은 포털 작은 창, 알림은 포털 알림
// ★ **캔버스(그리기 · 객체 도형)는 원본 `CanvasShell` 그대로다.** 둘러싼 칸만 포털 모습이다.
//
// <h3>원본과 달라진 자리 (모양만)</h3>
//   · 영상 잠금 · 폐기 프레임 안내 — 원본은 머리 줄 아래 가로 띠였다. 편집 틀에 그 줄이 없어 캔버스 칸 아래 안내 띠로 선다
//   · 캔버스 크기 — 원본은 캔버스 칸을 **처음 한 번만** 재서, 불러오는 중 화면으로 시작하면 기본값(1280×720)에
//     머물렀다. 포털 편집 틀은 칸 높이가 캔버스로 정해져 기본값으로 서면 칸을 넘치므로 이 뷰가 칸을 계속 잰다

import { Button } from 'krds-react';
import { CircleAlert, Lock } from 'lucide-react';

import { EmptyState, Toaster } from '@portal/components/custom';
import {
  isPortalUnavailableError,
  portalWorkErrorMessage,
  PORTAL_WORK_ERROR_UNAVAILABLE,
} from '@/features/portal/work/workError';

import { usePortalToastBridge } from './hooks/usePortalToastBridge';
import { PortalLabelingEditor } from './PortalLabelingEditor';
import type { PortalLabelingViewProps } from './types';

export type { PortalLabelingViewProps } from './types';

export function PortalLabelingView(props: PortalLabelingViewProps) {
  const toasts = usePortalToastBridge();
  return (
    <>
      <PortalLabelingBody {...props} />
      <Toaster items={toasts.items} onDismiss={toasts.dismiss} />
    </>
  );
}

/** 화면 전체 안내 — 편집기 자리를 대신한다. 영역 바로 아래 자식이어야 바닥 위 여백 규칙이 걸린다. */
function FullNotice({
  icon,
  title,
  desc,
  onBack,
  testId,
}: {
  icon?: typeof Lock;
  title: string;
  desc?: string;
  onBack?: () => void;
  testId?: string;
}) {
  return (
    <section
      className="klid-authoring-block klid-labeling-notice"
      aria-label="라벨링 편집기"
      data-testid={testId}
    >
      <EmptyState
        icon={icon}
        title={title}
        desc={desc}
        action={
          onBack ? (
            <Button variant="secondary" size="medium" onClick={onBack}>
              뒤로 가기
            </Button>
          ) : undefined
        }
      />
    </section>
  );
}

function PortalLabelingBody(props: PortalLabelingViewProps) {
  const { status } = props;

  if (status.invalidId) {
    return <FullNotice icon={CircleAlert} title="잘못된 프레임 ID" onBack={status.onBack} testId="labeling-page" />;
  }
  // 업로드 자산 갈래 — 네 사유 모두 상태 안내다. 문구는 사유 판정 한 곳(`useUploadLabelSource`)이 갖는다.
  if (status.uploadNotice) {
    return (
      <FullNotice
        icon={CircleAlert}
        title={status.uploadNotice.message}
        onBack={status.onBack}
        testId="portal-upload-notice"
      />
    );
  }
  if (status.isLoading) {
    return (
      <section className="klid-authoring-block klid-labeling-notice" aria-label="라벨링 편집기" data-testid="labeling-page">
        <EmptyState busy title="라벨을 불러오고 있습니다." />
      </section>
    );
  }
  // 403 · 404 를 가르지 않는다 — 가르면 실재 여부가 드러난다. 문구는 상태코드로만 만든다(원본 규칙).
  if (status.error && isPortalUnavailableError(status.error)) {
    return (
      <FullNotice
        icon={Lock}
        title="접근할 수 없는 영상입니다"
        desc={PORTAL_WORK_ERROR_UNAVAILABLE}
        onBack={status.onBack}
        testId="portal-forbidden-screen"
      />
    );
  }
  if (status.error) {
    return (
      <FullNotice
        icon={CircleAlert}
        title="라벨 조회 실패"
        desc={portalWorkErrorMessage(status.error)}
        onBack={status.onBack}
        testId="labeling-page"
      />
    );
  }
  return <PortalLabelingEditor {...props} />;
}
