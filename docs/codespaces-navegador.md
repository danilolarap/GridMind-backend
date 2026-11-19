# Comprobación manual en un navegador real de Codespaces

Estado: **recorrido principal ejecutado con el usuario en un navegador real**.
Registro, login, historial, forecast y actualización visual por WebSocket con
conexión nueva quedaron comprobados. Ver [resultados e incidencias](validacion-ia-caida.md):
los primeros envíos no actualizaron el monitor y ese comportamiento sigue pendiente
de diagnóstico. Las instrucciones siguientes sirven para repetir la comprobación.

### Variante del Codespace utilizado

El Codespace partió de backend `122e273`, frontend `e114384` e IA `08761af`.
Esos commits ya traían Dockerfiles y un Compose con `name: gridmind`. Se compararon
con las fuentes revisadas antes de adaptar su configuración. Se conservó ese
nombre y se añadió un **compose.override.yaml**, cargado automáticamente:

```yaml
services:
  backend:
    environment:
      SPRING_PROFILES_ACTIVE: prod
      FRONTEND_URL: ${BROWSER_ORIGIN:?Falta BROWSER_ORIGIN en .env}
  frontend:
    build:
      args:
        VITE_API_URL: ${BROWSER_ORIGIN:?Falta BROWSER_ORIGIN en .env}
    volumes:
      - ./docker/frontend.conf:/etc/nginx/conf.d/default.conf:ro
```

El montaje utiliza [la configuración nginx revisada](../docker/frontend.conf).
Se fijó la imagen del Dockerfile de frontend al digest Node 22.23.5 / npm 10.9.9
utilizado en esta integración y se registraron `.nvmrc` y `packageManager`.
No sustituir el Compose publicado por el de otra base sin revisar antes el
nombre de proyecto y los volúmenes: cambiar el nombre puede seleccionar otros
volúmenes y aparentar pérdida de datos. Antes de preparar un entorno existente,
consultar `docker compose ls`, `docker ps -a` y `docker volume ls`; conservar
su `.env` y credenciales. En la sesión observada esos tres listados estaban vacíos.

El cierre del backend incorpora esta variante sobre `122e273`: conserva el
Compose publicado y añade el override. El override final permite localhost como
valor local predeterminado; para Codespaces se debe definir BROWSER_ORIGIN como
en el recorrido. Si una pila anterior utilizaba otro nombre de proyecto, fijar
COMPOSE_PROJECT_NAME en su `.env` antes de arrancar, para conservar sus volúmenes.

## 1. Preparar el Codespace y construir

Se necesitan los cambios de esta integración en ambos repositorios: backend y
frontend. IA no requiere cambios. Los tres deben estar en carpetas hermanas
o tener sus rutas configuradas en `.env`. Ejecutar desde GridMind-backend:

```bash
cd /workspaces/GridMind-backend
test -d ../GridMind-frontend && test -d ../GridMind-ia
test -f .env || cp .env.docker.example .env
python3 - <<'PY'
from pathlib import Path
import re, secrets
p = Path('.env')
s = p.read_text()
for key in ('JWT_SECRET', 'POSTGRES_PASSWORD'):
    if re.search(rf'^{key}=\s*$', s, flags=re.M):
        s = re.sub(rf'^{key}=\s*$', lambda _: key + '=' + secrets.token_urlsafe(48), s, flags=re.M)
p.write_text(s)
PY
export FRONTEND_PORT=5173 BACKEND_PORT=8080 IA_PORT=5000
export BROWSER_ORIGIN="https://${CODESPACE_NAME:?Ejecutar dentro de Codespaces}-${FRONTEND_PORT}.${GITHUB_CODESPACES_PORT_FORWARDING_DOMAIN:?Falta el dominio de reenvio}"
docker compose config --quiet
docker compose up --build -d
docker compose ps
printf 'Abrir: %s\nWebSocket esperado: %s/api/v1/ws\n' \
  "$BROWSER_ORIGIN" "${BROWSER_ORIGIN/https:/wss:}"
```

No cambiar contraseñas existentes de una base ya inicializada. El bloque solo
completa valores vacíos; para un Codespace nuevo basta la plantilla. Comprobar
que `db` e `ia` aparecen healthy y backend/frontend están Up. Esperar a Spring:

```bash
ready=0
for attempt in {1..30}; do
  if curl --silent --show-error --fail --connect-timeout 2 --max-time 5 \
    http://localhost:8080/v3/api-docs -o /dev/null; then
    ready=1
    break
  fi
  sleep 2
done
test "$ready" -eq 1 || { echo 'Backend no disponible; revisar docker compose logs backend'; false; }
```

`BROWSER_ORIGIN` es una variable de build del frontend y de CORS del backend.
Mantenerla exportada al ejecutar Compose en esa terminal. Si cambia el nombre
del Codespace o el puerto, volver a calcularla y reconstruir. No ejecutar Vite
simultáneamente en 5173: en este recorrido ese puerto pertenece a nginx.

## 2. Reenviar un único puerto y abrir la página

En **Ports / Puertos** del editor del Codespace:

1. Añadir el puerto **5173** con **Forward a Port / Reenviar un puerto**.
2. Mantener visibilidad **Private / Privado** y protocolo interno **HTTP**,
   porque nginx escucha HTTP. La dirección pública del túnel usa **HTTPS**.
3. Abrir **Open in Browser / Abrir en navegador** y autenticarse con GitHub si
   lo solicita. Comparar la dirección real con el valor impreso de `BROWSER_ORIGIN`.

No hace falta reenviar 8080, 5000 ni PostgreSQL para usar la aplicación.
El navegador debe ver `https://<codespace>-5173.<dominio>` y llamar a `/api/v1/...`
en ese mismo origen. La conexión de Spring a IA sigue dentro de Docker.
Formato y configuración del túnel: [documentación oficial de GitHub](https://docs.github.com/en/codespaces/developing-in-a-codespace/forwarding-ports-in-your-codespace).

Abrir DevTools **antes** del registro: Network con **Preserve log / Conservar
registro**, pestaña Console y filtro WS disponible. Capturar solo metadatos y
datos de prueba: las respuestas de login/dispositivo contienen JWT y API key.

## 3. Registro, login y dispositivo

1. Abrir `<origen>/register`. Registrar un usuario nuevo, por ejemplo nombre
   `Prueba Codespaces`, correo único `codespaces-<fecha>@gridmind.test` y contraseña
   de al menos ocho caracteres. Confirmar `POST /api/v1/users/register` HTTP 200.
2. La UI redirige a `/login`. Iniciar sesión con ese mismo correo y contraseña:
   `POST /api/v1/users/login` HTTP 200, seguido del dashboard `/` y consultas
   autenticadas correctas. No reutilizar el usuario creado por el escenario Bash:
   la UI transforma contraseñas con SHA-256 y ese script no lo hace.
3. Ir a `/devices`, abrir **Nuevo Dispositivo** y crear `Medidor Codespaces`,
   tipo enchufe, potencia nominal **150 W**, ESP32 ID único de hasta 20 caracteres
   (por ejemplo `cs-<epoch>`). Confirmar `POST /api/v1/devices` HTTP 200.
4. Anotar el `id` y `esp32Id` de la respuesta. Copiar la API key desde la fila del
   dispositivo para la terminal; no incluirla en las evidencias compartidas.

Antes de enviar la primera lectura, el forecast de ese usuario puede devolver
HTTP 200 con `error: No hay suficiente historial para predecir`. Es esperado
para una cuenta nueva. Después de recibir telemetría debe haber un valor numérico.

## 4. Abrir dashboard y comprobar la suscripción

Volver a `/` y mantenerlo abierto. En Network → **WS**, seleccionar:

```text
wss://<codespace>-5173.<dominio>/api/v1/ws
```

Exigir handshake **101 Switching Protocols** y frames STOMP:

- Cliente `CONNECT`, servidor `CONNECTED`.
- Suscripciones a `/topic/energy`, `/topic/forecast` y `/topic/alerts`.

La tarjeta **Monitor en Tiempo Real** debe mostrar **EN VIVO** y la tabla
**Lecturas en Tiempo Real**, **CONECTADO**. El texto fijo `ESTADO EN VIVO` del
encabezado general, por sí solo, no demuestra conexión.

## 5. Enviar lecturas sin recargar el dashboard

En la terminal **del Codespace**, con el navegador ya conectado, pegar la API key
cuando la solicite. El envío usa localhost del Codespace, sin atravesar el túnel:

```bash
read -rsp 'API key del Medidor Codespaces: ' APIKEY
printf '\n'
for pair in '120 1.0' '132 1.1' '144 1.2'; do
  read -r power current <<< "$pair"
  printf '{"apiKey":"%s","voltage":120,"current":%s,"power":%s}' \
    "$APIKEY" "$current" "$power" |
    curl --silent --show-error --fail-with-body --connect-timeout 3 --max-time 12 \
      -H 'Content-Type: application/json' --data-binary @- \
      -w '\nHTTP %{http_code}; tiempo %{time_total}s\n' \
      http://localhost:8080/api/v1/iot/telemetry || break
  sleep 3
done
unset APIKEY
```

Cada envío debe responder HTTP **200** y texto `Telemetría GridMind recibida con
éxito`. Sin refrescar ni navegar, comprobar en el navegador:

1. Llegan tres frames `MESSAGE` con destino `/topic/energy`, `esp32Id` correcto,
   potencias **120, 132 y 144**, voltaje 120 y las corrientes enviadas.
2. La potencia visible cambia hasta **144.0 W** y la tabla añade las lecturas.
   Consumo/gráfica se actualizan a partir de esos mensajes.
3. Llegan mensajes de `/topic/forecast` con `predicted_next_30_days` numérico y
   sin `error`. La tarjeta **Predicción 30 días** refleja ese valor en kWh,
   redondeado a cuatro decimales. No es obligatorio que cambie con cada lectura:
   el predictor agrega historial; comprobar concordancia con el payload recibido.

El dashboard aplica forecast directamente desde STOMP; no emite necesariamente
un GET forecast por cada lectura. Un handshake 101 sin frames ni cambio visual
no completa esta comprobación.

## 6. Confirmar persistencia y forecast HTTP desde el navegador

Después de capturar la actualización en vivo, ejecutar este bloque en Console,
sustituyendo `DEVICE_ID` por el id anotado. Usa la sesión existente sin imprimir
el token:

```javascript
const deviceId = DEVICE_ID;
const checks = await Promise.all([
  `/api/v1/energy/${deviceId}?size=20&sort=timestamp,desc`,
  '/api/v1/analytics/forecast'
].map(async path => {
  const response = await fetch(path, {
    headers: { Authorization: `Bearer ${localStorage.getItem('gridmind_token')}` }
  });
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
  return { path, status: response.status, body: await response.json() };
}));
console.log(checks);
```

Ambos GET deben responder **200**. Para el dispositivo nuevo, historial con
`totalElements: 3` (o `page.totalElements`), las tres potencias correctas y su id.
Forecast con valor numérico finito, sin `error`. Si se repitieron los envíos,
comparar el incremento respecto al total previo, no exigir total 3.

## Evidencia a recopilar al repetir el recorrido

Registrar fecha/hora y commits de los tres repositorios junto con:

| Evidencia del navegador real | Criterio de aprobación |
|---|---|
| Dirección abierta y puerto privado en Ports | HTTPS de ese Codespace, puerto 5173; mismo origen para API |
| Registro/login y navegación autenticada | HTTP 200 y acceso efectivo al dashboard |
| Dispositivo y lecturas persistidas | ID/ESP32 coincidentes, aumento de tres y potencias correctas |
| Forecast HTTP | HTTP 200, JSON numérico sin error después de las lecturas |
| WebSocket y frames STOMP | URL WSS correcta, 101, CONNECTED, SUBSCRIBE y MESSAGE de energy/forecast |
| Captura o grabación sin recargar | 120 → 132 → 144 W y tarjeta forecast coherente con los mensajes |
| Console/Network | Sin errores de CORS, mixed content, autenticación inesperada o WebSocket |

La ejecución guiada ya está registrada en `docs/validacion-ia-caida.md`, incluida
su evidencia pendiente. Al repetirla, añadir una nueva ejecución sin sobrescribir
los tiempos D1 locales ni atribuirles las mediciones del navegador/Codespace.
