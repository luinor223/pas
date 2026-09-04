{{/*
Per-service Secret: the DB password (when the service has a database) plus any
extra secret values it needs (referenced by service.secretEnv). For production,
point at an externally managed Secret of the same name instead.
*/}}
{{- define "pas-common.secret" -}}
{{- if or .Values.service.database .Values.service.secrets -}}
apiVersion: v1
kind: Secret
metadata:
  name: {{ include "pas-common.fullname" . }}-secret
  labels: {{- include "pas-common.labels" . | nindent 4 }}
type: Opaque
stringData:
  {{- if .Values.service.database }}
  db-password: {{ required "service.database.password is required" .Values.service.database.password | quote }}
  {{- end }}
  {{- range $k, $v := .Values.service.secrets }}
  {{ $k }}: {{ $v | quote }}
  {{- end }}
{{- end -}}
{{- end -}}
