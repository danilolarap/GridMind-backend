#!/usr/bin/env bash
# Adaptado del escenario D1 recibido; ejecutar contra el compose real, nunca mocks.
set -Eeuo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
LIMITE_SEG=8
FALLOS=0
RESTORE_IA=0
for tool in docker curl python3; do
  command -v "$tool" >/dev/null || { echo "FALLA: falta $tool" >&2; exit 1; }
done
python3 -c 'import json,sys' || { echo 'FALLA: python3 no es un interprete operativo' >&2; exit 1; }
docker compose version >/dev/null
docker compose config --quiet
docker info >/dev/null
# Leer los puertos resueltos por Compose, incluyendo .env, sin hacer source de .env.
CONFIG=$(docker compose config --format json)
port() {
  python3 -c 'import json,sys; print(json.load(sys.stdin)["services"][sys.argv[1]]["ports"][0]["published"])' "$1" <<< "$CONFIG"
}
API="${API:-http://localhost:$(port backend)/api/v1}"
HEALTH="${IA_HEALTH_URL:-http://localhost:$(port ia)/health}"
TMP=$(mktemp -d)
BODY="$TMP/body"
die() { echo "FALLA: $*" >&2; exit 1; }
ok() { echo "OK: $*"; }
falla() { echo "FALLA: $*" >&2; FALLOS=$((FALLOS + 1)); }
# Todas las peticiones tienen limites de conexion y de duracion total; sin retries
# de POST (podrian duplicar lecturas). Las respuestas no imprimen JWT ni API keys.
request() {
  local method=$1 url=$2 limit=$3
  shift 3
  local metrics
  CURL_RC=0
  metrics=$(curl --silent --show-error --connect-timeout 3 --max-time "$limit" \
    -o "$BODY" -w '%{http_code} %{time_total}' -X "$method" "$url" "$@") || CURL_RC=$?
  read -r HTTP SEG <<< "$metrics"
  echo "  $method ${url%%\?*}: curl=$CURL_RC HTTP=$HTTP tiempo=${SEG}s"
}
expect200() { [[ $CURL_RC == 0 && $HTTP == 200 ]] || die "HTTP esperado 200; recibido $HTTP (curl $CURL_RC)"; }
validate() {
  python3 -c '
import json,sys,math
with open(sys.argv[1], encoding="utf-8") as f: d=json.load(f)
mode=sys.argv[2]
assert isinstance(d,dict), "Se esperaba un objeto JSON"
if mode=="register":
    assert isinstance(d.get("id"),int) and d["id"]>0 and d.get("email")==sys.argv[3]
elif mode=="token":
    assert isinstance(d.get("token"),str) and d["token"].strip()
    print(d["token"])
elif mode=="device":
    assert isinstance(d.get("id"),int) and d["id"]>0
    assert isinstance(d.get("apiKey"),str) and d["apiKey"].strip()
    print(d["id"],d["apiKey"])
elif mode=="history":
    n=d.get("totalElements",d.get("page",{}).get("totalElements"))
    assert isinstance(n,int) and not isinstance(n,bool) and n>=0
    assert isinstance(d.get("content"),list)
    print(n)
elif mode=="powers":
    rows=d["content"]
    assert len(rows)==4, "Se esperaban las cuatro lecturas"
    assert sorted(r["power"] for r in rows)==[120,130,140,150]
    assert all(r["deviceId"]==int(sys.argv[3]) for r in rows)
elif mode=="forecast":
    v=d.get("predicted_next_30_days")
    assert "error" not in d and isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v) and v>=0
elif mode=="error":
    assert d.get("error")=="El servicio de IA no responde."
elif mode=="health":
    assert d.get("status")=="IA Service is Operational" and "error" not in d
else: raise ValueError(mode)
' "$BODY" "$@"
}
wait_health() {
  local deadline=$((SECONDS + 60)) remaining
  while (( SECONDS < deadline )); do
    remaining=$((deadline - SECONDS))
    (( remaining > 5 )) && remaining=5
    request GET "$HEALTH" "$remaining"
    if [[ $CURL_RC == 0 && $HTTP == 200 ]] && validate health 2>/dev/null; then
      return 0
    fi
    (( SECONDS < deadline )) && sleep 1
  done
  echo "FALLA: /health no devolvio HTTP 200 y JSON operativo dentro de 60s" >&2
  return 1
}
cleanup() {
  local rc=$?
  trap - EXIT INT TERM HUP
  if (( RESTORE_IA )); then
    echo 'Restaurando IA tras salida/interrupcion...'
    if docker compose start ia && wait_health; then
      echo 'OK: IA restaurada'
    else
      echo 'FALLA: restauracion; ejecutar docker compose start ia y revisar logs' >&2
      rc=1
    fi
  fi
  rm -f -- "$BODY"
  rmdir -- "$TMP"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

echo '== Precondiciones: IA y backend reales arriba'
IA_ID=$(docker compose ps -q ia)
[[ -n $IA_ID ]] || die 'ia no esta ejecutandose; ejecutar docker compose up --build -d'
wait_health || die 'IA inicial no operativa'
SUFIJO="$(date +%s)-$$"
EMAIL="escenario$SUFIJO@gridmind.test"
PASS='Demo12345'
request POST "$API/users/register" 15 -H 'Content-Type: application/json' \
  -d "{\"name\":\"Escenario IA caida\",\"email\":\"$EMAIL\",\"password\":\"$PASS\"}"
expect200
validate register "$EMAIL" || die 'JSON de registro invalido'
request POST "$API/users/login" 15 -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\"}"
expect200
TOKEN=$(validate token) || die 'JSON de login sin token valido'
AUTH=(-H "Authorization: Bearer $TOKEN")
request POST "$API/devices" 15 "${AUTH[@]}" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Medidor Escenario\",\"type\":\"OUTLET\",\"powerRating\":150,\"esp32Id\":\"esc-$SUFIJO\"}"
expect200
DEVICE=$(validate device) || die 'JSON de dispositivo invalido'
read -r DEVICE_ID APIKEY <<< "$DEVICE"
echo "  Usuario $EMAIL, dispositivo $DEVICE_ID (persisten para revision)"
telemetry() {
  local current
  current=$(python3 -c 'import sys; print(round(float(sys.argv[1])/120,2))' "$1")
  request POST "$API/iot/telemetry" 12 -H 'Content-Type: application/json' \
    -d "{\"apiKey\":\"$APIKEY\",\"voltage\":120,\"current\":$current,\"power\":$1}"
}
telemetry_valid() {
  [[ $CURL_RC == 0 && $HTTP == 200 ]] && \
    [[ $(cat "$BODY") == 'Telemetría GridMind recibida con éxito' ]]
}
history() {
  request GET "$API/energy/$DEVICE_ID?size=20&sort=timestamp,desc" 15 "${AUTH[@]}"
  expect200
  TOTAL=$(validate history) || die 'Historial JSON invalido'
}
echo '== Paso 1: lectura y prediccion con IA arriba'
telemetry 120
telemetry_valid || die 'Telemetria inicial invalida (respuesta de texto esperada)'
request GET "$API/analytics/forecast" 15 "${AUTH[@]}"
expect200
validate forecast || die 'Prediccion inicial invalida'
history
ANTES=$TOTAL
[[ $ANTES == 1 ]] || die 'El medidor nuevo debe tener una lectura'

echo '== Paso 2: detener IA y verificar estado del contenedor'
# Marcar ANTES de stop: incluso una interrupcion durante stop debe restaurar IA.
RESTORE_IA=1
docker compose stop --timeout 10 ia || die 'docker compose stop ia fallo'
RUNNING=$(docker inspect --format '{{.State.Running}}' "$IA_ID") || die 'No se pudo inspeccionar ia'
[[ $RUNNING == false ]] || die 'ia sigue ejecutandose tras stop'
ok 'ia detenida y verificada'

echo '== Paso 3: tres lecturas; limite de aceptacion 8 segundos cada una'
for P in 130 140 150; do
  telemetry "$P"
  if telemetry_valid && python3 -c 'import sys; sys.exit(0 if float(sys.argv[1])<=8 else 1)' "$SEG"; then
    ok "${P} W: HTTP $HTTP, ${SEG}s <= ${LIMITE_SEG}s"
  else
    falla "${P} W: curl=$CURL_RC HTTP=$HTTP, ${SEG}s; limite ${LIMITE_SEG}s o cuerpo invalido"
  fi
done
history
if (( TOTAL - ANTES == 3 )) && validate powers "$DEVICE_ID"; then
  ok "Tres lecturas persistidas ($ANTES -> $TOTAL), potencias y dispositivo correctos"
else
  falla "Persistencia incorrecta ($ANTES -> $TOTAL)"
fi
echo '== Paso 4: error controlado sin tumbar backend'
request GET "$API/analytics/forecast" 15 "${AUTH[@]}"
expect200
validate error || die 'Se esperaba HTTP 200 con JSON error de indisponibilidad'
ok 'Error JSON controlado; backend responde'
echo '== Paso 5: restaurar IA y esperar recuperacion'
docker compose start ia || die 'No se pudo iniciar ia'
wait_health || die 'IA no recupero /health'
# Reintentos acotados para cache DNS de JVM; no se reinicia backend.
RECOVERED=0
for ((i=1; i<=10; i++)); do
  request GET "$API/analytics/forecast" 12 "${AUTH[@]}"
  if [[ $CURL_RC == 0 && $HTTP == 200 ]] && validate forecast 2>/dev/null; then
    RECOVERED=1
    break
  fi
  (( i < 10 )) && sleep 2
done
(( RECOVERED )) || die 'La prediccion no se recupero tras 10 intentos'
RESTORE_IA=0
ok 'Prediccion recuperada sin reiniciar backend'
echo 'Alcance: detener Flask prueba indisponibilidad inmediata; lentitud o conexion bloqueada requieren otro escenario.'
(( FALLOS == 0 )) || die "Escenario con $FALLOS fallas"
echo 'Escenario 1 cumplido.'
