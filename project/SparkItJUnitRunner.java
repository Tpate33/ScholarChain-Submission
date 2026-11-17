import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.util.List;

/**
 * The JUnit runner main of `sbt sparkIT` (work orders BE-P6-03 repair 01, CR-P6-16, BE-P6-06).
 * Owner: build-engineer.
 *
 * <p>A meta-build class written in plain Java with no Scala library and no compile-time JUnit
 * dependency (JUnit 4 is reached by reflection on the SparkIT classpath), so that its one class
 * file can be copied into the attempt directory and put first on the forked JVM's classpath. It
 * has no nested, inner or anonymous class and no lambda: SparkItRuns copies exactly this one class
 * file.
 *
 * <p>Reflective names (BE-P6-06): every name below is public API of JUnit 4.13.2 (the version the
 * build asserts, ledger key {@code junit}; javap of junit-4.13.2.jar as recorded by the human in
 * prompt-sparkit-runner-repair.md, and exercised on the real jar by {@code verifySparkItRunner} in
 * {@code sbt check}): {@code org.junit.runner.JUnitCore()} and {@code run(Class...)};
 * {@code org.junit.runner.Result.getFailures()}, {@code getRunCount()}, {@code getFailureCount()},
 * {@code getIgnoreCount()} (not getIgnoredCount, the run 03 defect) and
 * {@code getAssumptionFailureCount()} (since 4.13); {@code
 * org.junit.runner.notification.Failure.getTestHeader()}, {@code getException()} and {@code
 * getMessage()}. All are looked up once, before any test class is loaded; a missing name is a setup
 * failure (exit 2, no result file).
 *
 * <p>Arguments: {@code <nonce file> <result file> <test class>...}. In order:
 *
 * <ol>
 *   <li>exit 4 when any MUNIT_* variable is in its environment (for example MUNIT_FLAKY_OK, which
 *       turns flaky failures into skips); sbt sparkIT also strips them;
 *   <li>reads the one-time nonce (64 lower-case hex) from the nonce file and deletes the file,
 *       before any test class is loaded (exit 2 on failure), so test code cannot read it;
 *   <li>loads org.junit.runner.JUnitCore, Result and Failure and resolves every reflective method
 *       (exit 2 when any is absent), and records where JUnitCore and Result were loaded from (code
 *       source path and Implementation-Version);
 *   <li>runs each test class separately with a fresh JUnitCore; per class it reads and records the
 *       failures FIRST (class, test header, exception class, message; at most {@link
 *       #MAX_FAILURE_LINES}), then the run, failure, ignored and assumption-failure counts; any
 *       error records an error line naming the stage, and failures already recorded stay;
 *   <li>after the last class, writes the result file once (CREATE_NEW; exit 3 when it exists or
 *       cannot be written), ending in {@code complete=<nonce>}, then exits 0 when every class ran
 *       at least one test with no failure, no ignored test and no assumption failure, else 1.
 * </ol>
 *
 * A test that calls System.exit, or a JVM stopped at the bound, leaves no result file; a test
 * that prints a summary changes nothing, because sbt sparkIT decides from the result file, the
 * nonce and the exit code only.
 */
public final class SparkItJUnitRunner {

  public static final String FORMAT = "scholarchain.sparkit.junit-result.v2";
  public static final int EXIT_PASS = 0;
  public static final int EXIT_TESTS_NOT_CLEAN = 1;
  public static final int EXIT_SETUP = 2;
  public static final int EXIT_RESULT_FILE = 3;
  public static final int EXIT_MUNIT_ENV = 4;

  /** At most this many failure lines per class are recorded (the counts are always recorded). */
  public static final int MAX_FAILURE_LINES = 100;

  public static final int MAX_FIELD_CHARS = 500;
  public static final int MAX_PATH_CHARS = 4000;

  /** Written when a datum cannot be determined; the reader treats it as missing. */
  public static final String UNKNOWN = "-";

  public static final String STAGE_RUN = "run";
  public static final String STAGE_FAILURES = "failures";
  public static final String STAGE_COUNTS = "counts";

  private SparkItJUnitRunner() {}

  public static void main(String[] args) {
    int code = run(args);
    System.out.flush();
    System.err.flush();
    System.exit(code);
  }

  private static void say(String message) {
    System.out.println("[sparkit-runner] " + message);
  }

  /** One result-file field: control characters (tab, CR, LF included) become spaces; clipped. */
  static String field(String s, int max) {
    if (s == null) return "";
    StringBuilder b = new StringBuilder();
    int n = Math.min(s.length(), max);
    for (int i = 0; i < n; i++) {
      char c = s.charAt(i);
      b.append(Character.isISOControl(c) ? ' ' : c);
    }
    if (s.length() > max) b.append("...");
    return b.toString();
  }

  private static String field(String s) {
    return field(s, MAX_FIELD_CHARS);
  }

  private static boolean isNonce(String s) {
    if (s.length() != 64) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    }
    return true;
  }

  /** The file system path of the class's code source, or UNKNOWN. */
  static String loadedFrom(Class<?> c) {
    try {
      CodeSource cs = c.getProtectionDomain().getCodeSource();
      URL u = cs == null ? null : cs.getLocation();
      if (u == null) return UNKNOWN;
      String p = Paths.get(u.toURI()).toString();
      return p.isEmpty() ? UNKNOWN : field(p, MAX_PATH_CHARS);
    } catch (Exception | LinkageError e) {
      return UNKNOWN;
    }
  }

  /** The Implementation-Version of the class's package (jar manifest), or UNKNOWN. */
  static String implementationVersion(Class<?> c) {
    Package p = c.getPackage();
    String v = p == null ? null : p.getImplementationVersion();
    return v == null || v.isEmpty() ? UNKNOWN : field(v);
  }

  private static int intOf(Method m, Object target) throws Exception {
    Object v = m.invoke(target);
    if (!(v instanceof Integer)) {
      throw new IllegalStateException(m.getName() + " did not return an int");
    }
    return ((Integer) v).intValue();
  }

  private static Throwable rootOf(Throwable t) {
    return t.getCause() != null ? t.getCause() : t;
  }

  private static void error(StringBuilder out, String name, String stage, Throwable t) {
    Throwable cause = rootOf(t);
    out.append("error\t").append(name).append('\t').append(stage).append('\t')
        .append(field(cause.getClass().getName())).append('\n');
    say("class " + name + ": could not be run (stage " + stage + "): "
        + cause.getClass().getName() + ": " + field(cause.getMessage()));
  }

  static int run(String[] args) {
    if (args.length < 3) {
      say("usage: <nonce file> <result file> <test class>...; nothing ran");
      return EXIT_SETUP;
    }
    for (String name : System.getenv().keySet()) {
      if (name.startsWith("MUNIT_")) {
        say("refused: " + name + " is set in the environment; nothing ran");
        return EXIT_MUNIT_ENV;
      }
    }
    Path nonceFile = Paths.get(args[0]);
    Path resultFile = Paths.get(args[1]);
    String nonce;
    try {
      if (!Files.isRegularFile(nonceFile, LinkOption.NOFOLLOW_LINKS)) {
        say("the nonce file is missing or not a regular file; nothing ran");
        return EXIT_SETUP;
      }
      nonce = new String(Files.readAllBytes(nonceFile), StandardCharsets.US_ASCII).trim();
      Files.delete(nonceFile);
    } catch (IOException e) {
      say("cannot read and delete the nonce file: " + field(e.getMessage()) + "; nothing ran");
      return EXIT_SETUP;
    }
    if (!isNonce(nonce)) {
      say("the nonce is not 64 lower-case hex; nothing ran");
      return EXIT_SETUP;
    }
    Class<?> core;
    Class<?> resultClass;
    Method runMethod;
    try {
      core = Class.forName("org.junit.runner.JUnitCore");
      resultClass = Class.forName("org.junit.runner.Result");
      runMethod = core.getMethod("run", Class[].class);
    } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
      say("JUnit 4 (org.junit.runner.JUnitCore, Result) is not on the classpath; nothing ran");
      return EXIT_SETUP;
    }
    Method getFailures;
    Method getRunCount;
    Method getFailureCount;
    Method getIgnoreCount;
    Method getAssumptionFailureCount;
    Method getTestHeader;
    Method getException;
    Method getMessage;
    try {
      Class<?> failureClass = Class.forName("org.junit.runner.notification.Failure");
      getFailures = resultClass.getMethod("getFailures");
      getRunCount = resultClass.getMethod("getRunCount");
      getFailureCount = resultClass.getMethod("getFailureCount");
      getIgnoreCount = resultClass.getMethod("getIgnoreCount");
      getAssumptionFailureCount = resultClass.getMethod("getAssumptionFailureCount");
      getTestHeader = failureClass.getMethod("getTestHeader");
      getException = failureClass.getMethod("getException");
      getMessage = failureClass.getMethod("getMessage");
    } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
      say("the JUnit on the classpath lacks a required API member (" + e.getClass().getName()
          + ": " + field(e.getMessage()) + "); JUnitCore from " + loadedFrom(core)
          + "; nothing ran");
      return EXIT_SETUP;
    }
    ClassLoader loader = SparkItJUnitRunner.class.getClassLoader();
    StringBuilder out = new StringBuilder();
    out.append("format=").append(FORMAT).append('\n');
    out.append("nonce=").append(nonce).append('\n');
    String coreFrom = loadedFrom(core);
    String coreVersion = implementationVersion(core);
    String resultFrom = loadedFrom(resultClass);
    String resultVersion = implementationVersion(resultClass);
    out.append("junit\tJUnitCore\t").append(coreFrom).append('\t').append(coreVersion).append('\n');
    out.append("junit\tResult\t").append(resultFrom).append('\t').append(resultVersion)
        .append('\n');
    say("JUnit: org.junit.runner.JUnitCore loaded from " + coreFrom + " (Implementation-Version "
        + coreVersion + "); org.junit.runner.Result loaded from " + resultFrom
        + " (Implementation-Version " + resultVersion + ")");
    boolean clean = true;
    int classes = 0;
    for (int i = 2; i < args.length; i++) {
      String name = args[i];
      classes++;
      say("class " + name + ": starting");
      Object result;
      try {
        Class<?> c = Class.forName(name, false, loader);
        Object junit = core.getConstructor().newInstance();
        result = runMethod.invoke(junit, (Object) new Class<?>[] {c});
      } catch (Throwable t) {
        error(out, name, STAGE_RUN, t);
        clean = false;
        continue;
      }
      // Failures first, so that no later lookup can hide them.
      int listed;
      try {
        Object list = getFailures.invoke(result);
        if (!(list instanceof List)) {
          throw new IllegalStateException("Result.getFailures did not return a List");
        }
        List<?> failures = (List<?>) list;
        listed = failures.size();
        int recorded = 0;
        for (Object f : failures) {
          if (recorded >= MAX_FAILURE_LINES) break;
          String header = field(String.valueOf(getTestHeader.invoke(f)));
          Object ex = getException.invoke(f);
          String exClass = ex == null ? UNKNOWN : field(ex.getClass().getName());
          String message = field(String.valueOf(getMessage.invoke(f)));
          out.append("failure\t").append(name).append('\t').append(header).append('\t')
              .append(exClass).append('\t').append(message).append('\n');
          say("class " + name + ": FAILURE " + header + ": " + exClass + ": " + message);
          recorded++;
        }
        if (listed > recorded) {
          say("class " + name + ": " + (listed - recorded) + " further failure(s) not listed");
        }
      } catch (Throwable t) {
        error(out, name, STAGE_FAILURES, t);
        clean = false;
        continue;
      }
      int run;
      int failureCount;
      int ignored;
      int assumptions;
      try {
        run = intOf(getRunCount, result);
        failureCount = intOf(getFailureCount, result);
        ignored = intOf(getIgnoreCount, result);
        assumptions = intOf(getAssumptionFailureCount, result);
      } catch (Throwable t) {
        error(out, name, STAGE_COUNTS, t);
        clean = false;
        continue;
      }
      out.append("class\t").append(name)
          .append("\trun=").append(run)
          .append("\tfailures=").append(failureCount)
          .append("\tignored=").append(ignored)
          .append("\tassumptionFailures=").append(assumptions)
          .append('\n');
      say("class " + name + ": run=" + run + " failures=" + failureCount + " ignored=" + ignored
          + " assumptionFailures=" + assumptions);
      if (run <= 0 || failureCount != 0 || listed != failureCount || ignored != 0
          || assumptions != 0) {
        clean = false;
      }
    }
    out.append("classes=").append(classes).append('\n');
    out.append("complete=").append(nonce).append('\n');
    try {
      Files.write(
          resultFile,
          out.toString().getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
    } catch (IOException e) {
      say("cannot create the result file (it must not exist before): " + field(e.getMessage()));
      return EXIT_RESULT_FILE;
    }
    say("result file written: " + classes + " class(es); " + (clean ? "clean" : "NOT clean"));
    return clean ? EXIT_PASS : EXIT_TESTS_NOT_CLEAN;
  }
}
