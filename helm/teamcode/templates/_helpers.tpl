{{/*
Expand the name of the chart.
*/}}
{{- define "teamcode.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "teamcode.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "teamcode.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "teamcode.labels" -}}
helm.sh/chart: {{ include "teamcode.chart" . }}
{{ include "teamcode.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "teamcode.selectorLabels" -}}
app.kubernetes.io/name: {{ include "teamcode.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "teamcode.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "teamcode.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "teamcode.mysql.fullname" -}}
{{- $fullName := include "teamcode.fullname" . -}}
{{- printf "%s-mysql" ($fullName | trunc 57 | trimSuffix "-") -}}
{{- end -}}

{{- define "teamcode.databaseUrl" -}}
{{- if .Values.mysql.enabled -}}
{{- printf "jdbc:mysql://%s:3306/%s" (include "teamcode.mysql.fullname" .) .Values.mysql.database -}}
{{- else -}}
{{- required "Set mysql.jdbcUrl when mysql.enabled=false" .Values.mysql.jdbcUrl -}}
{{- end -}}
{{- end -}}

{{- define "teamcode.mysql.name" -}}
{{- printf "%s-mysql" (include "teamcode.name" . | trunc 57 | trimSuffix "-") -}}
{{- end -}}

{{/*
Size of the bundled MySQL's volume claim template. volumeClaimTemplates is immutable on an existing StatefulSet, so:
an explicit mysql.persistence.size is used as written; otherwise the size of the release's existing StatefulSet is kept
(read with lookup, https://helm.sh/docs/chart_template_guide/function_list/#lookup); otherwise (new installation, or
helm template / --dry-run=client, where lookup returns nothing) 30Gi. Takes a dict: explicit, existing (the StatefulSet
object or an empty dict).
*/}}
{{- define "teamcode.mysql.storageSize" -}}
{{- $size := "" -}}
{{- range ((.existing.spec | default dict).volumeClaimTemplates | default list) -}}
{{- if eq .metadata.name "data" -}}{{- $size = .spec.resources.requests.storage -}}{{- end -}}
{{- end -}}
{{- .explicit | default $size | default "30Gi" -}}
{{- end -}}

{{/*
The MCP stdio host (docs/mcp.md). Its pods carry their own name label, never teamcode.selectorLabels: the server's
Service, PodDisruptionBudget and the bundled MySQL's NetworkPolicy select pods by those, and must not match the host.
A release upgraded with --reuse-values from a chart without the `mcp` block has no .Values.mcp: nothing is rendered.
*/}}
{{- define "teamcode.mcp.enabled" -}}
{{- if dig "enabled" false (.Values.mcp | default dict) -}}true{{- end -}}
{{- end -}}

{{- define "teamcode.mcp.fullname" -}}
{{- printf "%s-mcp" (include "teamcode.fullname" . | trunc 59 | trimSuffix "-") -}}
{{- end -}}

{{- define "teamcode.mcp.name" -}}
{{- printf "%s-mcp" (include "teamcode.name" . | trunc 59 | trimSuffix "-") -}}
{{- end -}}

{{- define "teamcode.mcp.selectorLabels" -}}
app.kubernetes.io/name: {{ include "teamcode.mcp.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "teamcode.mcp.labels" -}}
helm.sh/chart: {{ include "teamcode.chart" . }}
{{ include "teamcode.mcp.selectorLabels" . }}
app.kubernetes.io/component: mcp
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/* Where the host's token is read: the operator's existingSecret, or the Secret the chart generates. */}}
{{- define "teamcode.mcp.tokenSecretName" -}}
{{- .Values.mcp.token.existingSecret | default (include "teamcode.mcp.fullname" .) -}}
{{- end -}}

{{- define "teamcode.mcp.tokenSecretKey" -}}
{{- if .Values.mcp.token.existingSecret -}}{{- .Values.mcp.token.key | default "token" -}}{{- else -}}token{{- end -}}
{{- end -}}

{{/*
The generated token, base64-encoded as Secret data: the value of the release's existing Secret when there is one (read
with lookup, so an upgrade keeps it), otherwise 48 random alphanumeric characters. Computed once per render and kept in
.Values.mcp (shared by every template of the chart), so the Secret and both checksum annotations see the same value.
lookup returns nothing under `helm template` and `--dry-run=client`: they show a new value every time.
*/}}
{{- define "teamcode.mcp.generatedToken" -}}
{{- if not (hasKey .Values.mcp "__generatedToken") -}}
{{- $existing := lookup "v1" "Secret" .Release.Namespace (include "teamcode.mcp.fullname" .) -}}
{{- $data := (($existing | default dict).data | default dict) -}}
{{- $_ := set .Values.mcp "__generatedToken" (get $data "token" | default (randAlphaNum 48 | b64enc)) -}}
{{- end -}}
{{- index .Values.mcp "__generatedToken" -}}
{{- end -}}

{{/*
Changes when the token changes, so the server and host pods roll together after a rotation (annotation
checksum/mcp-token). For an existingSecret, the hash of its current value when lookup can read it.
*/}}
{{- define "teamcode.mcp.tokenChecksum" -}}
{{- if .Values.mcp.token.existingSecret -}}
{{- $existing := lookup "v1" "Secret" .Release.Namespace .Values.mcp.token.existingSecret -}}
{{- $data := (($existing | default dict).data | default dict) -}}
{{- get $data (include "teamcode.mcp.tokenSecretKey" .) | default (printf "%s/%s" .Values.mcp.token.existingSecret (include "teamcode.mcp.tokenSecretKey" .)) | sha256sum -}}
{{- else -}}
{{- include "teamcode.mcp.generatedToken" . | sha256sum -}}
{{- end -}}
{{- end -}}

{{- define "teamcode.mcp.url" -}}
{{- printf "http://%s:%d" (include "teamcode.mcp.fullname" .) 8090 -}}
{{- end -}}
