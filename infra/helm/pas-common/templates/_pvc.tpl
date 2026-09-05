{{/* PersistentVolumeClaim for services that store files locally (e.g. contract attachments). */}}
{{- define "pas-common.pvc" -}}
{{- if .Values.service.persistence -}}
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: {{ include "pas-common.fullname" . }}-data
  labels: {{- include "pas-common.labels" . | nindent 4 }}
spec:
  accessModes: ["ReadWriteOnce"]
  resources:
    requests:
      storage: {{ .Values.service.persistence.size | default "1Gi" }}
  {{- with .Values.service.persistence.storageClass }}
  storageClassName: {{ . }}
  {{- end }}
{{- end -}}
{{- end -}}
