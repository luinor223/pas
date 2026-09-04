{{- define "pas-common.deployment" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "pas-common.fullname" . }}
  labels: {{- include "pas-common.labels" . | nindent 4 }}
spec:
  replicas: {{ .Values.service.replicas | default 1 }}
  selector:
    matchLabels: {{- include "pas-common.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels: {{- include "pas-common.selectorLabels" . | nindent 8 }}
    spec:
      containers:
        - name: {{ .Values.service.name }}
          image: "{{ .Values.service.image.repository }}:{{ .Values.service.image.tag | default .Chart.AppVersion }}"
          imagePullPolicy: {{ .Values.service.image.pullPolicy | default "IfNotPresent" }}
          ports:
            - name: http
              containerPort: {{ .Values.service.httpPort }}
            {{- if .Values.service.grpcPort }}
            - name: grpc
              containerPort: {{ .Values.service.grpcPort }}
            {{- end }}
          env:
            {{- if .Values.service.database }}
            - name: DB_URL
              value: "jdbc:postgresql://{{ .Values.postgres.host }}:{{ .Values.postgres.port }}/{{ .Values.service.database.name }}"
            - name: DB_USER
              value: {{ .Values.service.database.user | quote }}
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: {{ include "pas-common.fullname" . }}-secret
                  key: db-password
            {{- end }}
            {{- if not .Values.service.disableRedis }}
            - name: REDIS_HOST
              value: {{ .Values.redis.host | quote }}
            - name: REDIS_PORT
              value: {{ .Values.redis.port | quote }}
            {{- end }}
            {{- if not .Values.service.disableKafka }}
            - name: KAFKA_BOOTSTRAP_SERVERS
              value: {{ .Values.kafka.bootstrapServers | quote }}
            {{- end }}
            {{- if .Values.service.signsJwt }}
            - name: JWT_ISSUER
              value: {{ .Values.jwt.issuer | quote }}
            - name: JWT_PRIVATE_KEY_PATH
              value: /keys/jwt-private.pem
            {{- end }}
            {{- range $k, $v := .Values.service.env }}
            - name: {{ $k }}
              value: {{ $v | quote }}
            {{- end }}
            {{- range $k, $secretKey := .Values.service.secretEnv }}
            - name: {{ $k }}
              valueFrom:
                secretKeyRef:
                  name: {{ include "pas-common.fullname" $ }}-secret
                  key: {{ $secretKey }}
            {{- end }}
          {{- if or .Values.service.signsJwt .Values.service.persistence }}
          volumeMounts:
            {{- if .Values.service.signsJwt }}
            - name: jwt-key
              mountPath: /keys
              readOnly: true
            {{- end }}
            {{- if .Values.service.persistence }}
            - name: data
              mountPath: {{ .Values.service.persistence.mountPath }}
            {{- end }}
          {{- end }}
          {{- $probe := .Values.service.probe | default "http" }}
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
          resources: {{- toYaml .Values.service.resources | nindent 12 }}
      {{- if or .Values.service.signsJwt .Values.service.persistence }}
      volumes:
        {{- if .Values.service.signsJwt }}
        - name: jwt-key
          secret:
            secretName: {{ .Values.jwt.privateKeySecret }}
        {{- end }}
        {{- if .Values.service.persistence }}
        - name: data
          persistentVolumeClaim:
            claimName: {{ include "pas-common.fullname" . }}-data
        {{- end }}
      {{- end }}
{{- end -}}
