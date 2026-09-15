import { Button } from 'krds-react';
import { CircleAlert, RotateCcw } from 'lucide-react';

import { EmptyState } from '@portal/components/custom';

/**
 * 포털 채널 진입 안내 — 인증 확인 중 · 인증 필요 · 역할 없음 · 권한 없음 · 역할 확인 실패.
 *
 * 포털 화면이 쓰는 **빈 화면 안내** 부품으로 판(`klid-authoring-pane`) 안에 세운다 — 목록이 비었거나
 * 못 불러왔을 때와 같은 자리 · 같은 모양이라, 들어갈 수 없는 사정도 영역 안의 안내로 읽힌다.
 * 문구는 가드(`router/guards.tsx`)가 정하고 이 부품은 모양만 갖는다.
 *
 * ⚠ 포털 채널 전용이다 — 관제 산출물에 포털 스킨이 새지 않게 가드가 지연 로드한다.
 */
export function PortalGuardNotice({
  title,
  desc,
  busy = false,
  onRetry,
  testId,
}: {
  title: string;
  desc?: string;
  busy?: boolean;
  onRetry?: () => void;
  testId?: string;
}) {
  return (
    <section className="klid-authoring-pane" data-testid={testId}>
      {busy ? (
        <EmptyState busy title={title} />
      ) : (
        <EmptyState
          icon={CircleAlert}
          title={title}
          desc={desc}
          action={
            onRetry && (
              <Button variant="secondary" size="medium" onClick={onRetry}>
                <RotateCcw aria-hidden />
                다시 시도
              </Button>
            )
          }
        />
      )}
    </section>
  );
}
