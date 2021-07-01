/*
 * Copyright 2020-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.test.SpringTestRule;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.jose.TestJwks;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AccessTokenAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientCredentialsAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.TestRegisteredClients;
import org.springframework.security.oauth2.server.authorization.web.OAuth2TokenEndpointFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for the OAuth 2.0 Client Credentials Grant.
 *
 * @author Alexey Nesterov
 * @author Joe Grandja
 */
public class OAuth2ClientCredentialsGrantTests {
	private static JWKSource<SecurityContext> jwkSource;
	private static OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer;
	private static AuthenticationConverter accessTokenRequestConverter;
	private static AuthenticationProvider authenticationProvider;
	private static AuthenticationSuccessHandler accessTokenResponseHandler;
	private static AuthenticationFailureHandler errorResponseHandler;

	@Rule
	public final SpringTestRule spring = new SpringTestRule();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private RegisteredClientRepository registeredClientRepository;

	@BeforeClass
	public static void init() {
		JWKSet jwkSet = new JWKSet(TestJwks.DEFAULT_RSA_JWK);
		jwkSource = (jwkSelector, securityContext) -> jwkSelector.select(jwkSet);
		jwtCustomizer = mock(OAuth2TokenCustomizer.class);
		accessTokenRequestConverter = mock(AuthenticationConverter.class);
		authenticationProvider = mock(AuthenticationProvider.class);
		accessTokenResponseHandler = mock(AuthenticationSuccessHandler.class);
		errorResponseHandler = mock(AuthenticationFailureHandler.class);
	}

	@SuppressWarnings("unchecked")
	@Before
	public void setup() {
		reset(jwtCustomizer);
	}

	@Test
	public void requestWhenTokenRequestNotAuthenticatedThenUnauthorized() throws Exception {
		this.spring.register(AuthorizationServerConfiguration.class).autowire();

		this.mvc.perform(MockMvcRequestBuilders.post(OAuth2TokenEndpointFilter.DEFAULT_TOKEN_ENDPOINT_URI)
				.param(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.CLIENT_CREDENTIALS.getValue()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	public void requestWhenTokenRequestValidThenTokenResponse() throws Exception {
		this.spring.register(AuthorizationServerConfiguration.class).autowire();

		RegisteredClient registeredClient = TestRegisteredClients.registeredClient2().build();
		this.registeredClientRepository.save(registeredClient);

		this.mvc.perform(post(OAuth2TokenEndpointFilter.DEFAULT_TOKEN_ENDPOINT_URI)
				.param(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())
				.param(OAuth2ParameterNames.SCOPE, "scope1 scope2")
				.header(HttpHeaders.AUTHORIZATION, "Basic " + encodeBasicAuth(
						registeredClient.getClientId(), registeredClient.getClientSecret())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.access_token").isNotEmpty())
				.andExpect(jsonPath("$.scope").value("scope1 scope2"));

		verify(jwtCustomizer).customize(any());
	}

	@Test
	public void requestWhenTokenRequestPostsClientCredentialsThenTokenResponse() throws Exception {
		this.spring.register(AuthorizationServerConfiguration.class).autowire();

		RegisteredClient registeredClient = TestRegisteredClients.registeredClient2().build();
		this.registeredClientRepository.save(registeredClient);

		this.mvc.perform(post(OAuth2TokenEndpointFilter.DEFAULT_TOKEN_ENDPOINT_URI)
				.param(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())
				.param(OAuth2ParameterNames.SCOPE, "scope1 scope2")
				.param(OAuth2ParameterNames.CLIENT_ID, registeredClient.getClientId())
				.param(OAuth2ParameterNames.CLIENT_SECRET, registeredClient.getClientSecret()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.access_token").isNotEmpty())
				.andExpect(jsonPath("$.scope").value("scope1 scope2"));

		verify(jwtCustomizer).customize(any());
	}

	@Test
	public void requestWhenTokenEndpointCustomizedThenUsed() throws Exception {
		this.spring.register(AuthorizationServerConfigurationCustomTokenEndpoint.class).autowire();

		RegisteredClient registeredClient = TestRegisteredClients.registeredClient2().build();
		this.registeredClientRepository.save(registeredClient);

		OAuth2ClientAuthenticationToken clientPrincipal = new OAuth2ClientAuthenticationToken(registeredClient);
		OAuth2ClientCredentialsAuthenticationToken clientCredentialsAuthentication =
				new OAuth2ClientCredentialsAuthenticationToken(clientPrincipal, null, null);
		when(accessTokenRequestConverter.convert(any())).thenReturn(clientCredentialsAuthentication);

		OAuth2AccessToken accessToken = new OAuth2AccessToken(
				OAuth2AccessToken.TokenType.BEARER, "token",
				Instant.now(), Instant.now().plus(Duration.ofHours(1)));
		OAuth2AccessTokenAuthenticationToken accessTokenAuthentication =
				new OAuth2AccessTokenAuthenticationToken(registeredClient, clientPrincipal, accessToken);
		when(authenticationProvider.supports(eq(OAuth2ClientCredentialsAuthenticationToken.class))).thenReturn(true);
		when(authenticationProvider.authenticate(any())).thenReturn(accessTokenAuthentication);

		this.mvc.perform(post(OAuth2TokenEndpointFilter.DEFAULT_TOKEN_ENDPOINT_URI)
				.param(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.CLIENT_CREDENTIALS.getValue())
				.header(HttpHeaders.AUTHORIZATION, "Basic " + encodeBasicAuth(
						registeredClient.getClientId(), registeredClient.getClientSecret())))
				.andExpect(status().isOk());

		verify(accessTokenRequestConverter).convert(any());
		verify(authenticationProvider).authenticate(eq(clientCredentialsAuthentication));
		verify(accessTokenResponseHandler).onAuthenticationSuccess(any(), any(), eq(accessTokenAuthentication));
	}

	private static String encodeBasicAuth(String clientId, String secret) throws Exception {
		clientId = URLEncoder.encode(clientId, StandardCharsets.UTF_8.name());
		secret = URLEncoder.encode(secret, StandardCharsets.UTF_8.name());
		String credentialsString = clientId + ":" + secret;
		byte[] encodedBytes = Base64.getEncoder().encode(credentialsString.getBytes(StandardCharsets.UTF_8));
		return new String(encodedBytes, StandardCharsets.UTF_8);
	}

	@EnableWebSecurity
	@Import(OAuth2AuthorizationServerConfiguration.class)
	static class AuthorizationServerConfiguration {

		@Bean
		OAuth2AuthorizationService authorizationService() {
			return new InMemoryOAuth2AuthorizationService();
		}

		@Bean
		OAuth2AuthorizationConsentService authorizationConsentService() {
			return new InMemoryOAuth2AuthorizationConsentService();
		}

		@Bean
		RegisteredClientRepository registeredClientRepository() {
			// @formatter:off
			RegisteredClient dummyClient = TestRegisteredClients.registeredClient()
					.id("dummy-client")
					.clientId("dummy-client")
					.clientSecret("dummy-secret")
					.build();
			// @formatter:on
			return new InMemoryRegisteredClientRepository(dummyClient);
		}

		@Bean
		JWKSource<SecurityContext> jwkSource() {
			return jwkSource;
		}

		@Bean
		OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer() {
			return jwtCustomizer;
		}

		@Bean
		PasswordEncoder passwordEncoder() {
			return NoOpPasswordEncoder.getInstance();
		}

	}

	@EnableWebSecurity
	static class AuthorizationServerConfigurationCustomTokenEndpoint extends AuthorizationServerConfiguration {
		// @formatter:off
		@Bean
		public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
			OAuth2AuthorizationServerConfigurer<HttpSecurity> authorizationServerConfigurer =
					new OAuth2AuthorizationServerConfigurer<>();
			authorizationServerConfigurer
					.tokenEndpoint(tokenEndpoint ->
							tokenEndpoint
									.accessTokenRequestConverter(accessTokenRequestConverter)
									.authenticationProvider(authenticationProvider)
									.accessTokenResponseHandler(accessTokenResponseHandler)
									.errorResponseHandler(errorResponseHandler));
			RequestMatcher endpointsMatcher = authorizationServerConfigurer.getEndpointsMatcher();

			http
					.requestMatcher(endpointsMatcher)
					.authorizeRequests(authorizeRequests ->
							authorizeRequests.anyRequest().authenticated()
					)
					.csrf(csrf -> csrf.ignoringRequestMatchers(endpointsMatcher))
					.apply(authorizationServerConfigurer);
			return http.build();
		}
		// @formatter:on
	}

}
