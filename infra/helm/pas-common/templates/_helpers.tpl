{{/*
Workload/Service name. Defaults to "<name>-service" so in-cluster gRPC peers
(e.g. IDENTITY_GRPC_HOST=identity-service) resolve to this Service's DNS;
service.fullnameOverride sets it verbatim for peers referenced without the
suffix (web, esign-mock-provider).
*/}}
{{- define "pas-common.fullname" -}}
{{- .Values.service.fullnameOverride | default (printf "%s-service" .Values.service.name) -}}
{{- end -}}

{{/*
Name of the externally provisioned Secret the Deployment reads db-password and
secretEnv keys from. Defaults to "<fullname>-secret"; override with
service.secretName. The chart never creates this Secret: provision it out of
band from Secret Manager / Vault via the External Secrets Operator.
*/}}
{{- define "pas-common.secretName" -}}
{{- .Values.service.secretName | default (printf "%s-secret" (include "pas-common.fullname" .)) -}}
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
