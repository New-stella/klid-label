import { useEffect } from 'react';

import type { Channel } from '@/lib/api/types';

import { upstreamLoginConfigKey } from './redirectToUpstream';

/**
 * 상위 시스템 로그인 주소가 <설정되지 않아> 이동할 수 없을 때 덧붙이는 원인 안내.
 *
 * <p>배경: 주소가 없으면 {@link redirectToUpstream} 은 아무 데도 보내지 않는다(fail-closed —
 * 그건 옳다). 문제는 그 다음이었다. 화면에는 "로그인 서버에 연결할 수 없습니다 / 다시
 * 접근해주세요"만 떴는데, 그 문구는 <b>상위 서버 장애와 설정 누락을 구분하지 못한다</b>.
 * 후자는 이 서버에서 고칠 수 있는 문제인데도 손댈 곳을 알 수 없었다.
 *
 * <p>그래서 <b>세션에 일어난 일</b>(만료·인계 실패)은 종전 문구가 그대로 말하게 두고, 여기에
 * <b>설정 항목 이름</b>만 덧붙인다. 둘 중 하나를 지우면 사용자용 정보와 운영자용 정보 중
 * 한쪽이 사라진다.
 *
 * <p>파일 경로·스택은 화면에 내지 않는다(CWE-209). 그 상세는 콘솔에만 남긴다 — 키 이름은
 * 그 자체로 비밀이 아니고, 이것만 있으면 운영자가 설정 문서에서 바로 찾을 수 있다.
 *
 * <p>키 이름은 {@link upstreamLoginConfigKey} 에서 받는다 — 여기에 문자열을 다시 적으면
 * 사본이 두 번째 진실원이 되어 키를 바꿀 때 한쪽만 갱신된다.
 */
export function UpstreamLoginConfigHint({ channel }: { channel?: Channel }) {
  const configKey = upstreamLoginConfigKey(channel);

  useEffect(() => {
    // 운영자용 상세 — 화면이 아니라 콘솔로만 낸다.
    console.error(
      `[klid] 상위 시스템 로그인 주소가 없습니다: ${configKey}. ` +
        '대상 서버의 프론트엔드 설정 정본(frontend.env)에 값을 채운 뒤 설정 반영 명령을 다시 실행하세요.',
    );
  }, [configKey]);

  return (
    <span className="mt-3 block border-t border-gray-200 pt-3 text-caption">
      <span className="block">상위 시스템 로그인 주소가 설정되지 않았습니다.</span>
      <span className="block">시스템 관리자에게 아래 설정 항목을 확인해 달라고 알려주세요.</span>
      <code className="mt-1 inline-block rounded bg-gray-100 px-2 py-1 text-gray-700">
        {configKey}
      </code>
    </span>
  );
}
