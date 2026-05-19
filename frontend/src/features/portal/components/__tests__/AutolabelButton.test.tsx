import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, screen, waitFor } from '@testing-library/react';
import MockAdapter from 'axios-mock-adapter';

import { apiClient } from '@/lib/api/client';
import { renderWithProviders } from '@/test/renderWithProviders';

import { AutolabelButton } from '../AutolabelButton';

describe('AutolabelButton', () => {
  let mock: MockAdapter;

  beforeEach(() => {
    mock = new MockAdapter(apiClient);
  });

  afterEach(() => {
    mock.restore();
  });

  it('클릭시_오토라벨링_API_호출_및_성공_콜백', async () => {
    // given: BE PortalAutolabelRequest 는 portalVideoSn + imageB64 가 모두 필수.
    //        프레임 base64 콜백을 통해 imageB64 를 동적으로 제공한다.
    let calledBody: { portalVideoSn?: number; imageB64?: string } | null = null;
    mock.onPost('/portal/autolabel').reply((config) => {
      calledBody = JSON.parse(config.data);
      return [
        200,
        {
          success: true,
          data: {
            detections: [
              { label: 'person', points: [10, 20, 30, 40], score: 0.9, trackId: null },
              { label: 'car', points: [50, 60, 70, 80], score: 0.85, trackId: null },
              { label: 'dog', points: [90, 100, 110, 120], score: 0.75, trackId: null },
            ],
            mock: false,
            source: 'model',
            mockReason: null,
          },
          message: null,
          errorCode: null,
        },
      ];
    });

    let receivedCount = 0;
    renderWithProviders(
      <AutolabelButton
        portalVideoSn={5}
        imageB64="data:image/png;base64,AAAA"
        onSuccess={(r) => (receivedCount = r.detections.length)}
      />,
    );
    fireEvent.click(screen.getByRole('button', { name: /오토라벨링/ }));

    await waitFor(() => expect(calledBody).not.toBeNull());
    const body = calledBody as unknown as { portalVideoSn: number; imageB64: string };
    expect(body.portalVideoSn).toBe(5);
    expect(body.imageB64).toBe('data:image/png;base64,AAAA');
    await waitFor(() => expect(receivedCount).toBe(3));
  });

  it('imageB64_미제공시_버튼_disabled', () => {
    // given: imageB64 prop 미제공
    // when: 컴포넌트 렌더
    renderWithProviders(<AutolabelButton portalVideoSn={5} />);
    // then: BE @NotBlank imageB64 검증 위반 방지 위해 버튼이 disabled
    const btn = screen.getByRole('button', { name: /오토라벨링/ }) as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
  });
});
