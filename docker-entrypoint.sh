#!/bin/bash
set -e

# =============================================================================
# Tramando Docker Entrypoint
# =============================================================================
# Gestisce:
# - Configurazione BASE_PATH per servire sotto subpath
# - Avvio del server Clojure
# =============================================================================

echo "=== Tramando Server ==="
echo "PORT: ${PORT}"
echo "BASE_PATH: ${BASE_PATH:-/}"
echo "DATA_DIR: ${DATA_DIR}"
echo "========================"

# Se BASE_PATH è impostato, aggiorna index.html per usare path relativi
if [ -n "${BASE_PATH}" ] && [ "${BASE_PATH}" != "/" ]; then
    echo "Configuring BASE_PATH: ${BASE_PATH}"

    # Rimuovi trailing slash se presente
    BASE_PATH="${BASE_PATH%/}"

    # Aggiorna i path degli asset in index.html
    if [ -f /app/public/index.html ]; then
        sed -i "s|src=\"/js/|src=\"${BASE_PATH}/js/|g" /app/public/index.html
        sed -i "s|href=\"/|href=\"${BASE_PATH}/|g" /app/public/index.html
        echo "Updated index.html with BASE_PATH"
    fi
fi

# Crea directory dati se non esistono
mkdir -p "${DATA_DIR}/projects"

# Avvia il server Clojure
cd /app/server
exec clojure -M:run
