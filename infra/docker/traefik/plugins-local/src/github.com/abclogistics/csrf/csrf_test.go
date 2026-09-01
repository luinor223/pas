package csrf

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func handler(t *testing.T) http.Handler {
	t.Helper()
	next := http.HandlerFunc(func(rw http.ResponseWriter, _ *http.Request) { rw.WriteHeader(http.StatusOK) })
	h, err := New(context.Background(), next, CreateConfig(), "csrf")
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	return h
}

func run(t *testing.T, method string, cookies map[string]string, header string) int {
	t.Helper()
	req := httptest.NewRequest(method, "/api/v1/contracts", nil)
	for name, value := range cookies {
		req.AddCookie(&http.Cookie{Name: name, Value: value})
	}
	if header != "" {
		req.Header.Set("X-CSRF-Token", header)
	}
	rw := httptest.NewRecorder()
	handler(t).ServeHTTP(rw, req)
	return rw.Code
}

func TestSafeMethodPasses(t *testing.T) {
	if code := run(t, http.MethodGet, map[string]string{"pas_at": "x"}, ""); code != http.StatusOK {
		t.Fatalf("GET with auth cookie should pass, got %d", code)
	}
}

func TestNoAuthCookiePasses(t *testing.T) {
	if code := run(t, http.MethodPost, nil, ""); code != http.StatusOK {
		t.Fatalf("POST without auth cookie (Bearer/login) should pass, got %d", code)
	}
}

func TestCookieAuthWithMatchingTokenPasses(t *testing.T) {
	cookies := map[string]string{"pas_at": "x", "pas_csrf": "tok"}
	if code := run(t, http.MethodPost, cookies, "tok"); code != http.StatusOK {
		t.Fatalf("matching double-submit should pass, got %d", code)
	}
}

func TestCookieAuthMissingHeaderRejected(t *testing.T) {
	cookies := map[string]string{"pas_at": "x", "pas_csrf": "tok"}
	if code := run(t, http.MethodPost, cookies, ""); code != http.StatusForbidden {
		t.Fatalf("missing header should be 403, got %d", code)
	}
}

func TestCookieAuthMismatchRejected(t *testing.T) {
	cookies := map[string]string{"pas_at": "x", "pas_csrf": "tok"}
	if code := run(t, http.MethodPost, cookies, "nope"); code != http.StatusForbidden {
		t.Fatalf("mismatched header should be 403, got %d", code)
	}
}

func TestRefreshCookieCounts(t *testing.T) {
	// pas_rt-only request (the refresh path) must still require the token.
	cookies := map[string]string{"pas_rt": "x", "pas_csrf": "tok"}
	if code := run(t, http.MethodPost, cookies, "nope"); code != http.StatusForbidden {
		t.Fatalf("refresh with mismatched header should be 403, got %d", code)
	}
}
