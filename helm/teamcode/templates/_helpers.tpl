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
With --set mcp.enabled=true on top, the block holds that key alone: the render fails with what to run instead, rather
than deploy a host from empty settings.
*/}}
{{- define "teamcode.mcp.enabled" -}}
{{- $mcp := .Values.mcp | default dict -}}
{{- if dig "enabled" false $mcp -}}
{{- if not (and (hasKey $mcp "image") (hasKey $mcp "resources")) -}}
{{- fail "mcp.enabled is set but the rest of the mcp block is missing: the release was upgraded with --reuse-values from a chart without it. Run helm upgrade with --reset-then-reuse-values (or your values file) instead" -}}
{{- end -}}
true
{{- end -}}
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

{{/* The operator's Secret holding the token, or empty when the chart generates it. */}}
{{- define "teamcode.mcp.existingSecret" -}}
{{- (.Values.mcp.token | default dict).existingSecret | default "" -}}
{{- end -}}

{{/* Where the host's token is read: the operator's existingSecret, or the Secret the chart generates. */}}
{{- define "teamcode.mcp.tokenSecretName" -}}
{{- include "teamcode.mcp.existingSecret" . | default (include "teamcode.mcp.fullname" .) -}}
{{- end -}}

{{- define "teamcode.mcp.tokenSecretKey" -}}
{{- if include "teamcode.mcp.existingSecret" . -}}{{- (.Values.mcp.token | default dict).key | default "token" -}}{{- else -}}token{{- end -}}
{{- end -}}

{{/*
The token to store, base64-encoded as Secret data, from the release's existing Secret (an object or an empty dict):
its value when it has one, so an upgrade keeps it; otherwise 48 new random alphanumeric characters.
*/}}
{{- define "teamcode.mcp.tokenData" -}}
{{- get ((.existing | default dict).data | default dict) "token" | default (randAlphaNum 48 | b64enc) -}}
{{- end -}}

{{/*
The generated token, read back with lookup (https://helm.sh/docs/chart_template_guide/function_list/#lookup).
Computed once per render and kept in .Values.mcp (shared by every template of the chart), so the Secret and both
checksum annotations see the same value. lookup returns nothing under `helm template` and `--dry-run=client`: they
show a new value every time.
*/}}
{{- define "teamcode.mcp.generatedToken" -}}
{{- if not (hasKey .Values.mcp "__generatedToken") -}}
{{- $existing := lookup "v1" "Secret" .Release.Namespace (include "teamcode.mcp.fullname" .) -}}
{{- $_ := set .Values.mcp "__generatedToken" (include "teamcode.mcp.tokenData" (dict "existing" $existing)) -}}
{{- end -}}
{{- index .Values.mcp "__generatedToken" -}}
{{- end -}}

{{/*
Changes when the token changes, so the server and host pods roll together after a rotation (annotation
checksum/mcp-token). For an existingSecret, the hash of its current value when lookup can read it; rendered outside
the cluster it cannot change, and a rotation needs `kubectl rollout restart` of both Deployments.
*/}}
{{- define "teamcode.mcp.tokenChecksum" -}}
{{- $secret := include "teamcode.mcp.existingSecret" . -}}
{{- if $secret -}}
{{- $key := include "teamcode.mcp.tokenSecretKey" . -}}
{{- $existing := lookup "v1" "Secret" .Release.Namespace $secret -}}
{{- get (($existing | default dict).data | default dict) $key | default (printf "%s/%s" $secret $key) | sha256sum -}}
{{- else -}}
{{- include "teamcode.mcp.generatedToken" . | sha256sum -}}
{{- end -}}
{{- end -}}

{{- define "teamcode.mcp.url" -}}
{{- printf "http://%s:%d" (include "teamcode.mcp.fullname" .) 8090 -}}
{{- end -}}

{{/*
Checks one CIDR of mcp.networkPolicy.blockedCidrs and returns its family ("4" or "6"): an IPv4 address of four
decimal octets up to 255 with a prefix of 1 to 32, or an IPv6 address with a prefix of 1 to 128.
*/}}
{{- define "teamcode.mcp.cidrFamily" -}}
{{- $parts := splitList "/" . -}}
{{- $invalid := printf "mcp.networkPolicy.blockedCidrs takes IPv4 or IPv6 CIDRs narrower than a whole address family: %s" . -}}
{{- if or (ne (len $parts) 2) (not (regexMatch "^[0-9]{1,3}$" (index $parts 1 | default "x"))) -}}{{- fail $invalid -}}{{- end -}}
{{- $prefix := atoi (index $parts 1) -}}
{{- if regexMatch "^[0-9]{1,3}(\\.[0-9]{1,3}){3}$" (index $parts 0) -}}
{{- range splitList "." (index $parts 0) -}}{{- if gt (atoi .) 255 -}}{{- fail $invalid -}}{{- end -}}{{- end -}}
{{- if or (lt $prefix 1) (gt $prefix 32) -}}{{- fail $invalid -}}{{- end -}}
4
{{- else if and (regexMatch "^[0-9A-Fa-f:]+$" (index $parts 0)) (contains ":" (index $parts 0)) -}}
{{- if or (lt $prefix 1) (gt $prefix 128) -}}{{- fail $invalid -}}{{- end -}}
6
{{- else -}}
{{- fail $invalid -}}
{{- end -}}
{{- end -}}

{{/*
Refuses an mcp.networkPolicy.extraEgress rule that would reach link-local addresses (169.254.0.0/16, where cloud
metadata answers, and fe80::/10), like the cloud session policy: a rule needs `to`, and an ipBlock inside a link-local
range, or containing one without excepting it, fails the render.
*/}}
{{- define "teamcode.mcp.checkExtraEgress" -}}
{{- range . -}}
{{- if not .to -}}{{- fail "Each mcp.networkPolicy.extraEgress rule must name its destinations in `to` (a rule without it reaches every address, link-local included)" -}}{{- end -}}
{{- range .to -}}
{{- with .ipBlock -}}
{{- $cidr := .cidr | default "" -}}
{{- $except := .except | default list -}}
{{- $parts := splitList "/" $cidr -}}
{{- $invalid := printf "mcp.networkPolicy.extraEgress may not reach link-local addresses (169.254.0.0/16, fe80::/10): %s" $cidr -}}
{{- if regexMatch "^[0-9]{1,3}(\\.[0-9]{1,3}){3}/[0-9]{1,2}$" $cidr -}}
{{- $octets := splitList "." (index $parts 0) -}}
{{- $prefix := atoi (index $parts 1) -}}
{{- $address := add (mul (atoi (index $octets 0)) 16777216) (mul (atoi (index $octets 1)) 65536) (mul (atoi (index $octets 2)) 256) (atoi (index $octets 3)) -}}
{{- $size := 1 -}}
{{- range until (sub 32 $prefix | int) -}}{{- $size = mul $size 2 -}}{{- end -}}
{{- $start := sub $address (mod $address $size) -}}
{{- $end := sub (add $start $size) 1 -}}
{{- /* 169.254.0.0 and 169.254.255.255 */ -}}
{{- if and (le $start 2851995648) (ge $end 2852061183) -}}
{{- if not (or (has "169.254.0.0/16" $except) (has "169.254.0.0/15" $except) (has "168.0.0.0/7" $except) (has "160.0.0.0/4" $except) (has "128.0.0.0/1" $except)) -}}{{- fail $invalid -}}{{- end -}}
{{- else if and (le $start 2852061183) (ge $end 2851995648) -}}
{{- fail $invalid -}}
{{- end -}}
{{- else if regexMatch "^[0-9A-Fa-f:]*:[0-9A-Fa-f:]*/[0-9]{1,3}$" $cidr -}}
{{- /* Only the first 16 bits matter for fe80::/10: the first group, read as hexadecimal ("" before "::" is 0). */ -}}
{{- $prefix := atoi (index $parts 1) -}}
{{- $digits := dict "0" 0 "1" 1 "2" 2 "3" 3 "4" 4 "5" 5 "6" 6 "7" 7 "8" 8 "9" 9 "a" 10 "b" 11 "c" 12 "d" 13 "e" 14 "f" 15 -}}
{{- $group := 0 -}}
{{- range splitList "" (first (splitList ":" (lower (index $parts 0)))) -}}{{- $group = add (mul $group 16) (get $digits .) -}}{{- end -}}
{{- $size := 1 -}}
{{- range until (sub 16 (min $prefix 16) | int) -}}{{- $size = mul $size 2 -}}{{- end -}}
{{- $start := sub $group (mod $group $size) -}}
{{- $end := sub (add $start $size) 1 -}}
{{- /* fe80 to febf */ -}}
{{- if and (le $start 65152) (ge $end 65215) (le $prefix 10) -}}
{{- if not (or (has "fe80::/10" $except) (has "fe80::/9" $except) (has "fe00::/8" $except) (has "fe00::/7" $except)) -}}{{- fail $invalid -}}{{- end -}}
{{- else if and (le $start 65215) (ge $end 65152) -}}
{{- fail $invalid -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
