# User Service - Docker Setup

## Overview
The User Service is containerized using a multi-stage Dockerfile that keeps the final image lean (~150-180MB) while ensuring fast builds with proper layer caching.

## Docker Build

### Build the image locally
```bash
docker build -t user-service:latest .
```

### Build with custom tag (for registry)
```bash
docker build -t your-registry.azurecr.io/stagepass/user-service:1.0.0 .
```

## Docker Compose (Local Development)

To run the entire stack locally with PostgreSQL, Kafka, Zookeeper, and Eureka:

### Prerequisites
Set environment variables for OAuth2 credentials:
```bash
export GOOGLE_CLIENT_ID=your-google-client-id
export GOOGLE_CLIENT_SECRET=your-google-client-secret
export GITHUB_CLIENT_ID=your-github-client-id
export GITHUB_CLIENT_SECRET=your-github-client-secret
```

### Start all services
```bash
docker-compose up -d
```

### Check service status
```bash
docker-compose ps
```

### View logs
```bash
docker-compose logs -f user-service
```

### Stop all services
```bash
docker-compose down
```

### Clean up volumes
```bash
docker-compose down -v
```

## Container Details

### Multi-stage Build
**Stage 1 (Builder)**
- Image: `maven:3.9.0-eclipse-temurin-17`
- Builds JAR from source
- Downloads dependencies
- Skips tests for faster builds

**Stage 2 (Runtime)**
- Image: `eclipse-temurin:17-jre`
- ~150-180MB final size
- Runs as non-root user (`appuser:1000`)
- Includes health check
- Exposes port 8081

### Security Features
- Non-root user execution
- Health checks enabled
- Minimal attack surface

## Environment Variables

Required for container startup:
- `DB_URL` - PostgreSQL connection URL
- `DB_USERNAME` - Database user
- `DB_PASSWORD` - Database password
- `KAFKA_BOOTSTRAP_SERVERS` - Kafka broker address
- `JWT_SECRET` - JWT signing key (min 32 chars)
- `JWT_EXPIRATION` - Token expiry in seconds
- `GOOGLE_CLIENT_ID` - Google OAuth2 client ID
- `GOOGLE_CLIENT_SECRET` - Google OAuth2 client secret
- `GITHUB_CLIENT_ID` - GitHub OAuth2 client ID
- `GITHUB_CLIENT_SECRET` - GitHub OAuth2 client secret
- `EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE` - Eureka server URL

## Kubernetes Deployment

### Create deployment manifest (example)
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: user-service
spec:
  replicas: 2
  selector:
    matchLabels:
      app: user-service
  template:
    metadata:
      labels:
        app: user-service
    spec:
      containers:
      - name: user-service
        image: your-registry.azurecr.io/stagepass/user-service:1.0.0
        ports:
        - containerPort: 8081
        env:
        - name: DB_URL
          valueFrom:
            secretKeyRef:
              name: user-service-secrets
              key: db-url
        # ... other environment variables
        livenessProbe:
          httpGet:
            path: /actuator/health
            port: 8081
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health
            port: 8081
          initialDelaySeconds: 5
          periodSeconds: 5
```

### Deploy to Kubernetes
```bash
kubectl apply -f deployment.yaml
kubectl apply -f service.yaml
```

## Image Size Optimization

The multi-stage approach reduces image size by:
1. **Build stage**: Compiling in a full JDK environment
2. **Runtime stage**: Using only JRE (no compiler/build tools)
3. **Alpine variants**: Using slim images where available
4. **Layer caching**: Copying pom.xml separately to cache dependencies

Final image: **~150-180MB** (vs ~500MB+ with single-stage)

## Push to Registry

```bash
docker tag user-service:latest your-registry.azurecr.io/stagepass/user-service:latest
docker push your-registry.azurecr.io/stagepass/user-service:latest
```

## Troubleshooting

### Container exits immediately
Check logs: `docker logs user-service`
Common causes:
- Missing environment variables
- Database connection refused
- Invalid JWT_SECRET

### Health check failing
Ensure:
- Spring Actuator is enabled
- Port 8081 is accessible
- Application started successfully

### High memory usage
Adjust JVM options in Dockerfile:
```dockerfile
ENTRYPOINT ["java", "-Xmx512m", "-Xms256m", "-jar", "app.jar"]
```

## Notes

- Gateway routes to this service via **Eureka by service name**, not by port
- Port 8081 is internal to the container; mapping is handled by docker-compose/Kubernetes
- Each microservice gets a unique port for local development

