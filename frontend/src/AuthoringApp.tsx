import { QueryClientProvider } from '@tanstack/react-query';

import { App } from './App';
import { ToastProvider } from './components/common/ToastProvider';
import { queryClient } from './lib/queryClient';

/**
 * 저작도구 앱의 프로바이더 트리 — 두 진입점이 공유하는 공통 몸통.
 *
 * 독립 앱(`main.tsx`)과 포털 Remote(`remote/AuthoringRemote.tsx`)가 이것을 함께 쓴다.
 *
 * ⚠ `ReactDOM.createRoot` 와 `React.StrictMode` 는 여기 넣지 않는다 — 그 둘은 **문서를
 *   소유한 쪽**의 몫이다(내부 채널은 `main.tsx`, 포털 채널은 Host). 여기에 두면 Host 트리
 *   안에 두 번째 root 가 생겨 이벤트·컨텍스트가 갈린다.
 */
export function AuthoringApp() {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <App />
      </ToastProvider>
    </QueryClientProvider>
  );
}
