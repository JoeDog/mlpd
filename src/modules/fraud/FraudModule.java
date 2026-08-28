package modules.fraud;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.joedog.ann.Module;
import org.joedog.ann.data.Value;
import org.joedog.ann.function.Function;

/**
 * Module implementation for credit card fraud detection.
 *
 * Packaging
 * ---------
 * Compile this class against mlp-lite.jar and gson-*.jar, then package it
 * into fraud.jar alongside the SPI descriptor:
 *
 *   META-INF/services/org.joedog.ann.Module
 *     (single line: fraud.FraudModule)
 *
 * Place fraud.jar in lib/.  MLPd will load lib/fraud.xml at startup
 * and register POST /fraud/prompt and POST /fraud/train.
 *
 * Prompt request body
 * -------------------
 *   {
 *     "amount_usd":                  1234.56,
 *     "merchant_category":           "Online Retail",
 *     "card_type":                   "Visa",
 *     "auth_method":                 "OTP",
 *     "channel":                     "Online",
 *     "device_type":                 "iPhone",
 *     "is_foreign_transaction":      false,
 *     "hours_since_last_txn":        12.3,
 *     "txn_count_last_24h":          2,
 *     "distance_from_home_km":       45.0,
 *     "card_age_months":             36,
 *     "customer_age":                34,
 *     "account_balance_usd":         5000.0,
 *     "is_new_merchant":             false,
 *     "used_vpn":                    false,
 *     "ip_country_mismatch":         false,
 *     "billing_shipping_mismatch":   false,
 *     "cvv_retry_count":             0,
 *     "velocity_score":              12.0,
 *     "time_of_day_hour":            14,
 *     "day_of_week":                 2,
 *     "is_ai_generated_scam_attempt":false,
 *     "merchant_risk_score":         30.0,
 *     "prior_disputes":              0
 *   }
 *
 * Train request body
 * ------------------
 *   Same as prompt, plus:
 *     "is_fraud": true
 *
 * Responses
 * ---------
 *   prompt: { "fraud": true,  "score": 0.873 }
 *   train:  { "status": "ok", "calls": 42    }
 */
public class FraudModule implements Module {

  private static final Gson GSON = new Gson();

  /* ---- categorical vocabularies (order must match the training encoder) ---- */
  private static final String[] MERCHANT_CATEGORIES = {
    "Crypto Exchange", "Electronics", "Fuel", "Gaming", "Gift Cards",
    "Groceries", "Healthcare", "Online Retail", "Restaurants",
    "Streaming", "Travel", "Utilities"
  };
  private static final String[] CARD_TYPES = {
    "Amex", "Discover", "Mastercard", "RuPay", "Visa"
  };
  private static final String[] AUTH_METHODS = {
    "3D Secure", "Biometric", "No Authentication", "OTP", "PIN"
  };
  private static final String[] CHANNELS = {
    "ATM", "Contactless", "In-App", "Online", "POS"
  };
  private static final String[] DEVICE_TYPES = {
    "ATM Machine", "Android Phone", "Mac", "POS Terminal",
    "Smart Watch", "Tablet", "Windows PC", "iPhone"
  };

  /* ---- numeric ranges ---- */
  private static final double AMOUNT_HI        = 7_000.0;
  private static final double HOURS_HI         = 90.0;
  private static final double DISTANCE_HI      = 220.0;
  private static final double BALANCE_HI       = 130_000.0;
  private static final double VELOCITY_HI      = 75.0;
  private static final double MERCHANT_RISK_HI = 100.0;
  private static final int    TXN_COUNT_HI     = 12;
  private static final int    CARD_AGE_LO      = 22,  CARD_AGE_HI  = 74;
  private static final int    CUSTOMER_AGE_LO  = 18,  CUSTOMER_AGE_HI = 81;
  private static final int    CVV_RETRY_HI     = 3;
  private static final int    PRIOR_DISP_HI    = 4;
  private static final int    HOUR_HI          = 23;
  private static final int    DOW_HI           = 6;

  /* ---- threshold chosen at training time (best F1 on real distribution) ---- */
  private static final double THRESHOLD = 0.50;

  // -----------------------------------------------------------------------
  // Module
  // -----------------------------------------------------------------------

  @Override
  public int[] topology() { return new int[]{54, 24, 1}; }

  @Override
  public Function function() { return Function.SIGMOIDAL; }

  @Override
  public Value[] encode(String json) throws Exception {
    JsonObject o = GSON.fromJson(json, JsonObject.class);
    Value.ValueBuilder v = new Value.ValueBuilder();

    /* one-hot categoricals */
    Value[] mc = oneHot(str(o, "merchant_category"),      MERCHANT_CATEGORIES);
    Value[] ct = oneHot(str(o, "card_type"),              CARD_TYPES);
    Value[] am = oneHot(str(o, "auth_method"),            AUTH_METHODS);
    Value[] ch = oneHot(str(o, "channel"),                CHANNELS);
    Value[] dt = oneHot(str(o, "device_type"),            DEVICE_TYPES);

    /* continuous numerics */
    Value amt = v.inRange(dbl(o, "amount_usd"),               0, AMOUNT_HI);
    Value hrs = v.inRange(dbl(o, "hours_since_last_txn"),     0, HOURS_HI);
    Value dst = v.inRange(dbl(o, "distance_from_home_km"),    0, DISTANCE_HI);
    Value bal = v.inRange(dbl(o, "account_balance_usd"),      0, BALANCE_HI);
    Value vel = v.inRange(dbl(o, "velocity_score"),           0, VELOCITY_HI);
    Value mri = v.inRange(dbl(o, "merchant_risk_score"),      0, MERCHANT_RISK_HI);

    /* integer ranges */
    Value txn = v.inRange(dbl(o, "txn_count_last_24h"),       0,           TXN_COUNT_HI);
    Value cag = v.inRange(dbl(o, "card_age_months"),          CARD_AGE_LO, CARD_AGE_HI);
    Value cus = v.inRange(dbl(o, "customer_age"),             CUSTOMER_AGE_LO, CUSTOMER_AGE_HI);
    Value cvv = v.inRange(dbl(o, "cvv_retry_count"),          0, CVV_RETRY_HI);
    Value prd = v.inRange(dbl(o, "prior_disputes"),           0, PRIOR_DISP_HI);
    Value tod = v.inRange(dbl(o, "time_of_day_hour"),         0, HOUR_HI);
    Value dow = v.inRange(dbl(o, "day_of_week"),              0, DOW_HI);

    /* booleans */
    Value for_ = v.asBoolean(bool(o, "is_foreign_transaction"));
    Value nm   = v.asBoolean(bool(o, "is_new_merchant"));
    Value vpn  = v.asBoolean(bool(o, "used_vpn"));
    Value ipc  = v.asBoolean(bool(o, "ip_country_mismatch"));
    Value bsm  = v.asBoolean(bool(o, "billing_shipping_mismatch"));
    Value ais  = v.asBoolean(bool(o, "is_ai_generated_scam_attempt"));

    /* assemble -- order must match topology()[0] == 54 */
    Value[] inputs = new Value[54];
    int i = 0;
    for (Value x : mc)  inputs[i++] = x;   // 12
    for (Value x : ct)  inputs[i++] = x;   //  5
    for (Value x : am)  inputs[i++] = x;   //  5
    for (Value x : ch)  inputs[i++] = x;   //  5
    for (Value x : dt)  inputs[i++] = x;   //  8
    inputs[i++] = amt; inputs[i++] = hrs; inputs[i++] = dst;
    inputs[i++] = bal; inputs[i++] = vel; inputs[i++] = mri;  //  6
    inputs[i++] = txn; inputs[i++] = cag; inputs[i++] = cus;
    inputs[i++] = cvv; inputs[i++] = prd; inputs[i++] = tod;
    inputs[i++] = dow;                                          //  7
    inputs[i++] = for_; inputs[i++] = nm;  inputs[i++] = vpn;
    inputs[i++] = ipc;  inputs[i++] = bsm; inputs[i++] = ais;  //  6
    return inputs;
  }

  @Override
  public Value[] target(String json) throws Exception {
    JsonObject o = GSON.fromJson(json, JsonObject.class);
    return new Value[]{ new Value.ValueBuilder().asBoolean(bool(o, "is_fraud")) };
  }

  @Override
  public String decode(Value[] output) {
    double score = output[0].getValue();
    boolean fraud = score >= THRESHOLD;
    return String.format("{\"fraud\":%b,\"score\":%.4f}", fraud, score);
  }

  // -----------------------------------------------------------------------
  // Encoding helpers
  // -----------------------------------------------------------------------

  private static Value[] oneHot(String value, String[] vocab) {
    Value.ValueBuilder v = new Value.ValueBuilder();
    Value[] vec = new Value[vocab.length];
    for (int i = 0; i < vocab.length; i++) {
      vec[i] = v.asBoolean(vocab[i].equals(value));
    }
    return vec;
  }

  private static String  str(JsonObject o, String k)  { return o.get(k).getAsString(); }
  private static double  dbl(JsonObject o, String k)  { return o.get(k).getAsDouble(); }
  private static boolean bool(JsonObject o, String k) { return o.get(k).getAsBoolean(); }
}
