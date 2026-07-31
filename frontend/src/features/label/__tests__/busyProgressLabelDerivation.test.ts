// Phase 3 DEV_FIX 2차 NF-4① — 진행 오버레이 문구는 **작업 이름 단일 소스에서 파생**돼야 한다.
//
// SAVE/LOAD 만 파생 없이 완성 문구를 리터럴로 들고 있었다. base 이름(BUSY_KIND_NAME)을 바꾸면
// 토스트·거부 안내는 바뀌는데 오버레이 문구만 옛 값으로 남는다 — 이 파일이 막겠다고 선언한
// 드리프트가 같은 파일 안에서 그대로 살아 있던 형태다.

import { describe, expect, it } from 'vitest';

import type { BusyKind } from '@/stores/useLabelStore';

import {
  BUSY_KIND_CANCELLED_MESSAGE,
  BUSY_KIND_NAME,
  BUSY_KIND_PROGRESS_LABEL,
  BUSY_KIND_PROGRESS_RULE,
} from '../busyPolicy';

const KINDS = Object.keys(BUSY_KIND_NAME) as BusyKind[];

describe('busy 문구 단일 소스', () => {
  it('진행_문구는_작업이름_단일소스에서_파생된다', () => {
    for (const kind of KINDS) {
      // 규칙에 다른 이름을 넣으면 문구가 그 이름을 따라간다(리터럴이면 따라가지 않는다).
      expect(BUSY_KIND_PROGRESS_RULE[kind]('테스트작업하기')).toContain('테스트작업하');
      // 실제 노출 문구 = 그 규칙에 base 이름을 넣은 결과. 둘이 갈리면 드리프트다.
      expect(BUSY_KIND_PROGRESS_LABEL[kind]).toBe(BUSY_KIND_PROGRESS_RULE[kind](BUSY_KIND_NAME[kind]));
    }
  });

  it('사용자_노출_문구에_모델명이_없다', () => {
    // R6 무회귀 — 파생으로 바꾸면서 문구가 바뀌지 않았는지 함께 고정한다.
    for (const kind of KINDS) {
      expect(BUSY_KIND_PROGRESS_LABEL[kind]).not.toMatch(/YOLO|SAM/i);
      expect(BUSY_KIND_CANCELLED_MESSAGE[kind]).not.toMatch(/YOLO|SAM/i);
      expect(BUSY_KIND_CANCELLED_MESSAGE[kind]).toContain(BUSY_KIND_NAME[kind]);
    }
    expect(BUSY_KIND_PROGRESS_LABEL.SAVE).toBe('저장 중');
    expect(BUSY_KIND_PROGRESS_LABEL.LOAD).toBe('불러오는 중');
  });
});
