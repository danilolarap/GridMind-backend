# GridMind Backend

API de gestion de energia con Java 17 o superior, Spring Boot y PostgreSQL.

## Ejecutar

1. Crea la base de datos PostgreSQL `gridmind_db`.
2. Configura las variables de entorno de `.env.example`. Spring Boot no carga archivos `.env` automaticamente.
3. Define `SPRING_DATASOURCE_PASSWORD` y `JWT_SECRET` (secreto aleatorio de al menos 32 bytes).
4. Ejecuta `.\mvnw.cmd spring-boot:run` en Windows o `./mvnw spring-boot:run` en Linux/macOS.

Swagger: http://localhost:8080/swagger-ui/index.html

`GEMINI_API_KEY` habilita el analisis de facturas y `RESEND_API_KEY` el correo de recuperacion. Sin ellas no se prestan esas funciones. `IA_SERVICE_URL` apunta al microservicio de predicciones, mantenido en el repositorio separado `GridMind-ia`.

Para produccion configura `SPRING_PROFILES_ACTIVE=prod`, las tres variables de PostgreSQL, `JWT_SECRET`, `FRONTEND_URL` y las claves de las integraciones utilizadas. El contenedor escucha en `PORT` (8080 por defecto). Conserva `uploads/` en un volumen persistente para no perder las imagenes de facturas.

## Verificar

`.\mvnw.cmd clean verify` (Windows) o `./mvnw clean verify` (Linux/macOS).

Las pruebas usan el perfil test y H2 en memoria: no necesitan PostgreSQL ni claves externas. H2 no sustituye una validacion del despliegue contra PostgreSQL.

## Limites actuales

El estado de los reles se mantiene en memoria. Los canales WebSocket son publicos y compartidos; requieren autenticacion y aislamiento por usuario antes de utilizarse con datos privados en produccion.

## Docker (esqueleto completo)

`compose.yaml` y su override levantan frontend, backend, predictor y PostgreSQL con `docker compose up --build`.
Requisitos, variables, escenario de caida de IA y Codespaces: ver [DOCKER.md](DOCKER.md).
Los [resultados de validacion](docs/validacion-ia-caida.md) distinguen pruebas realizadas y pendientes.
