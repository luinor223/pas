// Package csrf is a Traefik local middleware: a double-submit CSRF guard for cookie-authenticated
// requests. On unsafe methods it requires the CSRF header to match the CSRF cookie, but only when
// an auth cookie is present, so header (Bearer) callers and pre-login requests pass untouched.
package csrf

import (
	"context"
	"crypto/subtle"
	"fmt"
	"net/http"
)

// Config is the plugin configuration (from the dynamic middleware definition).
type Config struct {
	CookieName      string   `json:"cookieName,omitempty"`
	HeaderName      string   `json:"headerName,omitempty"`
	AuthCookieNames []string `json:"authCookieNames,omitempty"`
}

// CreateConfig returns the default configuration.
func CreateConfig() *Config {
	return &Config{
		CookieName:      "pas_csrf",
		HeaderName:      "X-CSRF-Token",
		AuthCookieNames: []string{"pas_at", "pas_rt"},
	}
}

type csrf struct {
	next            http.Handler
	cookieName      string
	headerName      string
	authCookieNames []string
}

// New builds the middleware.
func New(_ context.Context, next http.Handler, config *Config, _ string) (http.Handler, error) {
	if config.CookieName == "" || config.HeaderName == "" {
		return nil, fmt.Errorf("csrf: cookieName and headerName are required")
	}
	return &csrf{
		next:            next,
		cookieName:      config.CookieName,
		headerName:      config.HeaderName,
		authCookieNames: config.AuthCookieNames,
	}, nil
}

func (c *csrf) ServeHTTP(rw http.ResponseWriter, req *http.Request) {
	if isSafe(req.Method) {
		c.next.ServeHTTP(rw, req)
		return
	}

	cookies := cookieValues(req)
	if !c.cookieAuthenticated(cookies) {
		c.next.ServeHTTP(rw, req)
		return
	}

	token := cookies[c.cookieName]
	header := req.Header.Get(c.headerName)
	if token == "" || header == "" ||
		subtle.ConstantTimeCompare([]byte(token), []byte(header)) != 1 {
		http.Error(rw, "Missing or invalid CSRF token", http.StatusForbidden)
		return
	}

	c.next.ServeHTTP(rw, req)
}

// cookieAuthenticated reports whether an ambient credential cookie rides the request; that is what
// exposes it to CSRF. Header (Bearer) callers carry none and are left alone.
func (c *csrf) cookieAuthenticated(cookies map[string]string) bool {
	for _, name := range c.authCookieNames {
		if cookies[name] != "" {
			return true
		}
	}
	return false
}

// cookieValues parses the request's Cookie header once into a name->value map.
func cookieValues(req *http.Request) map[string]string {
	list := req.Cookies()
	values := make(map[string]string, len(list))
	for _, ck := range list {
		values[ck.Name] = ck.Value
	}
	return values
}

func isSafe(method string) bool {
	switch method {
	case http.MethodGet, http.MethodHead, http.MethodOptions, http.MethodTrace:
		return true
	default:
		return false
	}
}
