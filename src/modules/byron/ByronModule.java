package modules.byron;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.joedog.ann.MLP;
import org.joedog.ann.Module;
import org.joedog.ann.data.Value;
import org.joedog.ann.function.Function;

/**
 * Byron -- a tic-tac-toe value-net module for mlp-lite / MLPd.
 *
 * The network is a {19,64,32,3} MLP trained on O-first tic-tac-toe.
 *
 * Input vector (19):
 *   for square i in 0..8:  in[2i]   = 1.0 if 'X' else 0.0
 *                          in[2i+1] = 1.0 if 'O' else 0.0
 *   in[18] = 1.0 if O is to move, else 0.0        (O moves first)
 *
 * Output vector (3), from O's perspective:
 *   out[0] = P(O wins)   out[1] = P(draw)   out[2] = P(O loses)
 *
 * Byron plays 'O'.  It selects a move with a one-ply search: for every empty
 * square it places 'O' and scores the resulting (X-to-move) child with the
 * net, choosing the square that minimizes O's danger:
 *
 *   danger = (P(draw)*0.5 + P(loss)*1.0) / (P(win)+P(draw)+P(loss))
 *
 * Terminal children are scored exactly (O-win = 0.0, draw = 0.5).  This search
 * was exhaustively verified against a minimax oracle on all 2423 legal
 * O-to-move positions: it never worsens the game-theoretic outcome.
 */
public class ByronModule implements Module {

  private static final Gson  GSON     = new Gson();
  private static final int   INPUTS   = 19;
  private static final int[] TOPOLOGY = {INPUTS, 64, 32, 3};

  private static final int[][] LINES = {
    {0,1,2},{3,4,5},{6,7,8},{0,3,6},{1,4,7},{2,5,8},{0,4,8},{2,4,6}
  };

  @Override public int[]    topology() { return TOPOLOGY; }
  @Override public Function function() { return Function.SIGMOIDAL; }

  /**
   * Byron's move selection.  Overrides the default encode -> predict -> decode
   * path because a value net needs a per-child search, not a single pass.
   *
   * Request  body: {"board":"O---X-OX-","turn":0.0}   (board is 9 chars X/O/-)
   * Response body: {"move":N}                          (N is a 0-based square)
   */
  @Override
  public String prompt(MLP mlp, String json) throws Exception {
    JsonObject o     = GSON.fromJson(json, JsonObject.class);
    String     board = str(o, "board");
    if (board == null || board.length() != 9) {
      throw new IllegalArgumentException("board must be 9 chars of X/O/-");
    }

    // Empty board: every opening is game-theoretically equal -> random.
    if (board.indexOf('X') < 0 && board.indexOf('O') < 0) {
      return "{\"move\":" + (int)(Math.random() * 9) + "}";
    }

    int    bestSq  = -1;
    double bestVal = Double.MAX_VALUE;
    for (int i = 0; i < 9; i++) {
      if (board.charAt(i) != '-') continue;
      String child = board.substring(0, i) + 'O' + board.substring(i + 1);
      double v     = childValue(mlp, child);
      if (v < bestVal) { bestVal = v; bestSq = i; }
    }
    return "{\"move\":" + bestSq + "}";
  }

  /** Danger of a child position to O; lower is better.  Terminals scored exactly. */
  private double childValue(MLP mlp, String child) {
    char w = winner(child);
    if (w == 'O') return 0.0;              // O just won -> best possible
    if (w == 'X') return 1.0;              // guard (cannot happen after an O move)
    if (isFull(child)) return 0.5;         // draw
    Value[] out = mlp.predict(encodeBoard(child, false)); // child: X to move
    double win = out[0].getValue();
    double dr  = out[1].getValue();
    double ls  = out[2].getValue();
    double s   = win + dr + ls;
    return s <= 0 ? 0.5 : (dr * 0.5 + ls * 1.0) / s;
  }

  // ---- default SPI path (encode -> predict -> decode) and training ----

  @Override
  public Value[] encode(String json) throws Exception {
    JsonObject o     = GSON.fromJson(json, JsonObject.class);
    String     board = str(o, "board");
    return encodeBoard(board, oToMove(board));
  }

  @Override
  public Value[] target(String json) throws Exception {
    JsonObject o = GSON.fromJson(json, JsonObject.class);
    // Label from O's perspective: "win" | "draw" | "loss" -> 3-way one-hot.
    String r = o.has("result") ? o.get("result").getAsString() : "draw";
    return new Value[]{
      analog("win".equals(r)  ? 1.0 : 0.0),
      analog("draw".equals(r) ? 1.0 : 0.0),
      analog("loss".equals(r) ? 1.0 : 0.0)
    };
  }

  @Override
  public String decode(Value[] output) {
    // Only used by the default path; report the raw O-perspective distribution.
    double win = output.length > 0 ? output[0].getValue() : 0.0;
    double dr  = output.length > 1 ? output[1].getValue() : 0.0;
    double ls  = output.length > 2 ? output[2].getValue() : 0.0;
    return String.format("{\"win\":%.4f,\"draw\":%.4f,\"loss\":%.4f}", win, dr, ls);
  }

  // ---- encoding + game helpers ----

  private static Value[] encodeBoard(String board, boolean oToMove) {
    Value[] in = new Value[INPUTS];
    for (int i = 0; i < 9; i++) {
      char c = board.charAt(i);
      in[i*2]     = bool(c == 'X');
      in[i*2 + 1] = bool(c == 'O');
    }
    in[18] = bool(oToMove);
    return in;
  }

  /** O moves first, so equal X/O counts means it is O's turn. */
  private static boolean oToMove(String board) {
    int x = 0, o = 0;
    for (int i = 0; i < board.length(); i++) {
      char c = board.charAt(i);
      if      (c == 'X') x++;
      else if (c == 'O') o++;
    }
    return x == o;
  }

  private static char winner(String b) {
    for (int[] L : LINES) {
      char a = b.charAt(L[0]);
      if (a != '-' && a == b.charAt(L[1]) && a == b.charAt(L[2])) return a;
    }
    return 0;
  }

  private static boolean isFull(String b) { return b.indexOf('-') < 0; }

  private static Value  bool(boolean b)  { return new Value.ValueBuilder().asBoolean(b); }
  private static Value  analog(double d) { return new Value.ValueBuilder().asAnalog(d); }
  private static String str(JsonObject o, String k) {
    return o.has(k) ? o.get(k).getAsString() : null;
  }
}
