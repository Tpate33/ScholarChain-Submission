import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * The JUnit runner main of `sbt sparkIT` (work order BE-P6-03 repair 01, CR-P6-16). Owner:
 * build-engineer.
 *
 * <p>A meta-build class written in plain Java with no Scala library and no compile-time JUnit
 * dependency (JUnit 4 is reached by reflection on the SparkIT classpath), so that its one class
 * file can be copied into the attempt directory and put first on the forked JVM's classpath. It
 * has no nested, inner or anonymous class: SparkItRuns copies exactly this one class file.
 *
 * <p>Arguments: {@code <nonce file> <result file> <test class>...}. In order:
 *
 * <ol>
 *   <li>exit 4 when any MUNIT_* variable is in its environment (for example MUNIT_FLAKY_OK, which
 *       turns flaky failures into skips); sbt sparkIT also strips them;
 *   <li>reads the one-time nonce (64 lower-case hex) from the nonce file and deletes the file,
 *       before any test class is loaded (exit 2 on failure), so test code cannot read it;
 *   <li>loads org.junit.runner.JUnitCore (exit 2 when absent);
 *   <li>runs each test class separately with a fresh JUnitCore and records, per class, its run,
 *       failure, ignored and assumption-failure counts (or an error line when it cannot be run);
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

  public static final String FORMAT = "scholarchain.sparkit.junit-result.v1";
  public static final int EXIT_PASS = 0;
  public static final int EXIT_TESTS_NOT_CLEAN = 1;
  public static final int EXIT_SETUP = 2;
  public static final int EXIT_RESULT_FILE = 3;
  public static final int EXIT_MUNIT_ENV = 4;

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

  private static String clip(String s) {
    if (s == null) return "";
    String t = s.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    return t.length() > 2000 ? t.substring(0, 2000) + "..." : t;
  }

  private static boolean isNonce(String s) {
    if (s.length() != 64) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    }
    return true;
  }

  private static int count(Object result, String method) throws Exception {
    Object v = result.getClass().getMethod(method).invoke(result);
    return ((Integer) v).intValue();
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
      say("cannot read and delete the nonce file: " + clip(e.getMessage()) + "; nothing ran");
      return EXIT_SETUP;
    }
    if (!isNonce(nonce)) {
      say("the nonce is not 64 lower-case hex; nothing ran");
      return EXIT_SETUP;
    }
    Class<?> core;
    Method runMethod;
    try {
      core = Class.forName("org.junit.runner.JUnitCore");
      runMethod = core.getMethod("run", Class[].class);
    } catch (ClassNotFoundException | NoSuchMethodException | LinkageError e) {
      say("JUnit 4 (org.junit.runner.JUnitCore) is not on the classpath; nothing ran");
      return EXIT_SETUP;
    }
    ClassLoader loader = SparkItJUnitRunner.class.getClassLoader();
    StringBuilder out = new StringBuilder();
    out.append("format=").append(FORMAT).append('\n');
    out.append("nonce=").append(nonce).append('\n');
    boolean clean = true;
    int classes = 0;
    for (int i = 2; i < args.length; i++) {
      String name = args[i];
      classes++;
      say("class " + name + ": starting");
      try {
        Class<?> c = Class.forName(name, false, loader);
        Object junit = core.getConstructor().newInstance();
        Object result = runMethod.invoke(junit, (Object) new Class<?>[] {c});
        int run = count(result, "getRunCount");
        int failures = count(result, "getFailureCount");
        int ignored = count(result, "getIgnoredCount");
        int assumptions = count(result, "getAssumptionFailureCount");
        Object list = result.getClass().getMethod("getFailures").invoke(result);
        if (list instanceof List) {
          for (Object f : (List<?>) list) {
            Object header = f.getClass().getMethod("getTestHeader").invoke(f);
            Object message = f.getClass().getMethod("getMessage").invoke(f);
            say("class " + name + ": FAILURE " + clip(String.valueOf(header)) + ": "
                + clip(String.valueOf(message)));
          }
        }
        out.append("class\t").append(name)
            .append("\trun=").append(run)
            .append("\tfailures=").append(failures)
            .append("\tignored=").append(ignored)
            .append("\tassumptionFailures=").append(assumptions)
            .append('\n');
        say("class " + name + ": run=" + run + " failures=" + failures + " ignored=" + ignored
            + " assumptionFailures=" + assumptions);
        if (run <= 0 || failures != 0 || ignored != 0 || assumptions != 0) clean = false;
      } catch (Throwable t) {
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        out.append("error\t").append(name).append('\t')
            .append(cause.getClass().getName()).append('\n');
        say("class " + name + ": could not be run: " + cause.getClass().getName() + ": "
            + clip(cause.getMessage()));
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
      say("cannot create the result file (it must not exist before): " + clip(e.getMessage()));
      return EXIT_RESULT_FILE;
    }
    say("result file written: " + classes + " class(es); " + (clean ? "clean" : "NOT clean"));
    return clean ? EXIT_PASS : EXIT_TESTS_NOT_CLEAN;
  }
}
