// Footer — 공공 필수: 근거법령·운영기관·문의처 노출(KRDS/공공 웹 표준).
// 초안 문안 + placeholder 이며 실제 문안은 후속 교체(TODO).

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
