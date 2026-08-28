#!/bin/bash
# Usage: ./train.sh [fraud.csv] [http://localhost:8080/fraud/train]

FILE=${1:-fraud.csv}
URL=${2:-http://localhost:8080/fraud/train}

# Convert Python True/False (or 0/1) to JSON true/false
bool() {
  case "$1" in
    [Tt]rue|1) echo true ;;
    *)         echo false ;;
  esac
}

count=0
tail -n +2 "$FILE" | while IFS=, read -r \
  txn_id amount merchant_category card_type auth_method channel device_type \
  is_foreign hours_since txn_count distance card_age customer_age balance \
  is_new_merchant used_vpn ip_mismatch billing_mismatch cvv_retry velocity \
  tod dow is_ai_scam merchant_risk prior_disputes is_fraud
do
  json=$(printf '{
  "amount_usd": %s,
  "merchant_category": "%s",
  "card_type": "%s",
  "auth_method": "%s",
  "channel": "%s",
  "device_type": "%s",
  "is_foreign_transaction": %s,
  "hours_since_last_txn": %s,
  "txn_count_last_24h": %s,
  "distance_from_home_km": %s,
  "card_age_months": %s,
  "customer_age": %s,
  "account_balance_usd": %s,
  "is_new_merchant": %s,
  "used_vpn": %s,
  "ip_country_mismatch": %s,
  "billing_shipping_mismatch": %s,
  "cvv_retry_count": %s,
  "velocity_score": %s,
  "time_of_day_hour": %s,
  "day_of_week": %s,
  "is_ai_generated_scam_attempt": %s,
  "merchant_risk_score": %s,
  "prior_disputes": %s,
  "is_fraud": %s
}' \
    "$amount" "$merchant_category" "$card_type" "$auth_method" \
    "$channel" "$device_type" \
    "$(bool "$is_foreign")" "$hours_since" "$txn_count" "$distance" \
    "$card_age" "$customer_age" "$balance" \
    "$(bool "$is_new_merchant")" "$(bool "$used_vpn")" "$(bool "$ip_mismatch")" \
    "$(bool "$billing_mismatch")" "$cvv_retry" "$velocity" \
    "$tod" "$dow" "$(bool "$is_ai_scam")" "$merchant_risk" "$prior_disputes" \
    "$(bool "$is_fraud")")

  echo -n "txn $txn_id: "
  curl -s -X POST "$URL" -H 'Content-Type: application/json' -d "$json"
  echo
  count=$((count + 1))
done

echo "Done: $count records posted to $URL"
