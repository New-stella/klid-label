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
 * </ul>
 */
@ConfigurationProperties(prefix = "portal.upload")
public record PortalUploadProperties(
        @DefaultValue("5368709120") long maxFileSizeBytes,
        @DefaultValue({"mp4", "mov", "avi"}) List<String> allowedExtensions,
        @DefaultValue("./storage/raw/portal") String storagePath,
        @DefaultValue({"jpg", "jpeg", "png"}) List<String> allowedImageExtensions,
        @DefaultValue("20971520") long maxImageSizeBytes,
        @DefaultValue("50") int maxImagesPerRequest,
        @DefaultValue("2000") int maxFrames
) {
}
