package com.multimodalAgent.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:multimodalAgent-auth-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE",
                "management.server.port=0",
                "multimodal-agent.ai.provider=mock",
                "multimodal-agent.knowledge.index-sync.enabled=false",
                "multimodal-agent.security.demo-accounts-enabled=true",
                "multimodal-agent.security.auth-session-store=memory",
                "multimodal-agent.security.jwt-secret=authentication-flow-test-secret-that-is-at-least-32-bytes"
        })
@ActiveProfiles("test")
@AutoConfigureWebTestClient
class AuthenticationFlowIntegrationTests {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void validCredentialsIssueAccessTokenThatAuthenticatesApiRequests() {
        Map<String, Object> login = webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "student", "password", "student123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();

        assertThat(login).isNotNull();
        assertThat(login.get("tokenType")).isEqualTo("Bearer");
        assertThat(login.get("accessToken")).isInstanceOf(String.class);

        webTestClient.get()
                .uri("/api/profile")
                .headers(headers -> headers.setBearerAuth((String) login.get("accessToken")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("student");
    }

    @Test
    void refreshTokenRotatesOnceAndRejectsReplay() {
        var login = webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "student", "password", "student123"))
                .exchange()
                .expectStatus().isOk()
                .expectCookie().httpOnly("refresh_token", true)
                .expectCookie().path("refresh_token", "/api/auth")
                .expectBody(Map.class)
                .returnResult();

        ResponseCookie firstRefresh = login.getResponseCookies().getFirst("refresh_token");
        assertThat(firstRefresh).isNotNull();

        var refreshed = webTestClient.post()
                .uri("/api/auth/refresh")
                .cookie("refresh_token", firstRefresh.getValue())
                .exchange()
                .expectStatus().isOk()
                .expectCookie().value("refresh_token", value ->
                        assertThat(value).isNotEqualTo(firstRefresh.getValue()))
                .expectBody(Map.class)
                .returnResult();

        assertThat(refreshed.getResponseBody()).isNotNull();
        assertThat(refreshed.getResponseBody().get("accessToken")).isInstanceOf(String.class);

        webTestClient.post()
                .uri("/api/auth/refresh")
                .cookie("refresh_token", firstRefresh.getValue())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheRefreshCookie() {
        var login = webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "student", "password", "student123"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult();

        String accessToken = (String) login.getResponseBody().get("accessToken");
        ResponseCookie refreshToken = login.getResponseCookies().getFirst("refresh_token");
        assertThat(refreshToken).isNotNull();

        webTestClient.post()
                .uri("/api/auth/logout")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .exchange()
                .expectStatus().isNoContent()
                .expectCookie().maxAge("refresh_token", Duration.ZERO);

        webTestClient.get()
                .uri("/api/profile")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void invalidCredentialsReturnUnauthorizedWithoutIssuingCookies() {
        webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", "student", "password", "wrong-password"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectCookie().doesNotExist("refresh_token");
    }

    @Test
    void basicCredentialsNoLongerAuthenticateApiRequests() {
        webTestClient.get()
                .uri("/api/profile")
                .headers(headers -> headers.setBasicAuth("admin@example.com", "admin123"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void currentAccountEndpointReturnsTheLiveAccountRoles() {
        Map<String, Object> login = login("student", "student123");

        webTestClient.get()
                .uri("/api/auth/me")
                .headers(headers -> headers.setBearerAuth((String) login.get("accessToken")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.username").isEqualTo("student")
                .jsonPath("$.roles[0]").isEqualTo("ROLE_USER");
    }

    @Test
    void logoutAllRevokesEverySessionForTheCurrentAccount() {
        Map<String, Object> firstLogin = login("student", "student123");
        Map<String, Object> secondLogin = login("student", "student123");

        webTestClient.post()
                .uri("/api/auth/logout-all")
                .headers(headers -> headers.setBearerAuth((String) firstLogin.get("accessToken")))
                .exchange()
                .expectStatus().isNoContent();

        for (Map<String, Object> login : java.util.List.of(firstLogin, secondLogin)) {
            webTestClient.get()
                    .uri("/api/profile")
                    .headers(headers -> headers.setBearerAuth((String) login.get("accessToken")))
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> login(String username, String password) {
        return webTestClient.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("username", username, "password", password))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
    }
}
