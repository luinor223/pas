{{/*
Workload/Service name. Kept as "<name>-service" so in-cluster gRPC peers
(e.g. IDENTITY_GRPC_HOST=identity-service) resolve to this Service's DNS.
*/}}
{{- define "pas-common.fullname" -}}
{{- printf "%s-service" .Values.service.name -}}
{{- end -}}

{{- define "pas-common.labels" -}}
app.kubernetes.io/name: {{ .Values.service.name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: pas
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "pas-common.selectorLabels" -}}
app.kubernetes.io/name: {{ .Values.service.name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* Build a Traefik match rule "PathPrefix(`a`) || PathPrefix(`b`)" from a list. */}}
{{- define "pas-common.matchRule" -}}
{{- $rules := list -}}
{{- range . -}}
{{- $rules = append $rules (printf "PathPrefix(`%s`)" .) -}}
{{- end -}}
{{- join " || " $rules -}}
{{- end -}}
