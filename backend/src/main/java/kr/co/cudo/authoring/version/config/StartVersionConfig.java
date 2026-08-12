package kr.co.cudo.authoring.version.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 「시작 버전 선택」 자원 상한 설정 등록. 도메인 국소 {@code @EnableConfigurationProperties} 로
 * 전역 설정 파일을 건드리지 않는다({@code AugmentDiscardConfig} 동형).
 *
 * @design D5
 * @req R6
 */
@Configuration
@EnableConfigurationProperties(StartVersionProperties.class)
public class StartVersionConfig {
}
