# Arranque reproducible con Docker

Levanta el *walking skeleton* completo en local con un solo comando:

| Servicio   | Origen                          | URL en el host                  |
|------------|---------------------------------|---------------------------------|
| `frontend` | repo `GridMind-frontend` (nginx) | http://localhost:5173           |
| `backend`  | este repo (Spring Boot)          | http://localhost:8080           |
| `ia`       | repo `GridMind-ia` (Flask + gunicorn) | http://localhost:5000/health |
| `db`       | imagen `postgres:16-alpine`      | `localhost:5432`                |

Dentro de la red de Docker, Spring Boot llama al predictor en `http://ia:5000/predict`
(`HTTP · POST /predict`), igual que en Railway lo hace por la red privada.

## Requisitos previos

- Docker Desktop con Docker Compose v2 (`docker compose version`). En Windows, con el backend WSL 2.
- Git.
- Puertos libres: 5173, 8080, 5000 y 5432. Si alguno está ocupado, cámbialo en `.env` (ver más abajo).
- No hace falta tener instalados Java, Maven, Node ni Python: todo se compila dentro de las imágenes.

## Pasos (Windows · PowerShell)

1. Clona los tres repositorios **en la misma carpeta**:

   ```powershell
   mkdir GridMind; cd GridMind
   git clone https://github.com/danilolarap/GridMind-backend.git
   git clone https://github.com/danilolarap/GridMind-frontend.git
   git clone https://github.com/danilolarap/GridMind-ia.git
   cd GridMind-backend
   ```

   Si los clonas en otra ruta, indica `FRONTEND_PATH` e `IA_PATH` en `.env`.

2. Crea el archivo `.env` a partir del ejemplo y genera `JWT_SECRET`:

   ```powershell
   Copy-Item .env.docker.example .env
   $bytes = New-Object byte[] 48
   [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
   $secret = [Convert]::ToBase64String($bytes)
   (Get-Content .env) -replace '^JWT_SECRET=.*', "JWT_SECRET=$secret" | Set-Content .env
   Remove-Variable bytes, secret
   ```

   Cambia también `POSTGRES_PASSWORD`. `.env` está en `.gitignore`: no lo subas ni lo muestres en capturas.

3. Construye y levanta todo:

   ```powershell
   docker compose up --build
   ```

   La primera vez tarda varios minutos (descarga de imágenes, dependencias de Maven, npm y pip).
   El orden de arranque es automático: `db` e `ia` deben estar *healthy* antes de que arranque `backend`.
   Para dejarlo en segundo plano usa `docker compose up --build -d` y luego `docker compose ps`.

4. Comprueba los servicios (en otra terminal):

   ```powershell
   docker compose ps
   Invoke-RestMethod http://localhost:5000/health
   ```

   Abre http://localhost:5173 (frontend) y http://localhost:8080/swagger-ui/index.html (API).

## Cómo se inicializa la base de datos

- El contenedor `db` crea la base `gridmind_db` y el usuario de `.env` en el primer arranque.
- Spring Boot crea y actualiza las tablas al iniciar (`spring.jpa.hibernate.ddl-auto: update`).
  No hay scripts SQL ni datos de ejemplo: la base empieza vacía y se registra un usuario desde el frontend.
- Los datos se guardan en el volumen `gridmind_db-data` y las imágenes de facturas en `gridmind_uploads`.
  Sobreviven a `docker compose down`.
- Para empezar desde cero: `docker compose down -v` (borra los volúmenes).

## Repetir el recorrido de predicción en local

Es el mismo recorrido de la guía de pruebas, cambiando la URL del backend:

1. En http://localhost:5173 regístrate, inicia sesión y crea el dispositivo `Medidor Demo`. Copia su API key.
2. Envía las tres lecturas con el bloque de PowerShell de la guía, usando
   `$baseUrl = 'http://localhost:8080'`.
3. Consulta el historial (`GET /api/v1/energy/{deviceId}?page=0&size=20&sort=timestamp,desc`):
   `totalElements` debe subir en 3, con potencias 120, 132 y 144 W.
4. Logs de la comunicación entre procesos:

   ```powershell
   docker compose logs backend | Select-String 'IA '
   docker compose logs ia | Select-String 'POST /predict'
   ```

   En Spring deben aparecer `Historial real de 3 registros`, `Enviando N puntos` e `IA RAW RESPONSE`.
   En Flask, `"POST /predict HTTP/1.1" 200`.
5. Recarga el dashboard: la tarjeta «Predicción 30 días» muestra el valor y la llamada `forecast`
   en DevTools devuelve `predicted_next_30_days` sin campo `error`.

## Variables

| Variable | Obligatoria | Uso |
|---|---|---|
| `POSTGRES_PASSWORD` | Sí | Contraseña de la base local. |
| `POSTGRES_USER` | No (`gridmind`) | Usuario de la base local. |
| `JWT_SECRET` | Sí | Firma de los JWT, al menos 32 caracteres. |
| `GEMINI_API_KEY`, `RESEND_API_KEY` | No | Análisis de facturas y correo de recuperación. Sin ellas esas funciones no responden. |
| `FRONTEND_PATH`, `IA_PATH` | No | Rutas a los otros repos (por defecto, `../GridMind-frontend` y `../GridMind-ia`). |
| `FRONTEND_PORT`, `BACKEND_PORT`, `IA_PORT`, `DB_PORT` | No | Puertos en el host. `FRONTEND_PORT` debe estar permitido por CORS (5173, 5174, 5175 o 3000). |

`VITE_API_URL` se fija en el build del frontend como `http://localhost:${BACKEND_PORT}`, porque es
el navegador del host quien llama al backend. Si cambias `BACKEND_PORT`, vuelve a ejecutar con `--build`.

## Comandos útiles

```powershell
docker compose logs -f backend ia   # seguir logs
docker compose restart backend      # reiniciar un servicio
docker compose down                 # parar (conserva datos)
docker compose down -v              # parar y borrar datos
```

## Problemas frecuentes

- **`JWT_SECRET` / `POSTGRES_PASSWORD` no definido**: Compose se detiene con ese mensaje. Revisa `.env`.
- **Puerto ocupado** (por ejemplo, un PostgreSQL local en 5432): cambia `DB_PORT` en `.env`.
- **`unable to prepare context` / ruta no encontrada**: los repos no están en carpetas hermanas; define `FRONTEND_PATH` e `IA_PATH`.
- **El frontend no conecta con el backend**: comprueba que abres el puerto de `FRONTEND_PORT` y que `backend` está arriba (`docker compose logs backend`).
