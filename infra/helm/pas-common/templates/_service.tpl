{{- define "pas-common.service" -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "pas-common.fullname" . }}
  labels: {{- include "pas-common.labels" . | nindent 4 }}
spec:
  selector: {{- include "pas-common.selectorLabels" . | nindent 4 }}
  ports:
    - name: http
      port: {{ .Values.service.httpPort }}
      targetPort: http
    {{- if .Values.service.grpcPort }}
    - name: grpc
      port: {{ .Values.service.grpcPort }}
      targetPort: grpc
    {{- end }}
{{- end -}}
