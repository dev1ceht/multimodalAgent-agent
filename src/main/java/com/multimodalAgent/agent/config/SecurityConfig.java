package com.multimodalAgent.agent.config;

import com.multimodalAgent.agent.security.CurrentUserDetailsService;
import com.multimodalAgent.agent.security.AuditAccessDeniedHandler;
import com.multimodalAgent.agent.security.AuditAuthenticationEntryPoint;
import com.multimodalAgent.agent.security.JwtCurrentUserAuthenticationConverter;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.config.web.server.ServerHttpSecurity;

@Configuration
@EnableWebFluxSecurity
/**
 * WebFlux 安全配置。
 *
 * <p>API authentication uses short-lived bearer access tokens. Route rules provide the
 * coarse role gate; sensitive record scope remains enforced by the domain authorization module.</p>
 */
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityFilterChain(
            ServerHttpSecurity http,
            AuditAccessDeniedHandler accessDeniedHandler,
            AuditAuthenticationEntryPoint authenticationEntryPoint,
            JwtCurrentUserAuthenticationConverter jwtAuthenticationConverter
    ) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .authorizeExchange(auth -> auth
                        .pathMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/prometheus",
                                "/h2-console/**")
                        .permitAll()
                        .pathMatchers("/actuator/info", "/actuator/metrics", "/actuator/metrics/**")
                        .hasRole("ADMIN")
                        .pathMatchers("/api/health", "/api/auth/login", "/api/auth/refresh").permitAll()
                        .pathMatchers(
                                "/api/admin/reports",
                                "/api/admin/excel-records",
                                "/api/admin/alerts",
                                "/api/admin/conversations/**")
                        .hasAnyRole("COUNSELOR", "PSYCHOLOGY_CENTER")
                        .pathMatchers("/api/admin/risk-cases/**")
                        .hasAnyRole("COUNSELOR", "PSYCHOLOGY_CENTER")
                        .pathMatchers("/api/admin/operations/**")
                        .authenticated()
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/reports/me").authenticated()
                        .pathMatchers("/api/reports/**").hasAnyRole("COUNSELOR", "PSYCHOLOGY_CENTER")
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().permitAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .build();
    }

    @Bean
    public ReactiveAuthenticationManager authenticationManager(
            CurrentUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        UserDetailsRepositoryReactiveAuthenticationManager manager =
                new UserDetailsRepositoryReactiveAuthenticationManager(userDetailsService);
        manager.setPasswordEncoder(passwordEncoder);
        return manager;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecretKey jwtSecretKey(multimodalAgentProperties properties) {
        byte[] secret = properties.getSecurity().getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder(
            SecretKey jwtSecretKey,
            multimodalAgentProperties properties
    ) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(
                properties.getSecurity().getJwtIssuer()));
        return decoder;
    }
}
