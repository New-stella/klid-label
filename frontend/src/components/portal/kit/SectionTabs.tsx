import type { ReactNode } from 'react'
import { Tab, TabContent, TabList, TabPanel, TabTrigger } from 'krds-react'

export interface SectionTabsItem {
  key: string
  label: string
}

export interface SectionTabsProps {
  items: SectionTabsItem[]
  /** 지금 열린 탭. 값은 늘 바깥(주소)이 정한다 */
  currentKey: string
  onSelect: (key: string) => void
  /** 열린 탭의 본문 */
  children: ReactNode
  className?: string
}

/**
 * 영역 탭 (SHELL-001 · 좌측 메뉴 대신 영역 안 면을 바꾸는 줄).
 * 킷 Tab 을 그대로 쓰되 면 하나만 그린다 — 면은 라우터가 갈아 끼우므로 안 열린 탭의
 * 본문을 미리 들고 있을 이유가 없다.
 *
 * 크기는 normal 로 못 박는다 — 킷 기본값이 full 이라 안 적으면 이 줄만 다른 토큰
 * (더 큰 글자·높이)을 먹는다.
 */
export function SectionTabs({ items, currentKey, onSelect, children, className }: SectionTabsProps) {
  return (
    <Tab
      className={className}
      variant="line"
      size="normal"
      value={currentKey}
      onValueChange={onSelect}
    >
      <TabList>
        {items.map((i) => (
          <TabTrigger key={i.key} value={i.key}>
            {i.label}
          </TabTrigger>
        ))}
      </TabList>
      <TabContent>
        <TabPanel value={currentKey}>{children}</TabPanel>
      </TabContent>
    </Tab>
  )
}
