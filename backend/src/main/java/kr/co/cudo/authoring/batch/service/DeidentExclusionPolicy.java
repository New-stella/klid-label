package kr.co.cudo.authoring.batch.service;

import jakarta.annotation.PostConstruct;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 비식별 대상 판정의 <b>출처유형 제외 축</b> — 배포 설정 {@code authoring.deidentify.excluded-src-types}
 * (환경변수 {@code DEIDENTIFY_EXCLUDED_SRC_TYPES}, 쉼표 구분 복수, 기본 {@code GENERATED}).
 *
 * <p>판정 규칙(단일 원천 — 다른 곳에서 목록을 다시 해석하지 않는다):
 * <ul>
 *   <li>쉼표로 분리 → 앞뒤 공백 제거 → 빈 항목 무시.</li>
 *   <li>대소문자를 바꾸지 않고 {@code LS_DATA_RAW.SRC_TYPE} 과 <b>정확히</b> 비교한다.</li>
 *   <li>출처유형이 {@code null} 인 영상은 어떤 설정에서도 제외 대상이 아니다.</li>
 *   <li>설정을 비우면 목록이 비어 <b>모든 영상을 위탁</b>한다 — 설정이 빠진 쪽이 안전측이다.</li>
 * </ul>
 *
 * <p>★ 기동 시 허용값 검사·기동 차단은 <b>두지 않는다</b>(사용자 확정). 대신 적용 목록을 기동 INFO 로그
 * 한 줄로 남긴다 — 원천 CCTV 출처유형({@code ORIGINAL}·{@code RELAY}·{@code IMPORTED})을 잘못 넣으면
 * 그 출처 영상 전체가 비식별 없이 흐르므로, 설치 문서 경고와 이 로그가 유일한 방어다.
 *
 * <p>⚠ 관리 화면 시스템 설정({@code ConfigKeys.ALLOWED})에 이 키를 넣지 않는다 — 비식별을 우회하는
 * 스위치를 운영 중 재기동 없이 켤 수 없게 하기 위해서다.
 *
 * @design ADR-066
 * @design DFEAT-042
 */
@Slf4j
@Component
public class DeidentExclusionPolicy {

    /** 배포 설정 키 — 관리 화면 설정 키가 아니다. */
    public static final String PROPERTY_KEY = "authoring.deidentify.excluded-src-types";

    private final Set<String> excludedSrcTypes;

    public DeidentExclusionPolicy(
            @Value("${authoring.deidentify.excluded-src-types:GENERATED}") String configured) {
        this.excludedSrcTypes = Collections.unmodifiableSet(parse(configured));
    }

    private static Set<String> parse(String configured) {
        Set<String> parsed = new LinkedHashSet<>();
        if (configured == null) {
            return parsed;
        }
        for (String token : configured.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(trimmed);
            }
        }
        return parsed;
    }

    /** 기동 시 적용 목록 INFO 1줄 — 빈 목록이면 {@code []}. */
    @PostConstruct
    void logApplied() {
        List<String> shown = excludedSrcTypes.stream().map(LogSanitizer::sanitize).toList();
        log.info("[Deident] excluded srcTypes={}", shown);
    }

    /** 이 출처유형의 영상이 비식별 제외 대상인가. {@code null} 은 언제나 아니다. */
    public boolean isExcluded(String srcType) {
        return srcType != null && excludedSrcTypes.contains(srcType);
    }

    /** 적용 중인 제외 목록(불변, 설정 순서 유지). */
    public Set<String> excludedSrcTypes() {
        return excludedSrcTypes;
    }
}
