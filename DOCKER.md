# Arranque reproducible y escenario de caída de IA

Guía integrada a partir del documento del compañero y conciliada con el Compose
publicado en `122e273`. Se conserva `compose.yaml` y su nombre `gridmind`;
`compose.override.yaml` añade el origen del navegador, el perfil prod y el proxy
nginx validados en Codespaces. Docker carga ambos archivos automáticamente.

## Requisitos y arranque local

- Docker Desktop iniciado (Linux containers), Compose v2.35 o posterior y Git.
- Repositorios `GridMind-backend`, `GridMind-frontend` y `GridMind-ia` en carpetas
  hermanas; alternativamente definir `FRONTEND_PATH` e `IA_PATH` en `.env`.
- Usar versiones actualizadas de los repositorios hermanos con sus Dockerfiles
  (`e114384` o posterior para frontend, `08761af` o posterior para IA).
  El frontend requiere su `package-lock.json`.
  El lock corregido de `GridMind-frontend` incluye `@emnapi/core` y
  `@emnapi/runtime`, omitidos en el recibido. Se debe usar ese cambio junto con
  este Compose: el Dockerfile del frontend ejecuta únicamente `npm ci`, sin
  regenerar el lock. Su fijación de Node 22.23.5 / npm 10.9.9 y los archivos
  `.nvmrc`/`packageManager` se gestionan y versionan en GridMind-frontend.
- Para el escenario, Bash, curl y Python 3 real (no el alias de Microsoft Store).
  En Windows usar WSL o Git Bash; para las pruebas Maven, JDK 17 o 21.

Desde `GridMind-backend`, en PowerShell:

```powershell
if (Test-Path .env) { throw '.env ya existe: conservar sus credenciales y revisar la configuracion antes de continuar.' }
Copy-Item .env.docker.example .env
$jwtBytes = New-Object byte[] 48
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($jwtBytes)
$jwtSecret = [Convert]::ToBase64String($jwtBytes)
(Get-Content .env) -replace '^JWT_SECRET=.*', "JWT_SECRET=$jwtSecret" | Set-Content .env
Remove-Variable jwtBytes, jwtSecret
# Editar POSTGRES_PASSWORD antes de continuar.
docker compose config --quiet
docker compose up --build -d
docker compose ps
Invoke-RestMethod http://localhost:5000/health
```

Para un entorno nuevo en Linux/Codespaces: `cp -n .env.docker.example .env`, generar `JWT_SECRET` con
`openssl rand -base64 48` y definir también una contraseña PostgreSQL.
No publicar `.env` ni capturas con secretos. No se necesitan Java, Maven, Node
o Python en el host para construir los contenedores.

| Servicio | URL local predeterminada | Uso |
|---|---|---|
| frontend | http://localhost:5173 | Navegador; nginx sirve SPA y proxy `/api/` |
| backend | http://localhost:8080/swagger-ui/index.html | Swagger y API directa |
| ia | http://localhost:5000/health | Comprobación del escenario |
| db | `localhost:5432` y `db:5432` dentro de Docker | Puerto del host configurable con `DB_PORT` |

Spring llama a `http://ia:5000/predict` por la red de Compose. La base se crea
vacía y Hibernate actualiza tablas; registrar usuario y crear dispositivo.
Los volúmenes `db-data` y `uploads` (prefijados por el proyecto Compose) conservan
base y facturas tras `docker compose down`. `docker compose down -v` los borra.

Antes de adoptar esta configuración sobre un despliegue existente, consultar
`docker compose ls` y `docker volume ls`. El nombre publicado es `gridmind`
(`gridmind_db-data` / `gridmind_uploads`). Si el despliegue anterior usaba otro
nombre, conservarlo en `.env` con `COMPOSE_PROJECT_NAME=nombre-anterior`;
por ejemplo, `gridmind-backend` para el ensayo local original. No cambiar
credenciales, recrear volúmenes ni sustituir `.env` para actualizar el código.

## Codespaces: URLs reales del navegador

Recorrido completo de registro, login, lecturas y actualización STOMP, con
comandos y criterios de evidencia: [comprobación manual en navegador](docs/codespaces-navegador.md).

Clonar los hermanos junto al backend dentro de `/workspaces` o configurar sus rutas.
Antes de construir, en la terminal del Codespace:

```bash
export FRONTEND_PORT=5173
export BROWSER_ORIGIN="https://${CODESPACE_NAME}-${FRONTEND_PORT}.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN}"
printf 'Navegador: %s\nAPI: %s/api/v1\nWebSocket: %s/api/v1/ws\n' \
  "$BROWSER_ORIGIN" "$BROWSER_ORIGIN" "${BROWSER_ORIGIN/https:/wss:}"
docker compose config --quiet
docker compose up --build -d
```

Reenviar el puerto 5173 desde **Ports** y abrir su dirección HTTPS. Comparar
esa dirección con `BROWSER_ORIGIN`; conservar el puerto privado. Si se cambia
el puerto, cambiar `FRONTEND_PORT` y `BROWSER_ORIGIN` y reconstruir frontend.
La variable se incorpora a `VITE_API_URL` durante el build, no al iniciar nginx.
Para persistirla entre terminales, escribir el valor resuelto en `.env`.

Se verificó el código del frontend: Axios añade `/api/v1/...` a `VITE_API_URL`,
y Dashboard transforma `http` en `ws` y añade `/api/v1/ws`. Con esta configuración:

| Recurso en DevTools | URL esperada |
|---|---|
| registro/login | `https://<codespace>-5173.<dominio>/api/v1/users/register` o `/login` |
| historial | `https://<codespace>-5173.<dominio>/api/v1/energy/<id>` |
| predicción | `https://<codespace>-5173.<dominio>/api/v1/analytics/forecast` |
| STOMP/WebSocket | `wss://<codespace>-5173.<dominio>/api/v1/ws` |

nginx reenvía `/api/` a `backend:8080` y permite Upgrade del WebSocket. El origen
exacto también llega a `FRONTEND_URL` para CORS. El navegador usa el puerto del
frontend como único origen, evitando autenticación entre dos puertos privados.
En el navegador remoto `localhost` apunta al equipo del usuario; los nombres
`backend` e `ia` solo se resuelven en Docker. El escenario ejecutado **dentro**
del Codespace sí usa `localhost:8080` y `localhost:5000` sin atravesar el túnel.
Para abrir Swagger, reenviar además 8080 y añadir `/swagger-ui/index.html` a su URL.

El formato de las URLs y las variables se contrastaron con la documentación
oficial de [puertos reenviados](https://docs.github.com/en/codespaces/developing-in-a-codespace/forwarding-ports-in-your-codespace)
y [variables de Codespaces](https://docs.github.com/en/codespaces/developing-in-a-codespace/default-environment-variables-for-your-codespace).
El recorrido en un navegador real de Codespaces se completó con el usuario:
HTTP/JSON de historial y forecast, handshake 101, mensajes STOMP y actualización
visible de 144 W con conexión nueva. Ver el [informe](docs/validacion-ia-caida.md)
para la falta de actualización inicial y la discrepancia de fechas observadas.
No cambiar visibilidad a pública para resolver este recorrido.

## Contrato comprobado en el backend

Todas las rutas siguientes empiezan por `/api/v1`. Los controladores actuales
devuelven **200** en estos casos, incluso el error controlado de predicción.

| Operación | Entrada/autenticación | Respuesta |
|---|---|---|
| `POST /users/register` | JSON `name`, `email`, `password`; sin JWT | Usuario JSON con `id`, `email`; sin contraseña |
| `POST /users/login` | JSON `email`, `password`; sin JWT | JSON `token`, `name` |
| `POST /devices` | Bearer JWT; JSON `name`, `esp32Id`, `type`, `powerRating` | Dispositivo JSON con `id`, `apiKey` generada |
| `POST /iot/telemetry` | JSON `apiKey`, `voltage`, `current`, `power`; sin JWT | Texto `Telemetría GridMind recibida con éxito` |
| `GET /energy/{deviceId}?size=20&sort=timestamp,desc` | Bearer JWT del dueño | Página JSON `content` y `totalElements` (o `page.totalElements`) |
| `GET /analytics/forecast` | Bearer JWT | JSON con `predicted_next_30_days`, o `error` |

Registro: nombre 2–100 caracteres (letras, espacios, guion/apóstrofe), email
válido, contraseña 8–128 caracteres. Dispositivo: nombre y `esp32Id` obligatorios;
`powerRating` no negativo. Telemetría: todas las medidas obligatorias y no negativas;
API key desconocida devuelve 403, datos inválidos 400. Historial requiere propiedad
del dispositivo. El frontend envía SHA-256 de la contraseña tanto en registro
como login; el script usa el mismo valor sin hash en ambas llamadas y BCrypt
lo almacena. Para acceder a ese usuario desde el frontend habría que respetar
su transformación; el usuario del script se reserva para esta prueba.

Fuentes: controladores `UserController`, `DeviceController`, `IotController`,
`EnergyConsumptionController`, `AnalyticsController`; DTO `RegisterUserRequest`,
modelo `Device`, `DeviceService`, `PredictiveService` y `ApiIntegrationTests`.
El `/health` real de Flask devuelve HTTP 200 y `status: IA Service is Operational`.

## Escenario D1

Con Compose operativo, desde este repositorio:

```bash
set -o pipefail
bash scripts/escenario-ia-caida.sh 2>&1 | tee escenario-ia-caida.log
```

El script lee los puertos resueltos de Compose (incluye `.env`). Se puede
sobrescribir `API` (base con `/api/v1`) e `IA_HEALTH_URL` (URL con `/health`).
Ambas deben apuntar al mismo despliegue de Compose que se va a detener.

1. Exige IA inicialmente operativa, registra usuario único y crea medidor.
2. Envía una lectura de 120 W y valida predicción HTTP 200, numérica y sin error.
3. Instala restauración por EXIT/INT/TERM/HUP antes de detener IA; exige éxito de
   `docker compose stop --timeout 10 ia` y verifica `State.Running=false`.
4. Envía tres lecturas de 130, 140 y 150 W. Cada una debe tener HTTP 200, cuerpo
   de éxito y duración medida por curl **menor o igual a 8 s**. Comprueba aumento
   exacto de tres registros y las cuatro potencias del dispositivo nuevo.
5. Exige forecast HTTP 200 con JSON `{"error":"El servicio de IA no responde."}`.
6. Arranca IA, espera hasta 60 s por `/health` HTTP 200 con JSON operativo y
   reintenta forecast hasta diez veces, sin reiniciar backend. Fallos e
   interrupciones posteriores a stop intentan restaurar IA y comprobar su salud.

Cada petición imprime estado HTTP, código curl y `time_total`. Hay timeout de
conexión de 3 s y total de 12 s para telemetría; el margen permite medir y
reportar un incumplimiento de 8 s. Otras llamadas tienen máximo 15 s y health
usa como máximo 5 s por intento dentro de su plazo. No se reintentan POST.
HTTP 4xx/5xx, JSON malformado, errores de transporte o plazo agotado fallan.
Telemetría se comprueba como texto porque ese es su contrato real.

**Alcance:** detener Flask prueba indisponibilidad inmediata del proceso. El fallo
de red puede manifestarse como rechazo, error DNS o timeout de conexión (esto
último se observó en la ejecución registrada). No demuestra el comportamiento ante un servicio lento o una
conexión bloqueada; eso requiere otro escenario. `PredictiveService` configura
3 s de conexión y 5 s de lectura, pero su suma no garantiza un tiempo total de
8 s incluyendo DNS, persistencia y otros trabajos; los 8 s siguen siendo el
criterio de aceptación medido. SIGKILL o pérdida del host no permiten ejecutar trap.
Los usuarios y lecturas de prueba persisten para revisión.

## Pruebas y diagnóstico

```bash
./mvnw test                # Windows: .\mvnw.cmd test
docker compose logs --since 5m backend ia
docker compose logs -f backend ia
docker compose restart backend
docker compose down       # conserva datos
```

Alternativa sin JDK/Maven en el host:

```bash
docker run --rm -v "$PWD:/workspace" -w /workspace maven:3.9-eclipse-temurin-21 mvn test
```

`ApiIntegrationTests` usa H2 y un mock de `PredictiveService`; no sustituye el
escenario Compose con Flask real. Ver [resultados observados](docs/validacion-ia-caida.md).

Si falta `JWT_SECRET` o `POSTGRES_PASSWORD`, Compose falla antes de arrancar.
Para puerto ocupado, cambiar `FRONTEND_PORT`, `BACKEND_PORT` o `IA_PORT`; si cambia
el del frontend, actualizar `BROWSER_ORIGIN` y reconstruir. Para contexto no
encontrado, revisar rutas hermanas. Si falta el pipe Docker en Windows, iniciar
Docker Desktop y verificar `docker info`. Si falla el dashboard, comprobar
Network y la URL incorporada en el build antes de volver a construir.
