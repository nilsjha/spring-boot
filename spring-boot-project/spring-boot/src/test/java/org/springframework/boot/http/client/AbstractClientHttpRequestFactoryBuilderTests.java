/*
 * Copyright 2012-2024 the original author or authors.
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

package org.springframework.boot.http.client;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import javax.net.ssl.SSLHandshakeException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.jks.JksSslStoreBundle;
import org.springframework.boot.ssl.jks.JksSslStoreDetails;
import org.springframework.boot.testsupport.web.servlet.DirtiesUrlFactories;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.Ssl.ClientAuth;
import org.springframework.boot.web.server.WebServer;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.StreamUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Base class for {@link ClientHttpRequestFactoryBuilder} tests.
 *
 * @param <T> The {@link ClientHttpRequestFactory} type
 * @author Phillip Webb
 * @author Andy Wilkinson
 */
@DirtiesUrlFactories
abstract class AbstractClientHttpRequestFactoryBuilderTests<T extends ClientHttpRequestFactory> {

	private final Class<T> requestFactoryType;

	private final ClientHttpRequestFactoryBuilder<T> builder;

	AbstractClientHttpRequestFactoryBuilderTests(Class<T> requestFactoryType,
			ClientHttpRequestFactoryBuilder<T> builder) {
		this.requestFactoryType = requestFactoryType;
		this.builder = builder;
	}

	@Test
	void buildReturnsRequestFactoryOfExpectedType() {
		T requestFactory = this.builder.build();
		assertThat(requestFactory).isInstanceOf(this.requestFactoryType);
	}

	@Test
	void buildWhenHasConnectTimeout() {
		ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
			.withConnectTimeout(Duration.ofSeconds(60));
		T requestFactory = this.builder.build(settings);
		assertThat(connectTimeout(requestFactory)).isEqualTo(Duration.ofSeconds(60).toMillis());
	}

	@Test
	void buildWhenHadReadTimeout() {
		ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
			.withReadTimeout(Duration.ofSeconds(120));
		T requestFactory = this.builder.build(settings);
		assertThat(readTimeout(requestFactory)).isEqualTo(Duration.ofSeconds(120).toMillis());
	}

	@ParameterizedTest
	@ValueSource(strings = { "GET", "POST" })
	void connectWithSslBundle(String httpMethod) throws Exception {
		TomcatServletWebServerFactory webServerFactory = new TomcatServletWebServerFactory(0);
		webServerFactory.setSsl(ssl());
		WebServer webServer = webServerFactory
			.getWebServer((context) -> context.addServlet("test", TestServlet.class).addMapping("/"));
		try {
			webServer.start();
			int port = webServer.getPort();
			URI uri = new URI("https://localhost:%s".formatted(port));
			ClientHttpRequestFactory insecureRequestFactory = this.builder.build();
			ClientHttpRequest insecureRequest = request(insecureRequestFactory, uri, httpMethod);
			assertThatExceptionOfType(SSLHandshakeException.class)
				.isThrownBy(() -> insecureRequest.execute().getBody());
			ClientHttpRequestFactory secureRequestFactory = this.builder
				.build(ClientHttpRequestFactorySettings.ofSslBundle(sslBundle()));
			ClientHttpRequest secureRequest = request(secureRequestFactory, uri, httpMethod);
			String secureResponse = StreamUtils.copyToString(secureRequest.execute().getBody(), StandardCharsets.UTF_8);
			assertThat(secureResponse).contains("Received " + httpMethod + " request to /");
		}
		finally {
			webServer.stop();
		}
	}

	private ClientHttpRequest request(ClientHttpRequestFactory factory, URI uri, String method) throws IOException {
		return factory.createRequest(uri, HttpMethod.valueOf(method));
	}

	private Ssl ssl() {
		Ssl ssl = new Ssl();
		ssl.setClientAuth(ClientAuth.NEED);
		ssl.setKeyPassword("password");
		ssl.setKeyStore("classpath:test.jks");
		ssl.setTrustStore("classpath:test.jks");
		return ssl;
	}

	protected final SslBundle sslBundle() {
		JksSslStoreDetails storeDetails = JksSslStoreDetails.forLocation("classpath:test.jks");
		JksSslStoreBundle stores = new JksSslStoreBundle(storeDetails, storeDetails);
		return SslBundle.of(stores, SslBundleKey.of("password"));
	}

	protected abstract long connectTimeout(T requestFactory);

	protected abstract long readTimeout(T requestFactory);

	public static class TestServlet extends HttpServlet {

		@Override
		public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
			res.getWriter().println("Received " + req.getMethod() + " request to " + req.getRequestURI());
		}

	}

}