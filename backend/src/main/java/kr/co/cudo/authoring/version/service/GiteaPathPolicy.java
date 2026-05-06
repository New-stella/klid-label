package kr.co.cudo.authoring.version.service;

import org.springframework.stereotype.Component;

/**
 * Gitea 저장 경로 정책 (Phase 8).
 *
 * <p>경로 컴포넌트는 모두 Long(srcSn) 으로 생성되어 사용자 입력이 직접 포함되지 않음 — Path Manipulation (CWE-22) 방어.
 *
 * <p>경로 형식: {@code labels/{srcSn}.json}
 * (향후 프로젝트 단위 격리 시 {@code {projectSn}/{rawSn}/{srcSn}.json} 으로 확장 가능)
 */
@Component
public class GiteaPathPolicy {

    public String path(Long srcSn) {
        if (srcSn == null || srcSn < 0) {
            throw new IllegalArgumentException("srcSn 은 0 이상의 값이어야 합니다.");
        }
        return "labels/" + srcSn + ".json";
    }
}
