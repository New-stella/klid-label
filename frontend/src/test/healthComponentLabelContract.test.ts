// 헬스 컴포넌트 표시명 계약 가드.
//
// 배경: 시스템 설정 화면의 외부 연동 목록은 서버 응답의 컴포넌트 **키**를 조회표
//   (HealthStatusList 의 componentLabels)로 한글 표시명으로 바꿔 그린다. 렌더 지점이
//   `componentLabels[key] ?? key` 폴백이라 조회표에 없는 키는 **오류 없이 원문 키가 그대로
//   화면에 노출**된다(실제로 `database` 가 그렇게 새어 나갔다). 반대로 서버가 보내지 않는
//   키는 아무 일도 하지 않아 죽은 채로 남는다 — 어느 쪽도 런타임 테스트로는 안 잡힌다.
//
// 그래서 **서버가 실제로 내보내는 키 집합**을 진실원으로 삼아 조회표와 양방향 대조한다.
//   진실원: backend .../sysconfig/controller/ManageHealthController.java 의 components.put("...")
//   (FE 가 호출하는 /manage/health 의 유일한 생산자다 — 이 경로를 매핑하는 컨트롤러는 하나뿐)
//
// 파일이 없으면 통과시키지 않는다(모노레포라 항상 존재해야 하며, graceful skip 은 가드를
// 조용히 무력화한다).

import { readFileSync } from 'node:fs';
import path from 'node:path';

import { describe, expect, it } from 'vitest';

import { componentLabels } from '@/features/sysconfig/components/HealthStatusList';

const REPO_ROOT = path.resolve(__dirname, '../../..');
const CONTROLLER = path.join(
  REPO_ROOT,
  'backend/src/main/java/kr/co/cudo/authoring/sysconfig/controller/ManageHealthController.java',
);

/** 컨트롤러가 응답 components 맵에 넣는 키를 소스에서 그대로 추출한다. */
function serverComponentKeys(): string[] {
  const src = readFileSync(CONTROLLER, 'utf-8');
  const keys = [...src.matchAll(/components\.put\(\s*"([^"]+)"/g)].map((m) => m[1]);
  return keys;
}

describe('헬스 컴포넌트 표시명 계약 (BE 응답 키 ↔ FE 조회표)', () => {
  it('서버가_내보내는_키를_추출할_수_있다', () => {
    // 추출이 0건이면 아래 대조가 공허하게 통과한다(빈 집합끼리 비교) — 먼저 고정한다.
    expect(serverComponentKeys().length).toBeGreaterThan(0);
  });

  it('조회표가_서버_응답_키와_정확히_일치한다_누락도_죽은_키도_없다', () => {
    const server = [...serverComponentKeys()].sort();
    const labels = Object.keys(componentLabels).sort();

    // 누락 = 화면에 원문 키가 그대로 노출 / 잉여 = 서버가 보내지 않는 죽은 키
    expect(labels).toEqual(server);
  });

  it('표시명은_비어_있지_않고_원문_키를_그대로_쓰지_않는다', () => {
    for (const [key, label] of Object.entries(componentLabels)) {
      expect(label.trim().length).toBeGreaterThan(0);
      expect(label).not.toBe(key);
    }
  });
});
