#!/bin/bash
# Script per avviare il server Tramando
# Termina eventuali istanze precedenti per evitare conflitti di porta

cd "$(dirname "$0")"

echo "Terminando eventuali server Tramando esistenti..."
pkill -f "tramando.server.core" 2>/dev/null

# Attendi che i processi terminino
sleep 1

# Verifica che la porta 3000 sia libera
if lsof -i :3000 -sTCP:LISTEN >/dev/null 2>&1; then
    echo "ERRORE: La porta 3000 è ancora occupata da un altro processo:"
    lsof -i :3000 -sTCP:LISTEN
    exit 1
fi

echo "Avvio server Tramando sulla porta 3000..."
clojure -M -m tramando.server.core
