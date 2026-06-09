package sh.vork.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.TokenBasedRememberMeServices;

/**
 * Spring Security configuration for custom login, page protection, and remember-me.
 * Uses DatabaseUserDetailsService for credential loading from MongoDB.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           RememberMeServices rememberMeServices,
                                           UserDetailsService userDetailsService) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/login/**").permitAll()
                .requestMatchers("/setup", "/api/setup/**").permitAll()
                .requestMatchers("/api/authorization/**").permitAll()
                .requestMatchers("/css/**", "/js/**", "/images/**", "/*.ico").permitAll()
                .requestMatchers("/packages/**").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                .requestMatchers("/input-form/**").permitAll()
                // ACME HTTP-01 challenge — must be publicly accessible on plain HTTP
                .requestMatchers("/.well-known/acme-challenge/**").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .usernameParameter("username")
                .passwordParameter("password")
                .defaultSuccessUrl("/index.html", false)
                .failureUrl("/login?error=true")
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout=true")
                .invalidateHttpSession(true)
                .clearAuthentication(true)
                .deleteCookies("VORK_REMEMBER_ME", "JSESSIONID")
                .permitAll()
            )
            .rememberMe(rememberMe -> rememberMe
                .rememberMeServices(rememberMeServices)
                .key("vork-remember-me-key-change-in-production")
            )
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/api/authorization/**", "/api/chat/**", "/ws/**", "/logout",
                        "/api/setup/**", "/api/system/**", "/api/ai/**", "/api/agents/**",
                        "/api/notifications/**", "/api/user/**", "/api/types/**",
                        "/api/transcription/**", "/api/ssl/**")
            )
            .sessionManagement(session -> session
                .sessionConcurrency(concurrency -> concurrency
                    .maximumSessions(5)
                    .expiredUrl("/login?expired=true")
                )
            );

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http,
                                                       UserDetailsService userDetailsService,
                                                       PasswordEncoder passwordEncoder) throws Exception {
        AuthenticationManagerBuilder authenticationManagerBuilder =
                http.getSharedObject(AuthenticationManagerBuilder.class);
        authenticationManagerBuilder
                .userDetailsService(userDetailsService)
                .passwordEncoder(passwordEncoder);
        return authenticationManagerBuilder.build();
    }

    @Bean
    public RememberMeServices rememberMeServices(UserDetailsService userDetailsService) {
        TokenBasedRememberMeServices rememberMeServices = new TokenBasedRememberMeServices(
                "vork-remember-me-key-change-in-production",
                userDetailsService);
        rememberMeServices.setTokenValiditySeconds(2_592_000);
        rememberMeServices.setCookieName("VORK_REMEMBER_ME");
        rememberMeServices.setUseSecureCookie(false);
        rememberMeServices.setAlwaysRemember(false);
        return rememberMeServices;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
