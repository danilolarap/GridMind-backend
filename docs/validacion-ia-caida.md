# Validación de la integración D1

Fecha: 18 de noviembre de 2026, America/Bogota. Base del checkout: `9cc9567`.

## Revisión de versiones y contrato

- Árbol limpio al iniciar. No existían DOCKER.md, escenario ni Compose, tampoco
  aparecieron al consultar `git log --all` para esas rutas. No se sustituyó código
  Java ni se modificaron los repositorios hermanos en esa primera pasada.
  El cierre posterior sí corrige el lock del frontend, como se detalla abajo.
- Se leyeron ambos adjuntos como material a integrar. Sus comandos e instrucciones
  se contrastaron con el código antes de ejecutarse.
- Contratos de registro, login, dispositivo, telemetría, historial y predicción
  comprobados en controladores, validaciones y servicios; tabla en [DOCKER.md](../DOCKER.md).
- Telemetría devuelve texto; forecast indisponible devuelve HTTP 200 con JSON
  `error`. No se presupone un 503 ni JSON en telemetría.
- Compose añadido porque este checkout y los repositorios hermanos carecían del
  soporte de construcción que daba por existente el documento recibido.

## Resultados observados

| Verificación | Resultado |
|---|---|
| `bash -n scripts/escenario-ia-caida.sh` | Correcto |
| `docker compose config --quiet`, Compose 2.35.1 | Correcto |
| Resolución de origen Codespaces de ejemplo | El mismo HTTPS en VITE_API_URL y FRONTEND_URL; IA permanece interna |
| `mvnw.cmd test`, Maven 3.9.12 / JDK 21.0.6 | BUILD SUCCESS; 20 pruebas, 0 fallos, 0 errores, 0 omitidas |
| `ApiIntegrationTests` | 19 pruebas correctas, 9.110 s |
| `BackendApplicationTests` | 1 prueba correcta, 1.164 s |
| `docker compose up --build -d`, motor Linux 28.1.1 | Correcto tras resolver las incidencias descritas abajo |
| Escenario contra Compose real | **Escenario 1 cumplido**, salida 0 |
| Frontend y bundle compilado | HTTP 200; VITE_API_URL incorporada: `http://localhost:5173` |
| Proxy `/api/v1/users/me` sin JWT | HTTP 401 y JSON `Unauthorized`, esperado |
| Preflight `/api/v1/devices` desde origen frontend | HTTP 200 y `Access-Control-Allow-Origin: http://localhost:5173` |
| WebSocket por proxy `/api/v1/ws` | Handshake HTTP 101 |

Ejecución real: usuario `escenario1795055987-634@gridmind.test`, dispositivo **1**.
Sin mocks de servicios, con Spring, Flask/gunicorn y PostgreSQL de este Compose.

| Comprobación real | HTTP | Tiempo curl | Resultado |
|---|---|---|---|
| Lectura inicial, IA arriba, 120 W | 200 | 0.307272 s | Texto de éxito válido |
| Predicción inicial | 200 | 0.226411 s | Número finito, sin error |
| IA detenida, lectura 130 W | 200 | **3.240534 s** | Cumple ≤ 8 s |
| IA detenida, lectura 140 W | 200 | **3.232218 s** | Cumple ≤ 8 s |
| IA detenida, lectura 150 W | 200 | **3.259140 s** | Cumple ≤ 8 s |
| Predicción con IA detenida | 200 | 3.232430 s | `{"error":"El servicio de IA no responde."}` |
| `/health` tras arrancar IA | 200 | 1.119828 s | JSON operativo |
| Predicción recuperada | 200 | 0.237903 s | Sin error; backend no reiniciado |

`docker compose stop` terminó correctamente y `docker inspect` confirmó
`State.Running=false`. El historial pasó de **1 a 4**, con las potencias
120, 130, 140 y 150 W asociadas al dispositivo correcto. IA quedó arrancada.
La transcripción local está en `target/scenario-real.log`, el build final en
`target/compose-up-retry.log` y la comprobación del navegador/proxy se ejecutó
con `target/check-browser-routes.py`. Se conservaron datos y volúmenes.

Los ~3.2 s observados con el contenedor detenido no prueban una demora controlada
de Flask ni una conexión bloqueada deliberadamente. No se inyectaron esas
condiciones: este escenario prueba indisponibilidad del proceso y recuperación.
Los cuatro mensajes `IA ERROR` del backend indicaron `Connect timed out`,
consistentes con los 3 s configurados para conexión más el trabajo de la petición.
En el estado final los cuatro servicios estaban arriba y tanto IA como DB healthy.

Los reportes Maven están en `target/surefire-reports/`. La suite usa H2 y sustituye
`PredictiveService` por un mock en las pruebas de API; no prueba la caída de Flask.

También se ejecutó un arnés local aislado de control de flujo (no el Compose):
éxito, fallo de `stop`, señal TERM, JSON inválido en login, tres lecturas simuladas
de 8.001 s, contenedor todavía activo tras `stop`, health HTTP 503 y health JSON
inválido. Los ocho casos dieron los códigos esperados; los casos posteriores a
stop terminaron con intento de restauración y los fallos de health agotaron su
plazo con mensaje explícito. El reloj del plazo de health se adelantó en el arnés;
eso no es una medición real de latencia. Archivos locales en
`target/test-scenario.sh` y `target/scenario-harness/`.

## Incidencias de entorno

- Git rechazó inicialmente la propiedad del directorio: se usó `-c safe.directory`
  por comando, sin cambiar configuración global.
- No había Java/Maven en PATH. Se encontró JDK 21 en la instalación de PyCharm y
  se ejecutó Maven Wrapper con caché temporal. El primer intento encontró acceso
  denegado a `.m2`; el siguiente, red restringida. La ejecución con acceso de red
  autorizado completó las 20 pruebas.
- El `python3` del PATH era el alias de Microsoft Store. Para ejecutar el script
  en Git Bash se usó el Python 3.11.4 instalado con pgAdmin, mediante una función
  `python3` exportada; no se alteró el PATH global ni el script para esa máquina.
- Docker no estaba iniciado. Los primeros intentos de Compose y escenario
  fallaron en preflight por pipe inexistente; no llegaron a detener IA ni crear
  lecturas. Se inició Docker Desktop y se activó Linux containers. Tras un error
  transitorio EOF al cambiar motor, Docker Linux 28.1.1 respondió y se reintentó
  `docker compose up --build -d` con credenciales aleatorias en `.env` ignorado.
- El primer build del frontend falló con `npm ci` (EUSAGE): faltaban
  `@emnapi/core@2.0.0-beta.2` y `@emnapi/runtime@2.0.0-beta.2` en el lock recibido.
  La primera pasada completó ese lock solo dentro de la imagen antes de instalar.
  Ese paso temporal se retiró al corregir el repositorio frontend en el cierre.
- Al solapar un build adicional con el arranque original se produjo una colisión
  al exportar la misma imagen (`image already exists`). Ambos procesos terminaron
  y el arranque se reintentó sin builds solapados.

## Cierre del lockfile y validación del frontend

El lock recibido declaraba ambas dependencias de `@rolldown/binding-wasm32-wasi`
y los peers de `@napi-rs/wasm-runtime`, pero carecía de sus entradas en `packages`.
`npm ci` valida el grafo bloqueado y no completa un lock inconsistente: por eso
fallaba aun cuando la plataforma utiliza un binding nativo y el WASM es opcional.
Completarlo dentro de la imagen fue una solución temporal para poder ejecutar D1,
no un requisito de Docker. No se puede determinar solo con este archivo qué
herramienta o plataforma produjo originalmente la omisión.

Se regeneró el lock en una instalación sin node_modules con **Node 22.23.5 / npm
10.9.9**, obtenidos de la imagen Node 22 del Compose. Antes solo existía el mínimo
Node ≥22.18 y no había versión npm fijada. Ahora `.nvmrc` y `packageManager`
registran esas versiones, y Compose fija por digest esa misma imagen.

Cambios semánticos del lock: **302 → 304 entradas**, dos adiciones
(`@emnapi/core@2.0.0-beta.2`, `@emnapi/runtime@2.0.0-beta.2`), ninguna eliminación y
**ningún cambio de versión existente**. npm 10 también normalizó marcas `peer`.
La fuente del frontend no cambió. `.dockerignore` excluye .git, dependencias,
compilados y archivos de entorno del contexto de construcción.

| Comprobación de cierre | Resultado observado |
|---|---|
| Primer `npm ci`, contenedor limpio con volumen node_modules nuevo | Correcto, 273 paquetes, 11 s informados por npm |
| Segundo `npm ci`, otro contenedor/volumen limpio | Correcto, 273 paquetes, 12 s informados por npm |
| Lock antes/después de ambas instalaciones | SHA-256 idéntico: `f44a5ca8d0d4186a646416932bca4dae1f125c73039e02ac3fa067c5d5ab2732` |
| `npm test` | 10 pruebas, 10 correctas, 0 fallos/omitidas |
| `npm run lint` | Correcto |
| `npm run build` | TypeScript y Vite correctos |
| `docker compose --progress plain build --no-cache frontend` | Correcto, ejecutando únicamente `npm ci` |
| Recreación de frontend y comprobación HTTP/CORS/WS local | Correcto; página/bundle 200, API protegida 401 esperada, OPTIONS 200, WebSocket 101 |

La compilación conserva el aviso de Vite sobre chunks mayores de 500 kB; no es
un fallo y optimizar su tamaño queda fuera de esta integración. Logs de cierre:
`target/frontend-validation.log`, `target/frontend-reinstall.log` y
`target/frontend-docker-build.log`. Solo se recreó frontend; backend, IA y DB
continuaron activos. Los tiempos D1 de la tabla original se conservan sin cambios;
no se repitió la caída de IA para esta corrección de dependencias.

## Revisión previa de diff y git status de los tres repositorios

En esa revisión se comprobaron cambios staged, unstaged y archivos nuevos. Aún
no había cambios staged ni commits de la integración. No se encontraron
modificaciones ajenas a esta integración.

| Repositorio / base | Cambios de esta integración |
|---|---|
| `GridMind-backend` / `9cc9567` | README y LF para Bash; Compose, plantilla .env y nginx; escenario protegido; guías Docker/Codespaces, resultados y diffs de revisión. Sin cambios Java ni en sus pruebas. |
| `GridMind-frontend` / `d29b89f` | README, package-lock.json y package.json modificados; `.nvmrc` y `.dockerignore` nuevos. Fuente React/TypeScript y pruebas sin cambios. |
| `GridMind-ia` / `d49af45` | Árbol limpio, sin diff staged/unstaged ni archivos nuevos. |

Los cambios que ya estaban en esos commits son la base de trabajo, no parte del
diff de esta integración. `.env` permanece ignorado y no aparece en los diffs.
Los logs y artefactos de prueba bajo `target/`, así como node_modules/dist del
frontend, no se incorporan al control de versiones.

Se entregaron los snapshots locales `docs/integracion-ia-caida.patch` y
`docs/integracion-frontend.patch`, comprobados con whitespace y aplicación inversa
en ese momento. Se conservan como artefactos locales ignorados; no forman parte
de los commits ni representan el diff sobre la nueva base publicada. El cierre
se revisa con los commits de Git; IA no necesitaba patch.

## Recorrido guiado en Codespaces: evidencia aportada por el usuario

El usuario ejecutó los comandos en su Codespace y compartió salidas de terminal
y capturas de Chrome. Las observaciones siguientes corresponden a ese entorno,
no a una ejecución remota directa del agente. Fecha de los registros: 19 de
noviembre de 2026 en el backend; 18 de noviembre por la noche en America/Bogota.

### Versiones, configuración y conservación de datos

| Elemento | Observación |
|---|---|
| Codespace | `organic-funicular-x5v5rx9p4v54c6x67` |
| Backend | `/workspaces/GridMind-backend`, `main`, commit `122e273` |
| Frontend | `/workspaces/GridMind-frontend`, commit `e114384` |
| IA | `/workspaces/GridMind-ia`, commit `08761af` |
| Origen del navegador | `https://organic-funicular-x5v5rx9p4v54c6x67-5173.app.github.dev` |
| Puerto reenviado | 5173, visibilidad Private observada en Ports |
| Estado inicial de Docker | Sin contenedores ni volúmenes; `.env` no existía |
| Arranque | `docker compose up --build -d`, salida 0; cuatro servicios Up, DB e IA healthy |

Se consultaron copias temporales de esos mismos commits en GitHub. Los inventarios
y contenidos de fuentes/pruebas coincidieron con lo revisado: 61 archivos del
backend, 20 del frontend y 4 de IA. El lock del frontend publicado ya coincidía
con el corregido localmente. Existían Dockerfiles y un Compose nuevos; se
conservaron en lugar de aplicar directamente los patches de las bases locales.

Se mantuvo `name: gridmind` del Compose publicado. Se creó `.env` solo porque no
existía, con secretos aleatorios y `BROWSER_ORIGIN`; no se imprimieron sus valores
secretos. El usuario añadió `compose.override.yaml` con FRONTEND_URL, perfil prod,
VITE_API_URL y el montaje de `docker/frontend.conf` para proxy HTTP/WebSocket.
En frontend se fijó el primer FROM del Dockerfile al mismo digest Node revisado,
se creó `.nvmrc` y se añadió `packageManager: npm@10.9.9`.

Los volúmenes `gridmind_db-data` y `gridmind_uploads` se crearon durante el arranque.
No había datos anteriores en ese Codespace; no se ejecutó eliminación de
volúmenes ni reinicialización. Los datos creados durante el recorrido se conservaron.
Estos ajustes remotos son distintos de los archivos de las bases locales de los
diffs de revisión anteriores; aún no se aportó su `git diff`/status final remoto.

### HTTP, navegador y persistencia

Cuenta de prueba: `codespaces-prueba-01@gridmind.test`, nombre `Prueba Codespaces`.
Dispositivo: id **1**, `Medidor Codespaces`, tipo OUTLET, 150 W, ESP32 `cs-prueba-01`.
Es un dispositivo distinto del id 1 del ensayo local, pues son bases separadas.

| Verificación | Evidencia observada |
|---|---|
| `docker compose config --quiet` | Configuración válida |
| `docker compose exec -T frontend nginx -t` | Syntax is ok / test is successful |
| Backend `/v3/api-docs`, desde terminal del Codespace | HTTP 200 |
| Frontend `/`, desde terminal del Codespace | HTTP 200 |
| Proxy `/api/v1/users/me` sin sesión | HTTP 401 y JSON Unauthorized, esperado |
| Página real del navegador | Login cargado en el origen HTTPS esperado, ruta `/login` |
| Registro | Petición `register` HTTP 200 en Network y redirección al login |
| Login | Petición `login` HTTP 200; dashboard y consultas autenticadas visibles |
| Dispositivo | Toast de registro correcto y fila OUTLET / 150 W / `cs-prueba-01`; peticiones devices 200. La captura no muestra la columna Method para distinguir cada GET/POST. |
| Historial consultado desde Console mediante fetch del mismo origen | HTTP **200**, `total: 3`, dispositivo **1** |
| Potencias persistidas | **144, 132 y 120 W**, orden descendente por timestamp |
| Forecast consultado desde Console | HTTP **200**, JSON con `prediction` y `predicted_next_30_days` aproximadamente **96.46545597**, `confidence_score: 0.85`, sin `error` |
| Correspondencia de la UI | Predicción **96.4655 kWh**, coherente con el JSON redondeado a cuatro decimales; consumo visible **0.0011 kWh** |

| Envío de telemetría en Codespaces | HTTP observado en terminal | Tiempo curl observado |
|---|---|---|
| 120 W | 200, texto de éxito | **0.115557 s** |
| 132 W | 200, texto de éxito | **0.034112 s** |
| 144 W | No se aportó su salida de terminal; persistencia y recepción en vivo sí confirmadas | **No aportado** |

Las tres fechas del historial final pertenecen al **19 de noviembre de 2026**
en los timestamps del backend: aproximadamente 03:34:54 (120 W), 03:37:36
(132 W) y 03:45:05 (144 W). Los valores se verificaron en la captura del JSON
de la última consulta. Las esperas deliberadas de 10/30 s previas a los envíos
no forman parte de `curl time_total`.

### WebSocket y comportamiento observado

Se vio la URL WSS del mismo origen con `/api/v1/ws`, handshake **101 Switching
Protocols**, frame servidor **CONNECTED** y SUBSCRIBE a `/topic/energy`,
`/topic/alerts` y `/topic/forecast`. Una conexión anterior mostró DISCONNECT y
RECEIPT; no se confundió ese registro cerrado con prueba de recepción actual.

Los primeros envíos de 120 y 132 W fueron HTTP 200, pero el monitor permaneció
en **0.0 W** y la tabla en vivo no mostró esas lecturas en las capturas. La
predicción visible era 86.4000 kWh. Esto se registra como una incidencia real
de la comprobación inicial; no quedó determinada su causa. Navegación, pérdida
de conexión o momento de suscripción son hipótesis, no conclusiones verificadas.

Para aislar el caso se programó el envío de 144 W con 30 s de espera, se limpió
la lista Network y se recargó el dashboard **antes** de ese envío. Tras esperar
sin navegar ni recargar otra vez, la captura mostró **144.0 W**, predicción
**96.4655 kWh** y una única conexión ws pendiente con estado 101. Otra captura,
filtrada por MESSAGE, mostró mensajes entrantes a **/topic/energy** y
**/topic/forecast**. Así se confirmó actualización visual en vivo con la
conexión nueva. No se aportaron los cuerpos expandidos de esos dos frames.

En las cabeceras de un handshake se observó `Access-Control-Allow-Origin:
http://localhost:5173` pese a la URL WSS reenviada. No se capturaron las cabeceras
de solicitud necesarias para explicar esa transformación; no se declara
verificado un origen CORS exacto del túnel a partir de ese dato. El acceso real
por el mismo origen, el handshake y los mensajes sí funcionaron.

También apareció **Días registrados: 2** y una gráfica con fechas 18/19, aunque
el historial final tiene sus tres timestamps en el día 19. Posible mezcla de
fecha local del navegador y fecha del backend: el dashboard agrega mensajes
usando la fecha local, mientras carga fechas del servidor para el historial.
Es una inferencia del código y las capturas; falta comprobar la zona horaria
efectiva y corregir/validar el criterio de agrupación. No se cambió código React
durante este recorrido.

**Resultado del recorrido principal:** registro, login, dispositivo, historial,
forecast y recepción/actualización WebSocket con conexión nueva comprobados.
No se acredita recepción en vivo de las tres lecturas ni estabilidad prolongada
de la conexión. No se repitió en Codespaces el escenario de caída de IA: los
tiempos D1 originales de este informe permanecen separados e intactos.

## Preparación de commits del backend

Tras la autorización «siguiente», se actualizó `origin/main` y se avanzó la copia
local mediante fast-forward desde `9cc9567` hasta `122e273`, sin reescribir
historia ni hacer push. Antes se guardaron los cambios en un snapshot recuperable
y en un stash de seguridad. No se aplicaron los patches locales directamente
sobre los archivos publicados.

Se conservan sin cambios el `compose.yaml` y `.dockerignore` publicados. Los
ajustes de Codespaces se concilian en `compose.override.yaml`: perfil prod,
FRONTEND_URL/VITE_API_URL y montaje de nginx. Para el uso local se permite el
origen por defecto `http://localhost:5173`; en el Codespace se indicó su origen
HTTPS explícitamente. Los Dockerfiles permanecen bajo responsabilidad de los
repositorios frontend e IA; esta integración requiere sus versiones actualizadas
y no los sustituye por Dockerfiles inline del backend.

El Compose conserva `name: gridmind` y su puerto PostgreSQL configurable mediante
DB_PORT. En la copia local se mantiene explícitamente el nombre anterior del
proyecto Docker mediante COMPOSE_PROJECT_NAME en `.env`, preservando la asociación
con los volúmenes del ensayo original. No se arrancó ni se detuvo la pila para
preparar los commits, ni se migraron datos o se sobrescribieron credenciales.

El cierre se divide en configuración/escenario y documentación/resultados.
Los `.patch` generados se mantienen fuera del control de versiones; `.env`, logs,
target y credenciales continúan excluidos. Los resultados de D1 y del navegador
de las secciones anteriores se conservan como evidencia histórica, no como una
repetición de esas pruebas al hacer los commits.

Validación previa a los commits: `mvnw.cmd -B test` completó **20 pruebas**, sin
fallos, errores ni omitidas (`BUILD SUCCESS`, 11.855 s de ejecución Maven).
Se validaron la sintaxis Bash y el Compose combinado para localhost y para el
origen HTTPS real del Codespace. La resolución de nombres de volúmenes fue
`gridmind-backend_db-data` / `gridmind-backend_uploads` en local y
`gridmind_db-data` en la variante Codespaces. Los Dockerfiles de ambos repositorios
hermanos estaban presentes al comprobar esos contextos. La pila local permaneció
con sus cuatro servicios activos. Log local: `target/backend-precommit-tests.log`.

## Pendientes y alcance

- El recorrido principal en navegador real se completó mediante ejecución del
  usuario. No quedan pasos obligatorios del recorrido guiado por solicitar.
  Para auditoría más completa faltan la salida/tiempo curl de 144 W, cuerpos
  expandidos de los frames y diff/status final de los repositorios del Codespace.
  Las capturas y transcripciones están en esta conversación; no se versionaron
  HAR, tokens, API keys ni imágenes que puedan contener credenciales.
- Investigar la ausencia de actualización inicial de 120/132 W y la discrepancia
  de agrupación por días. La prueba con conexión nueva pasó; la causa inicial,
  reconexión automática tras inactividad y estabilidad prolongada no se verificaron.
- Detener Flask prueba indisponibilidad inmediata. No acredita el plazo frente a
  lentitud o conexión bloqueada: eso necesita otro escenario. Se conserva como
  criterio de aceptación el máximo de 8 segundos por lectura, medido con curl.
- EXIT, INT, TERM y HUP ejecutan restauración; SIGKILL y pérdida del host no pueden
  ejecutar traps. Los datos de prueba se conservan para inspección.
