package harness;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import com.codesage.CodeSageApplication;
import com.codesage.auth.UserAccountService;
import com.codesage.github.GitHubClient;
import com.codesage.github.GitHubModels;
import com.codesage.github.GitHubTokenService;
import com.codesage.repository.GitRepository;
import com.codesage.repository.GitRepositoryRepository;
import com.codesage.security.CodeSageOAuth2UserService;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * LOCAL DEVELOPMENT HARNESS - lives in test sources and is never packaged into the application jar.
 *
 * Starts the real backend on an embedded PostgreSQL and adds a /harness/login endpoint that signs in
 * a local user WITHOUT GitHub OAuth, so the UI can be exercised (and screenshotted) on a machine
 * that has no OAuth App configured. GitHub calls are made anonymously, so only public repositories
 * can be reviewed. Run with:
 *
 *   mvn -q test-compile exec:java -Dexec.mainClass=harness.LocalHarness -Dexec.classpathScope=test
 */
public final class LocalHarness {

    private LocalHarness() {
    }

    public static void main(String[] args) throws IOException {
        EmbeddedPostgres pg = EmbeddedPostgres.builder().start();
        Map<String, Object> props = new HashMap<>();
        props.put("spring.datasource.url", pg.getJdbcUrl("postgres", "postgres"));
        props.put("spring.datasource.username", "postgres");
        props.put("spring.datasource.password", "");
        props.put("spring.main.allow-bean-definition-overriding", "true");
        props.put("codesage.token-encryption-key", "local-harness-only");
        props.put("codesage.public-url", "http://localhost:5173");
        props.put("codesage.frontend-url", "http://localhost:5173");
        props.put("codesage.review.max-files", "40");
        props.put("codesage.rate-limit.enabled", "false");
        // Placeholder OAuth registration so the sign-in UI renders as configured (the harness logs in via /harness/login).
        props.put("codesage.github.client-id", System.getenv().getOrDefault("GITHUB_CLIENT_ID", "harness-demo-client"));
        props.put("codesage.github.client-secret", System.getenv().getOrDefault("GITHUB_CLIENT_SECRET", "harness-demo-client"));
        SpringApplication app = new SpringApplication(CodeSageApplication.class, HarnessConfig.class);
        // System properties outrank application.yml defaults.
        props.forEach((k, v) -> System.setProperty(k, v.toString()));
        app.run(args);
    }

    @Configuration
    static class HarnessConfig {

        /** Anonymous GitHub access (public data only). */
        @Bean
        GitHubTokenService gitHubTokenService() {
            return new GitHubTokenService(null, null) {
                @Override
                public String requireToken(UUID userId) {
                    return "";
                }
            };
        }

        @Bean
        FilterRegistrationBean<OncePerRequestFilter> harnessLogin(org.springframework.context.ApplicationContext ctx) {
            OncePerRequestFilter filter = new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                        throws IOException, jakarta.servlet.ServletException {
                    if (!"/harness/login".equals(req.getRequestURI())) {
                        chain.doFilter(req, res);
                        return;
                    }
                    UserAccountService accounts = ctx.getBean(UserAccountService.class);
                    GitRepositoryRepository repositories = ctx.getBean(GitRepositoryRepository.class);
                    GitHubClient github = ctx.getBean(GitHubClient.class);
                    String login = req.getParameter("login");
                    long githubId = Long.parseLong(req.getParameter("githubId"));
                    String name = req.getParameter("name");
                    UUID userId = accounts.upsertOnLogin(githubId, login, name, null,
                            null, "anonymous", "public data (harness)");
                    String[] repos = req.getParameter("repos") == null ? new String[0] : req.getParameter("repos").split(",");
                    for (String fullName : repos) {
                        String[] parts = fullName.split("/");
                        GitHubModels.Repo repo = github.getRepository("", parts[0], parts[1]);
                        GitRepository row = repositories.findByUserIdAndGithubId(userId, repo.id()).orElseGet(GitRepository::new);
                        row.setUserId(userId);
                        row.setGithubId(repo.id());
                        row.setOwner(repo.owner().login());
                        row.setName(repo.name());
                        row.setFullName(repo.fullName());
                        row.setDescription(repo.description());
                        row.setLanguage(repo.language());
                        row.setPrivateRepo(repo.isPrivate());
                        row.setDefaultBranch(repo.defaultBranch());
                        row.setHtmlUrl(repo.htmlUrl());
                        row.setPushedAt(repo.pushedAt());
                        // far-future sync time: the harness cannot call /user/repos anonymously
                        row.setLastSyncedAt(Instant.now().plus(365, ChronoUnit.DAYS));
                        repositories.save(row);
                    }
                    Map<String, Object> attrs = new HashMap<>();
                    attrs.put("id", githubId);
                    attrs.put("login", login);
                    attrs.put(CodeSageOAuth2UserService.USER_ID_ATTRIBUTE, userId.toString());
                    var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")), attrs, "id");
                    var auth = new OAuth2AuthenticationToken(user, user.getAuthorities(), "github");
                    req.getSession(true).setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                            new SecurityContextImpl(auth));
                    res.sendRedirect("http://localhost:5173/dashboard");
                }
            };
            FilterRegistrationBean<OncePerRequestFilter> bean = new FilterRegistrationBean<>(filter);
            bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return bean;
        }
    }
}
