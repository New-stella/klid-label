// 시계열 위탁 **전체 건너뛰기** 상시 배너. [@design SCREEN-008] [@design ADR-050] [@design API-068]
//
// ★ 왜 상시 배너인가 — 스위치가 켜져 있는 동안 들어오는 영상은 **전건이 시계열 없이 확정**된다.
//   그 사실이 어디에도 드러나지 않으면 아무도 모르는 사이에 학습데이터가 시계열 없이 쌓이고,
//   끄는 것을 잊으면 벤더 연동이 끝난 뒤에도 계속 건너뛴다. 그래서 배너에는 ①켜져 있다는 사실
//   ②설정에 적힌 사유 ③끄러 가는 길 셋을 함께 싣는다.
//
// ★ 상태는 색이 아니라 **문구**가 말한다(grayscale·색각 이상에서도 구분된다). 아이콘은 장식이라
//   보조기술에 이름을 주지 않는다 — 정보는 문구가 나른다.
//
// ⚠ `role="alert"`(assertive) 을 쓰지 않는다 — 이 배너는 사건 알림이 아니라 **상시 표시되는
//   상태**다. 조회할 때마다 스크린리더 낭독을 가로채면 목록 탐색이 끊긴다.

import { Link } from 'react-router-dom';

import { Alert } from '@/components/common/Alert';

/** 시스템 설정 화면 경로 — 배너에서 곧바로 끄러 갈 수 있어야 한다. */
const SYSTEM_SETTINGS_PATH = '/manage/settings';

export interface VlmSkipDefaultBannerProps {
  /** 설정에 적힌 사유(원문). 비어 있으면 사유 문장을 만들지 않는다. */
  reason?: string;
}

/**
 * ⚠ **켜짐 판정은 이 컴포넌트가 하지 않는다** — 호출부가 꺼져 있으면 아예 렌더하지 않는다.
 * 판정을 여기에도 두면 같은 규칙이 두 곳에 생겨 한쪽만 갱신된다.
 */
export function VlmSkipDefaultBanner({ reason }: VlmSkipDefaultBannerProps) {
  const trimmed = reason?.trim();

  return (
    <Alert
      variant="error"
      role="status"
      data-testid="vlm-skip-default-banner"
      title="시계열 위탁을 전체 건너뛰는 중입니다"
    >
      <>
        지금 들어오는 영상은 외부 시계열 분석에 위탁하지 않고 건너뛴 것으로 기록됩니다.
        {trimmed ? ` 사유: ${trimmed}` : ''} 연동이 끝나면{' '}
        <Link to={SYSTEM_SETTINGS_PATH} className="font-semibold underline">
          시스템 설정
        </Link>
        에서 꺼 주세요 — 끄지 않으면 이후 영상도 계속 건너뜁니다.
      </>
    </Alert>
  );
}
