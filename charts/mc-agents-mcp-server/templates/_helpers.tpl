{{- define "mc-agents-mcp-server.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mc-agents-mcp-server.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "mc-agents-mcp-server.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "mc-agents-mcp-server.labels" -}}
helm.sh/chart: {{ include "mc-agents-mcp-server.chart" . }}
{{ include "mc-agents-mcp-server.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "mc-agents-mcp-server.selectorLabels" -}}
app.kubernetes.io/name: {{ include "mc-agents-mcp-server.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "mc-agents-mcp-server.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "mc-agents-mcp-server.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "mc-agents-mcp-server.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
{{- if not $tag -}}
{{- fail "image.tag is empty and Chart.appVersion is unset, so there is no image to run" -}}
{{- end -}}
{{- printf "%s/%s:%s" .Values.image.registry .Values.image.repository $tag -}}
{{- end -}}

{{- define "mc-agents-mcp-server.validate" -}}
{{- if gt (int .Values.replicaCount) 1 -}}
{{- fail "replicaCount > 1: a bot exists on the one pod it dialled, and any agent may address it by name, so a second replica answers about bots it cannot reach. Sticky sessions do not help -- what has to stay put is the bot, not the caller." -}}
{{- end -}}
{{- if and .Values.auth.enabled (not .Values.auth.token) (not .Values.auth.existingSecret) -}}
{{- fail "auth.enabled is true but neither auth.token nor auth.existingSecret is set. Generating one here would produce a different value on every render, which leaves a GitOps application permanently out of sync." -}}
{{- end -}}
{{- end -}}

{{- define "mc-agents-mcp-server.secretName" -}}
{{- default (printf "%s-auth" (include "mc-agents-mcp-server.fullname" .)) .Values.auth.existingSecret -}}
{{- end -}}

{{/*
Namespace labels a scraper reaches the MCP port from, as YAML for a namespaceSelector's matchLabels.
Empty when nothing is let in, so the caller can leave the rule out: an empty namespaceSelector
would open the port to every namespace, which is what the rule used to do.
*/}}
{{- define "mc-agents-mcp-server.metricsFrom" -}}
{{- if .Values.networkPolicy.metricsFrom -}}
{{- toYaml .Values.networkPolicy.metricsFrom -}}
{{- else if and .Values.metrics.serviceMonitor.enabled .Values.metrics.serviceMonitor.namespace -}}
kubernetes.io/metadata.name: {{ .Values.metrics.serviceMonitor.namespace | quote }}
{{- end -}}
{{- end -}}
