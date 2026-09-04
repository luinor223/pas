{{/*
Traefik IngressRoute for one service: protected routes (rate limit + jwt-auth +
strip /api/v1), public routes (no jwt), and optional docs.
*/}}
{{- define "pas-common.ingressroute" -}}
{{- $name := .Values.service.name -}}
{{- $svc := include "pas-common.fullname" . -}}
{{- $port := .Values.service.httpPort -}}
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: {{ $svc }}
  labels: {{- include "pas-common.labels" . | nindent 4 }}
spec:
  entryPoints:
    - {{ .Values.ingress.entryPoint | default "web" }}
  routes:
    {{- if .Values.service.routes }}
    - match: {{ include "pas-common.matchRule" .Values.service.routes }}
      kind: Rule
      middlewares:
        - name: {{ $name }}-ratelimit
        - name: {{ $name }}-jwt-auth
        - name: {{ $name }}-stripprefix
      services:
        - name: {{ $svc }}
          port: {{ $port }}
    {{- end }}
    {{- if .Values.service.publicRoutes }}
    - match: {{ include "pas-common.matchRule" .Values.service.publicRoutes }}
      kind: Rule
      middlewares:
        - name: {{ $name }}-ratelimit
        - name: {{ $name }}-stripprefix
      services:
        - name: {{ $svc }}
          port: {{ $port }}
    {{- end }}
    {{- if .Values.service.docsPath }}
    - match: PathPrefix(`{{ .Values.service.docsPath }}`)
      kind: Rule
      middlewares:
        - name: {{ $name }}-docs-strip
      services:
        - name: {{ $svc }}
          port: {{ $port }}
    {{- end }}
{{- end -}}
