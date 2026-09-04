{{/*
Traefik Middleware CRDs for one service: strip /api/v1, rate limit, the RS256
jwt-auth plugin (validates pas_at, injects X-User-* headers), and an optional
docs strip. Mirrors the compose dynamic config.
*/}}
{{- define "pas-common.middlewares" -}}
{{- $name := .Values.service.name -}}
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: {{ $name }}-stripprefix
  labels: {{- include "pas-common.labels" . | nindent 4 }}
spec:
  stripPrefix:
    prefixes: ["/api/v1"]
{{- if .Values.service.rateLimit }}
---
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: {{ $name }}-ratelimit
  labels: {{- include "pas-common.labels" . | nindent 4 }}
spec:
  rateLimit:
    average: {{ .Values.service.rateLimit.average }}
    burst: {{ .Values.service.rateLimit.burst }}
{{- end }}
{{- if .Values.service.routes }}
---
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: {{ $name }}-jwt-auth
  labels: {{- include "pas-common.labels" . | nindent 4 }}
spec:
  plugin:
    jwt:
      cookieName: pas_at
      headerName: Authorization
      secret: |
{{ required "jwt.publicKey is required for protected routes" .Values.jwt.publicKey | indent 8 }}
      require:
        iss: {{ .Values.jwt.issuer }}
      headerMap:
        X-User-Id: sub
        X-Username: username
        X-Full-Name: full_name
        X-Department: department
        X-Roles: roles
      removeMissingHeaders: true
{{- end }}
{{- if .Values.service.docsPath }}
---
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: {{ $name }}-docs-strip
  labels: {{- include "pas-common.labels" . | nindent 4 }}
spec:
  stripPrefix:
    prefixes: ["{{ .Values.service.docsPath }}"]
{{- end }}
{{- end -}}
