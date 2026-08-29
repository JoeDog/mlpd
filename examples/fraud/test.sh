# inference
curl -s -X POST http://localhost:8080/fraud/prompt \
  -H 'Content-Type: application/json' \
  -d '{
    "amount_usd": 4500,
    "merchant_category": "Crypto Exchange",
    "card_type": "Visa",
    "auth_method": "No Authentication",
    "channel": "Online",
    "device_type": "iPhone",
    "is_foreign_transaction": true,
    "hours_since_last_txn": 0.5,
    "txn_count_last_24h": 8,
    "distance_from_home_km": 210,
    "card_age_months": 24,
    "customer_age": 29,
    "account_balance_usd": 300,
    "is_new_merchant": true,
    "used_vpn": true,
    "ip_country_mismatch": true,
    "billing_shipping_mismatch": true,
    "cvv_retry_count": 2,
    "velocity_score": 70,
    "time_of_day_hour": 3,
    "day_of_week": 6,
    "is_ai_generated_scam_attempt": true,
    "merchant_risk_score": 95,
    "prior_disputes": 3
  }'
echo ""
# -> {"fraud":true,"score":0.9341}

