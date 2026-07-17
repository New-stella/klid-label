package kr.co.cudo.authoring.portal.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 포털 업로드 설정 등록. {@link PortalUploadProperties}(record, 생성자 바인딩)를
 * 빈으로 노출한다. 도메인 내부에 국한하여 전역 설정 파일을 건드리지 않는다.
 */
@Configuration
@EnableConfigurationProperties(PortalUploadProperties.class)
public class PortalUploadConfig {
}
