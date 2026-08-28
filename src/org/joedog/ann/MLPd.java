package org.joedog.ann;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;
import org.joedog.ann.MLP;
import org.joedog.ann.Module;
import org.joedog.ann.data.Value;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * mlp-lite Jetty 12 service.
 *
 * Scans lib/modules/*.jar at startup.  For each JAR it loads a Module via the
 * Java SPI, pairs it with a trained (or freshly-created) MLP, and registers:
 *
 *   POST /{name}/prompt   -- inference:       encode -> predict -> decode
 *   POST /{name}/train    -- online learning: encode + target -> learn
 *
 * Uses the Jetty 12 core Handler API -- no servlet API required.
 *
 * Dependencies (lib/):
 *   jetty-server-12.x.x.jar
 *   jetty-http-12.x.x.jar
 *   jetty-io-12.x.x.jar
 *   jetty-util-12.x.x.jar
 *   gson-2.x.jar
 *   mlp-lite.jar
 *   lib/modules
 *     [module JARs]
 *
 * Usage:
 *   java -cp "lib/*:out/" jetty.MLPd [port]
 */
public class MLPd extends Handler.Abstract {

  private static final int SAVE_INTERVAL = 1_000;

  /**
   * Read-only seeds shipped by the build (out/modules/<name>/<name>.xml) and
   * writable learned state (var/models/<name>.xml) are kept in SEPARATE trees.
   * The server never overwrites a seed, so `ant clean` + rebuild always
   * restores a known-good starting network, and a module that has never
   * trained can never clobber it with a fresh random net.
   */
  private static final String SEED_DIR   = "out/modules";
  private static final String MODELS_DIR = "var/models";

  private record Entry(
    String        name,
    Module        module,
    MLP           mlp,
    AtomicInteger trainCount
  ) {
    void save() {
      new File(MODELS_DIR).mkdirs();
      String path = MODELS_DIR + "/" + name + ".xml";
      mlp.save(path);
      System.out.printf("[%s] Saved to %s%n", name, path);
    }
  }

  private final ConcurrentHashMap<String, Entry> modules = new ConcurrentHashMap<>();

  public MLPd() throws Exception {
    File lib = new File("lib/modules");
    if (!lib.isDirectory()) throw new IllegalStateException("lib/modules directory not found");

    File[] jars = lib.listFiles(f -> f.getName().endsWith(".jar"));
    if (jars == null || jars.length == 0) {
      System.out.println("Warning: no module JARs found in lib/modules");
    } else {
      for (File jar : jars) loadModule(jar);
    }

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("Shutdown: saving trained models...");
      modules.values().stream()
        .filter(e -> e.trainCount().get() > 0)   // never persist an untrained (fresh/seed) net
        .forEach(Entry::save);
    }, "mlp-shutdown"));
  }

  private void loadModule(File jar) {
    String name = jar.getName().replaceAll("\\.jar$", "");
    try {
      URLClassLoader loader = new URLClassLoader(
        new URL[]{jar.toURI().toURL()},
        Thread.currentThread().getContextClassLoader()
      );

      Module module = ServiceLoader.load(Module.class, loader)
        .findFirst()
        .orElse(null);

      if (module == null) {
        /* Not every JAR is a module (Jetty, Gson, etc.) -- silently skip. */
        return;
      }

      MLP mlp = loadOrCreate(name, module);

      modules.put(name, new Entry(name, module, mlp, new AtomicInteger(0)));
      System.out.printf("[%s] Registered -- POST /%s/prompt  POST /%s/train%n",
        name, name, name);

    } catch (Exception e) {
      System.out.printf("[%s] Load failed: %s%n", name, e.getMessage());
    }
  }

  /**
   * Resolve the network for a module, preferring durable learned state, then
   * the shipped seed, then a fresh random net:
   *
   *   1. var/models/<name>.xml         -- learned state written by save()
   *   2. out/modules/<name>/<name>.xml -- seed shipped by the build
   *      (falls back to the first *.xml in that directory)
   *   3. fresh MLP.create(...)         -- nothing on disk
   */
  private static MLP loadOrCreate(String name, Module module) {
    File learned = new File(MODELS_DIR + "/" + name + ".xml");
    if (learned.isFile()) {
      System.out.printf("[%s] Loaded learned model from %s%n", name, learned.getPath());
      return MLP.load(learned.getPath());
    }

    File seed = seedModel(name);
    if (seed != null) {
      System.out.printf("[%s] Loaded seed model from %s%n", name, seed.getPath());
      return MLP.load(seed.getPath());
    }

    System.out.printf("[%s] No saved model; created fresh %s%n",
      name, Arrays.toString(module.topology()));
    return MLP.create(module.topology(), module.function(), true);
  }

  /** Locate the build-shipped seed: out/modules/<name>/<name>.xml, else first *.xml there. */
  private static File seedModel(String name) {
    File dir = new File(SEED_DIR + "/" + name);
    if (!dir.isDirectory()) return null;
    File exact = new File(dir, name + ".xml");
    if (exact.isFile()) return exact;
    File[] xmls = dir.listFiles(f -> f.getName().endsWith(".xml"));
    return (xmls != null && xmls.length > 0) ? xmls[0] : null;
  }

  /* Tell Jetty this handler does blocking I/O so it runs on a proper thread. */
  @Override
  public Invocable.InvocationType getInvocationType() {
    return Invocable.InvocationType.BLOCKING;
  }

  @Override
  public boolean handle(Request request, Response response, Callback callback) throws Exception {
    // --- CORS: apply to every response ---
    response.getHeaders().put("Access-Control-Allow-Origin", "*");  // XXX: should be https://www.joedog.org in prod
    response.getHeaders().put("Access-Control-Allow-Methods", "POST, GET, OPTIONS");
    response.getHeaders().put("Access-Control-Allow-Headers", "Content-Type");

    // --- Preflight: must return 200 and must NOT read the body or route to a module ---
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
      response.setStatus(200);
      callback.succeeded();
      return true;
    }

    /* "/credit/prompt" -> ["", "credit", "prompt"] */
    String[] parts = request.getHttpURI().getPath().split("/", 4);
    if (parts.length < 3 || parts[1].isEmpty()) {
      reply(response, callback, 404, err("Expected /{module}/{prompt|train}"));
      return true;
    }

    String ns     = parts[1];
    String action = parts[2];
    Entry  entry  = modules.get(ns);

    if (entry == null) {
      reply(response, callback, 404, err("No module registered: " + ns));
      return true;
    }

    String body = readBody(request);
    try {
      switch (action) {
        case "prompt" -> prompt(entry, body, response, callback);
        case "train"  -> train(entry, body, response, callback);
        default       -> reply(response, callback, 404, err("Unknown action: " + action));
      }
    } catch (Exception e) {
      reply(response, callback, 500, err(e.getMessage()));
    }

    return true;
  }

  // -----------------------------------------------------------------------
  // Inference and training
  // -----------------------------------------------------------------------
  private void prompt(Entry e, String body, Response res, Callback cb) throws Exception {
    String out = e.module().prompt(e.mlp(), body);
    if (out == null) { reply(res, cb, 500, err("prompt() returned null")); return; }
    reply(res, cb, 200, out);
  }
 
  private void train(Entry e, String body, Response res, Callback cb) throws Exception {
    Value[] inputs = e.module().encode(body);
    Value[] target = e.module().target(body);
    if (!e.mlp().learn(inputs, target)) { reply(res, cb, 500, err("learn() failed")); return; }
    int n = e.trainCount().incrementAndGet();
    if (n % SAVE_INTERVAL == 0) e.save();
    reply(res, cb, 200, "{\"status\":\"ok\",\"calls\":" + n + "}");
  }

  /**
   * Reads the full request body as a UTF-8 string.
   * Blocks using Jetty's Blocker when no chunk is immediately available.
   */
  private static String readBody(Request request) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    while (true) {
      Content.Chunk chunk = request.read();
      if (chunk == null) {
        /* No data yet -- demand a callback and block until it fires. */
        try (Blocker.Runnable b = Blocker.runnable()) {
          request.demand(b);
          b.block();
        }
        continue;
      }
      if (Content.Chunk.isFailure(chunk)) {
        throw new IOException(String.valueOf(chunk.getFailure()));
      }
      boolean last = chunk.isLast();
      byte[] b = new byte[chunk.remaining()];
      chunk.getByteBuffer().get(b);
      chunk.release();
      out.write(b);
      if (last) break;
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  private static void reply(Response res, Callback cb, int status, String json) {
    res.setStatus(status);
    res.getHeaders().put(HttpHeader.CONTENT_TYPE, "application/json;charset=utf-8");
    Content.Sink.write(res, true, json, cb);
  }

  private static String err(String msg) {
    if (msg == null) msg = "unknown error";
    return "{\"error\":\"" + msg.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
  }

  public static void main(String[] args) throws Exception {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
    Server server = new Server(port);
    server.setHandler(new MLPd());
    server.start();
    System.out.printf("mlp-lite Jetty service on :%d%n", port);
    server.join();
  }
}
