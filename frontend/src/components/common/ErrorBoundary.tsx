import { Component, ErrorInfo, ReactNode } from 'react';

interface Props {
  fallback?: ReactNode;
  children: ReactNode;
}

interface State {
  hasError: boolean;
  message: string | null;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false, message: null };

  static getDerivedStateFromError(error: unknown): State {
    const message = error instanceof Error ? error.message : '알 수 없는 오류';
    return { hasError: true, message };
  }

  componentDidCatch(error: unknown, info: ErrorInfo): void {
    // 보안: 스택트레이스는 콘솔에만, 사용자에게 노출 금지
    // eslint-disable-next-line no-console
    console.error('[ErrorBoundary]', error, info);
  }

  render() {
    if (this.state.hasError) {
      return (
        this.props.fallback ?? (
          <div role="alert" className="p-8 text-center">
            <h1 className="text-page-title text-danger">오류가 발생했습니다</h1>
            <p className="mt-2 text-body text-neutral">잠시 후 다시 시도해 주세요.</p>
          </div>
        )
      );
    }
    return this.props.children;
  }
}
