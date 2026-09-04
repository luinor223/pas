{{- define "pas-common.deployment" -}}
{{- $svc := .Values.service -}}
{{- $fullname := include "pas-common.fullname" . -}}
{{- $secret := include "pas-common.secretName" . -}}
{{- $signsJwt := $svc.signsJwt -}}
{{- $persistence := $svc.persistence -}}
{{- $probe := $svc.probe | default "http" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ $fullname }}
  labels: {{- include "pas-common.labels" . | nindent 4 }}
spec:
  replicas: {{ $svc.replicas | default 1 }}
  selector:
    matchLabels: {{- include "pas-common.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels: {{- include "pas-common.selectorLabels" . | nindent 8 }}
    spec:
      containers:
        - name: {{ $svc.name }}
          image: "{{ $svc.image.repository }}:{{ $svc.image.tag | default .Chart.AppVersion }}"
          imagePullPolicy: {{ $svc.image.pullPolicy | default "IfNotPresent" }}
          ports:
            - name: http
              containerPort: {{ $svc.httpPort }}
            {{- if $svc.grpcPort }}
            - name: grpc
              containerPort: {{ $svc.grpcPort }}
            {{- end }}
          env:
            {{- if $svc.database }}
            - name: DB_URL
              value: "jdbc:postgresql://{{ .Values.postgres.host }}:{{ .Values.postgres.port }}/{{ $svc.database.name }}"
            - name: DB_USER
              value: {{ $svc.database.user | quote }}
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ $secret }}
                  key: db-password
            {{- end }}
            {{- if not $svc.disableRedis }}
            - name: REDIS_HOST
              value: {{ .Values.redis.host | quote }}
            - name: REDIS_PORT
              value: {{ .Values.redis.port | quote }}
            {{- end }}
            {{- if not $svc.disableKafka }}
            - name: KAFKA_BOOTSTRAP_SERVERS
              value: {{ .Values.kafka.bootstrapServers | quote }}
            {{- end }}
            {{- if $signsJwt }}
            - name: JWT_ISSUER
              value: {{ .Values.jwt.issuer | quote }}
            - name: JWT_PRIVATE_KEY_PATH
              value: /keys/jwt-private.pem
            {{- end }}
            {{- range $k, $v := $svc.env }}
            - name: {{ $k }}
              value: {{ $v | quote }}
            {{- end }}
            {{- range $k, $secretKey := $svc.secretEnv }}
            - name: {{ $k }}
              valueFrom:
                secretKeyRef:
                  name: {{ $secret }}
                  key: {{ $secretKey }}
            {{- end }}
          {{- if or $signsJwt $persistence }}
          volumeMounts:
            {{- if $signsJwt }}
            - name: jwt-key
              mountPath: /keys
              readOnly: true
            {{- end }}
            {{- if $persistence }}
            - name: data
              mountPath: {{ $persistence.mountPath }}
            {{- end }}
          {{- end }}
          {{- if eq $probe "http" }}
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
            initialDelaySeconds: 20
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
            initialDelaySeconds: 15
            periodSeconds: 10
          {{- else if eq $probe "tcp" }}
          livenessProbe:
            tcpSocket:
              port: http
            initialDelaySeconds: 10
          readinessProbe:
            tcpSocket:
              port: http
            initialDelaySeconds: 5
          {{- end }}
          resources: {{- toYaml $svc.resources | nindent 12 }}
      {{- if or $signsJwt $persistence }}
      volumes:
        {{- if $signsJwt }}
        - name: jwt-key
          secret:
            secretName: {{ .Values.jwt.privateKeySecret }}
        {{- end }}
        {{- if $persistence }}
        - name: data
          persistentVolumeClaim:
            claimName: {{ $fullname }}-data
        {{- end }}
      {{- end }}
{{- end -}}
