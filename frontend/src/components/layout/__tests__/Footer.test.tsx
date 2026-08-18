// Footer — 공공 필수: 근거법령·운영기관·문의처 노출(KRDS/공공 웹 표준).
// 초안 문안 + placeholder 이며 실제 문안은 후속 교체(TODO).
//
// ★이 컴포넌트는 현재 **어느 레이아웃에도 마운트돼 있지 않다** (2026-08-18 사용자 확정 — 사양
//   SHELL-001·SHELL-002 `footer.enabled=false`). 아래는 컴포넌트 단독 렌더 계약이며 "화면에 푸터가
//   보인다"는 뜻이 아니다. 실제 화면의 미노출은 AppLayout·PortalLayout 테스트가 고정한다.

import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';

import { Footer } from '../Footer';

describe('Footer', () => {
  it('Footer_근거법령_운영기관_문의처_노출', () => {
    render(<Footer />);

    // 근거법령 섹션 (개인정보 보호법 근거) — dt 라벨은 정확 매칭
    expect(screen.getByText('근거법령')).toBeInTheDocument();
    expect(screen.getByText(/개인정보 보호법/)).toBeInTheDocument();

    // 운영기관 섹션
    expect(screen.getByText('운영기관')).toBeInTheDocument();

    // 문의처 섹션
    expect(screen.getByText('문의처')).toBeInTheDocument();
  });

  it('Footer_기존_버전_발주_표기_유지', () => {
    render(<Footer />);
    expect(screen.getByText(/저작도구/)).toBeInTheDocument();
  });
});
