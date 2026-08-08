package com.multimodalAgent.agent.config;

import com.multimodalAgent.agent.security.CurrentUserDetailsService;
import com.multimodalAgent.agent.security.AuditAccessDeniedHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UserDetailsRepositoryReactiveAuthenticationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.config.web.server.ServerHttpSecurity;

@Configuration
@EnableWebFluxSecurity
/**
 * WebFlux 安全配置。
 *
 * <p>项目使用 HTTP Basic 简化演示登录；管理员接口要求 ADMIN 角色，
 * 普通 API 要求已登录用户。</p>
 */
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityFilterChain(
            ServerHttpSecurity http,
            ReactiveAuthenticationManager authenticationManager,
            AuditAccessDeniedHandler accessDeniedHandler
    ) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authenticationManager(authenticationManager)
                .httpBasic(Customizer.withDefaults())
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers("/actuator/health", "/h2-console/**").permitAll()
                        .pathMatchers("/actuator/info", "/actuator/metrics", "/actuator/metrics/**")
                        .hasRole("ADMIN")
                        .pathMatchers(
                                "/api/admin/reports",
                                "/api/admin/excel-records",
                                "/api/admin/alerts",
                                "/api/admin/conversations/**")
                        .hasAnyRole("COUNSELOR", "PSYCHOLOGY_CENTER")
                        .pathMatchers("/api/admin/risk-cases/**")
                        .hasAnyRole("COUNSELOR", "PSYCHOLOGY_CENTER")
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")
                        .pathMatchers("/api/reports/me").authenticated()
                        .pathMatchers("/api/reports/**").hasAnyRole("COUNSELOR", "PSYCHOLOGY_CENTER")
                        .pathMatchers("/api/**").authenticated()
                        .anyExchange().permitAll())
                .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(accessDeniedHandler))
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
}
