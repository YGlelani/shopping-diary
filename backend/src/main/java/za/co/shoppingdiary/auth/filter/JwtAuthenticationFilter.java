package za.co.shoppingdiary.auth.filter;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import za.co.shoppingdiary.auth.UserPrincipal;
import za.co.shoppingdiary.auth.service.JwtService;
import za.co.shoppingdiary.user.entity.Role;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwt;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        try {
            Claims claims = jwt.parse(header.substring(7));
            Long userId    = Long.valueOf(claims.getSubject());
            String email   = claims.get("email", String.class);
            Role role      = Role.valueOf(claims.get("role", String.class));

            UserPrincipal principal = new UserPrincipal(userId, email, role);
            var auth = new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (Exception ex) {
            // Invalid/expired token — leave unauthenticated; downstream will 401/403.
            SecurityContextHolder.clearContext();
        }

        chain.doFilter(request, response);
    }
}