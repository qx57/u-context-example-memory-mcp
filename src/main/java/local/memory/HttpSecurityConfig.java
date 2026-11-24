package local.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration
class HttpSecurityConfig {
    static final String OPERATOR = "operator";

    private final String operatorToken;
    private final ObjectMapper mapper;

    HttpSecurityConfig(@Value("${MCP_MEMORY_OPERATOR_TOKEN:}") String operatorToken, ObjectMapper mapper) {
        if (!operatorToken.matches("[!-~]{24,256}")) {
            throw new IllegalArgumentException("Set MCP_MEMORY_OPERATOR_TOKEN to a local secret (24–256 ASCII characters)");
        }
        this.operatorToken = operatorToken;
        this.mapper = mapper;
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> localHttpOnly() {
        var filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                int port = request.getLocalPort();
                String host = "127.0.0.1" + (port == 80 ? "" : ":" + port);
                String origin = request.getHeader("Origin");
                boolean allowed = host.equals(request.getHeader("Host"))
                        && (origin == null || ("http://" + host).equals(origin));
                if (allowed) {
                    chain.doFilter(request, response);
                } else {
                    sendError(response, ErrorCode.FORBIDDEN);
                }
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        byte[] expected = ("Bearer " + operatorToken).getBytes(StandardCharsets.US_ASCII);
        var bearer = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                String header = request.getHeader("Authorization");
                if (header != null && MessageDigest.isEqual(expected, header.getBytes(StandardCharsets.UTF_8))) {
                    var authentication = UsernamePasswordAuthenticationToken.authenticated(
                            OPERATOR, null, AuthorityUtils.createAuthorityList("ROLE_OPERATOR"));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
                chain.doFilter(request, response);
            }
        };
        return http.securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, ex) -> {
                            res.setHeader("WWW-Authenticate", "Bearer");
                            sendError(res, ErrorCode.UNAUTHENTICATED);
                        })
                        .accessDeniedHandler((req, res, ex) -> sendError(res, ErrorCode.FORBIDDEN)))
                .addFilterBefore(bearer, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests.anyRequest().hasRole("OPERATOR"))
                .build();
    }

    @Bean
    PasswordEncoder operatorPasswordEncoder() {
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    InMemoryUserDetailsManager operatorUsers(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(User.withUsername(OPERATOR)
                .password(encoder.encode(operatorToken)).roles("OPERATOR").build());
    }

    @Bean
    SecurityFilterChain browserSecurity(HttpSecurity http, InMemoryUserDetailsManager users,
                                        PasswordEncoder encoder) throws Exception {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return http.authenticationProvider(provider)
                .authorizeHttpRequests(requests -> requests.requestMatchers("/health", "/login", "/ui.css", "/error")
                        .permitAll().anyRequest().hasRole("OPERATOR"))
                .formLogin(login -> login.loginPage("/login").defaultSuccessUrl("/ui/memories", true))
                .logout(logout -> logout.logoutSuccessUrl("/login"))
                .build();
    }

    private void sendError(HttpServletResponse response, ErrorCode code) throws IOException {
        response.setStatus(code.status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), new MemoryError(code).body());
    }
}
