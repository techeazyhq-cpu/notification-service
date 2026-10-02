{{/*
Copyright 2026 Vasantha Kumar. Licensed under the Apache License, Version 2.0.
@author Vasantha Kumar <vasantha.kumar@hotmail.com>
*/}}

{{/* Name of a component's objects: <release>-<component>, e.g. notification-client-api. */}}
{{- define "notification.componentName" -}}
{{- printf "%s-%s" .root.Release.Name .component | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "notification.selectorLabels" -}}
app.kubernetes.io/name: notification-service
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{- define "notification.labels" -}}
{{ include "notification.selectorLabels" . }}
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version }}
{{- end -}}

{{/* Every pod of this release, whatever its component; used by the default-deny and DNS policies. */}}
{{- define "notification.releaseSelector" -}}
app.kubernetes.io/name: notification-service
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "notification.image" -}}
{{- $tag := .root.Values.image.tag | default .root.Chart.AppVersion -}}
{{- printf "%s/%s%s:%s" .root.Values.image.registry .root.Values.image.repositoryPrefix .component $tag -}}
{{- end -}}

{{/* Non-root, no privilege escalation, no capabilities, read-only root filesystem (writable /tmp only). */}}
{{- define "notification.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: {{ .uid }}
runAsGroup: {{ .uid }}
fsGroup: {{ .uid }}
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "notification.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: [ALL]
{{- end -}}

{{/* Settings shared by client-api, admin-api and the dispatcher; secrets come from the existing Secret. */}}
{{- define "notification.serviceEnv" -}}
{{- $config := .Values.config -}}
- name: DB_URL
  value: {{ $config.database.url | quote }}
- name: DB_USER
  value: {{ $config.database.appUser | quote }}
- name: DB_PASSWORD
  valueFrom: {secretKeyRef: {name: {{ .Values.secrets.existingSecret }}, key: DB_PASSWORD}}
- name: REDIS_HOST
  value: {{ $config.redis.host | quote }}
- name: REDIS_PORT
  value: {{ $config.redis.port | quote }}
{{- if $config.redis.passwordFromSecret }}
- name: SPRING_DATA_REDIS_PASSWORD
  valueFrom: {secretKeyRef: {name: {{ .Values.secrets.existingSecret }}, key: REDIS_PASSWORD}}
{{- end }}
{{- with $config.redis.tlsTrustCertificateFile }}
- name: SPRING_DATA_REDIS_SSL_ENABLED
  value: "true"
- name: SPRING_DATA_REDIS_SSL_BUNDLE
  value: redis
- name: SPRING_SSL_BUNDLE_PEM_REDIS_TRUSTSTORE_CERTIFICATE
  value: {{ . | quote }}
{{- end }}
- name: PULSAR_URL
  value: {{ $config.pulsar.url | quote }}
- name: PULSAR_TLS_TRUST_CERTS_FILE
  value: {{ $config.pulsar.tlsTrustCertsFile | quote }}
- name: SECRETS_ENCRYPTION_KEY
  valueFrom: {secretKeyRef: {name: {{ .Values.secrets.existingSecret }}, key: SECRETS_ENCRYPTION_KEY}}
- name: DATA_ENCRYPTION_KEY
  valueFrom: {secretKeyRef: {name: {{ .Values.secrets.existingSecret }}, key: DATA_ENCRYPTION_KEY}}
- name: PROVIDER_TRUSTED_HOSTS
  value: {{ $config.providerTrustedHosts | quote }}
- name: PROVIDER_OTHER_PUBLIC_HOSTS_ALLOWED
  value: {{ $config.otherPublicProviderHostsAllowed | quote }}
- name: TRACING_SAMPLING_PROBABILITY
  value: {{ $config.tracingSamplingProbability | quote }}
- name: LOG_FORMAT
  value: {{ $config.logFormat | quote }}
{{- with $config.otlpTracingEndpoint }}
- name: MANAGEMENT_OTLP_TRACING_ENDPOINT
  value: {{ . | quote }}
{{- end }}
{{- range $name, $value := $config.extraEnv }}
- name: {{ $name }}
  value: {{ $value | quote }}
{{- end }}
{{- end -}}

{{/* Spring Boot probes on the management port (ADR-021); startup allows for a slow first schema check. */}}
{{- define "notification.javaProbes" -}}
startupProbe:
  httpGet: {path: /actuator/health/liveness, port: management}
  periodSeconds: 5
  failureThreshold: 36
livenessProbe:
  httpGet: {path: /actuator/health/liveness, port: management}
  periodSeconds: 10
  failureThreshold: 3
readinessProbe:
  httpGet: {path: /actuator/health/readiness, port: management}
  periodSeconds: 5
  failureThreshold: 3
{{- end -}}
