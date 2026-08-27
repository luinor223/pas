package com.abclogistics.pas.contract;

import com.abclogistics.pas.contract.controller.AttachmentController;
import com.abclogistics.pas.contract.controller.CustomerController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI document is the published contract (registry §5.1), so a path it advertises and no
 * controller serves is a broken promise, and a controller route it does not mention is an
 * undocumented one. This test compares the two directly.
 *
 * <p>Scoped to the surfaces Phase B items 1 and 4 own — customers and attachments. Items 5-14 add
 * the contract/addendum actions and should widen {@link #COVERED_PREFIXES} as they land, which is
 * the point: an unimplemented documented path stays visible instead of being silently tolerated.
 *
 * <p>No Spring context — reflection over the controller classes, so it runs without Docker.
 */
class ApiSurfaceConsistencyTest {

    private static final List<String> COVERED_PREFIXES = List.of("/customers", "/attachments");

    private static final List<Class<?>> CONTROLLERS =
            List.of(CustomerController.class, AttachmentController.class);

    @Test
    void everyDocumentedRouteUnderTheseSurfacesIsServed() {
        assertThat(documentedRoutes()).isSubsetOf(implementedRoutes());
    }

    @Test
    void everyImplementedRouteUnderTheseSurfacesIsDocumented() {
        assertThat(implementedRoutes()).isSubsetOf(documentedRoutes());
    }

    @Test
    void theComparisonIsActuallyLookingAtSomething() {
        // A silent regex change that matched nothing would make both tests above pass trivially.
        assertThat(implementedRoutes()).contains(
                "GET /customers", "POST /customers/{id}/suspend",
                "GET /attachments/{id}", "DELETE /attachments/{id}");
    }

    // ---- the two sides -------------------------------------------------------------------

    private static final Pattern PATH_KEY = Pattern.compile("^ {2}(/\\S*):\\s*$");
    private static final Pattern VERB_KEY = Pattern.compile("^ {4}(get|post|put|patch|delete):\\s*$");

    /** Parses the `paths:` tree: two-space-indented `/path:` keys, four-space-indented verbs. */
    private static Set<String> documentedRoutes() {
        Set<String> routes = new TreeSet<>();
        String path = null;
        boolean insidePaths = false;
        for (String line : readOpenApi().split("\n", -1)) {
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) {
                insidePaths = line.startsWith("paths:"); // any other top-level key ends the tree
                path = null;
                continue;
            }
            if (!insidePaths) {
                continue;
            }
            Matcher pathKey = PATH_KEY.matcher(line);
            if (pathKey.matches()) {
                path = pathKey.group(1);
                continue;
            }
            Matcher verb = VERB_KEY.matcher(line);
            if (path != null && verb.matches() && covered(path)) {
                routes.add(verb.group(1).toUpperCase() + " " + path);
            }
        }
        return routes;
    }

    private static Set<String> implementedRoutes() {
        Set<String> routes = new TreeSet<>();
        for (Class<?> controller : CONTROLLERS) {
            RequestMapping base = controller.getAnnotation(RequestMapping.class);
            String prefix = base == null || base.value().length == 0 ? "" : base.value()[0];
            for (Method method : controller.getDeclaredMethods()) {
                for (String route : routesOf(method)) {
                    String[] parts = route.split(" ", 2);
                    String full = normalize(prefix + parts[1]);
                    if (covered(full)) {
                        routes.add(parts[0] + " " + full);
                    }
                }
            }
        }
        return routes;
    }

    private static Set<String> routesOf(Method method) {
        Set<String> routes = new LinkedHashSet<>();
        GetMapping get = method.getAnnotation(GetMapping.class);
        if (get != null) routes.addAll(expand("GET", get.value()));
        PostMapping post = method.getAnnotation(PostMapping.class);
        if (post != null) routes.addAll(expand("POST", post.value()));
        PutMapping put = method.getAnnotation(PutMapping.class);
        if (put != null) routes.addAll(expand("PUT", put.value()));
        PatchMapping patch = method.getAnnotation(PatchMapping.class);
        if (patch != null) routes.addAll(expand("PATCH", patch.value()));
        DeleteMapping delete = method.getAnnotation(DeleteMapping.class);
        if (delete != null) routes.addAll(expand("DELETE", delete.value()));
        return routes;
    }

    private static List<String> expand(String verb, String[] paths) {
        if (paths.length == 0) {
            return List.of(verb + " ");
        }
        return java.util.Arrays.stream(paths).map(p -> verb + " " + p).toList();
    }

    /** Strips a trailing slash so `@RequestMapping("/customers")` + `@GetMapping` is `/customers`. */
    private static String normalize(String path) {
        return path.length() > 1 && path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    }

    private static boolean covered(String path) {
        return COVERED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private static String readOpenApi() {
        Path base = Path.of(System.getProperty("user.dir"));
        String relative = "services/contract/src/main/resources/openapi.yaml";
        Path p = base.resolve("src/main/resources/openapi.yaml");
        if (!Files.exists(p)) p = base.resolve(relative);
        if (!Files.exists(p)) p = base.resolve("../../" + relative);
        try {
            return Files.readString(p);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
