package kr.co.cudo.authoring.portal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * 포털 전용 업로드 정책 바인딩 ({@code portal.upload.*}).
 * <p>
 * 생성자 바인딩(record) — 불변 + {@code @Setter} 미사용. 파일 크기/확장자 allowlist·개수·
 * 프레임 상한을 설정으로 강제해 무제한 소비(OWASP API4)와 위험 확장자 업로드를 예방한다.
 * 빈 등록은 {@link PortalUploadConfig}(도메인 국소 {@code @EnableConfigurationProperties})가 담당한다.
 * <ul>
 *   <li>{@code maxFileSizeBytes} : 영상 파일 최대 크기(byte)</li>
 *   <li>{@code allowedExtensions} : 허용 영상 확장자 allowlist</li>
 *   <li>{@code storagePath} : 업로드 저장 루트</li>
 *   <li>{@code allowedImageExtensions} : 허용 이미지 확장자 allowlist</li>
 *   <li>{@code maxImageSizeBytes} : 이미지 파일 최대 크기(byte)</li>
 *   <li>{@code maxImagesPerRequest} : 1회 요청 최대 이미지 수</li>
 *   <li>{@code maxFrames} : 영상 1건 최대 추출 프레임 수(무제한 방지)</li>
 *   <li>{@code maxChunkBytes} : TUS PATCH 단일 청크 크기 상한(byte)</li>
 *   <li>{@code maxLabelBodyBytes} : 라벨 전체교체(PUT) 본문 크기 상한(byte)</li>
 *   <li>{@code probeTimeoutSec} : 영상 ffprobe 하드 타임아웃(초)</li>
 *   <li>{@code stuckTimeoutMinutes} : 고착 업로드를 FAILED 로 마감하는 무갱신 경과(분)</li>
 * </ul>
 *
 * <p><b>D2(2026-07-27 설정 전수조사)</b>: 아래 4개는 원래 같은 prefix 를 읽는 흩어진
 * {@code @Value} 였다. 바인딩 이원화(record + @Value)를 없애기 위해 필드로 흡수했으며
 * {@code @DefaultValue} 는 이관 전 {@code @Value} 기본값을 1:1 로 옮긴 것이다(동작 변경 0).
 * 단 {@code portal.upload.sweep.*}(스윕 주기/초기지연)은 {@code @Scheduled} 어노테이션 속성이라
 * 상수 표현식만 허용돼 record 로 옮길 수 없으므로 placeholder 로 남기고 yml 명시만 한다.
 */
@ConfigurationProperties(prefix = "portal.upload")
public record PortalUploadProperties(
        @DefaultValue("5368709120") long maxFileSizeBytes,
        @DefaultValue({"mp4", "mov", "avi"}) List<String> allowedExtensions,
        @DefaultValue("./storage/raw/portal") String storagePath,
        @DefaultValue({"jpg", "jpeg", "png"}) List<String> allowedImageExtensions,
        @DefaultValue("20971520") long maxImageSizeBytes,
        @DefaultValue("50") int maxImagesPerRequest,
        @DefaultValue("2000") int maxFrames,
        @DefaultValue("16777216") long maxChunkBytes,
        @DefaultValue("2097152") long maxLabelBodyBytes,
        @DefaultValue("30") long probeTimeoutSec,
        @DefaultValue("30") long stuckTimeoutMinutes
) {
}
