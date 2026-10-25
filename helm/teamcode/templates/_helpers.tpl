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
