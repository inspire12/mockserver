import { ChildProcess } from 'child_process';

/**
 * What `require('mockserver-node')` returns: index.js assigns this object to `module.exports`, so
 * there is no default export.
 */
declare namespace mockserverNode {
  interface StartServerOptions {
    serverPort: number;
    jvmOptions?: string[] | string;
    artifactoryHost?: string;
    artifactoryPath?: string;
    mockServerVersion?: string;
    /**
     * Absolute or relative path to a pre-provisioned mockserver-netty
     * jar-with-dependencies. When set (or via the MOCKSERVER_JAR_PATH environment
     * variable), this exact jar is launched and no download is attempted; a
     * missing path is a hard error rather than a silent fall-back to a released
     * jar. Intended for air-gapped/corporate use and for testing a locally-built
     * jar. Takes precedence over mockServerVersion / artifactory* download options.
     */
    jarPath?: string;
    initializationJsonPath?: string;
    trace?: boolean;
    verbose?: boolean;
    /**
     * How many times the readiness check is retried, 100 milliseconds apart: 110 by default, 500 when
     * javaDebugPort is set. A check left unanswered for 2 seconds uses up 20 further retries. A start
     * that runs out of retries rejects, and the JVM it launched is stopped unless javaDebugPort is set.
     */
    startupRetries?: number;
    /**
     * Starts the JVM suspended until a debugger attaches on this port. A start that gives up waiting
     * leaves this JVM running, and says so in its rejection.
     */
    javaDebugPort?: number;
    proxyRemotePort?: number;
    proxyRemoteHost?: string;
    /**
     * By default the launched MockServer is ended if the calling process meets an uncaught exception,
     * which is otherwise left to Node and to the caller's own handlers: it is ended even when one of
     * those handles the exception and the process carries on. Only the java process the launcher started
     * is signalled, so a java wrapper script that does not exec the JVM leaves the JVM running. Set to
     * true to leave MockServer running.
     */
    runForked?: boolean;
  }

  interface StopServerOptions {
    serverPort: number;
    verbose?: boolean;
  }

  /** Exit status of the launched MockServer java process once it has terminated. */
  interface MockServerExit {
    code: number | null;
    signal: NodeJS.Signals | null;
  }

  /**
   * MockServer's answer to the request the launcher polls it with until it has started. Any answer
   * counts as started, whatever its status.
   */
  interface ReadinessResponse {
    statusCode: number;
    body: string;
  }

  /**
   * Launches MockServer with the `java` found on the PATH of this process (JAVA_HOME is not used) and
   * resolves, with its first answer, once it answers. Rejects with an Error, also printed to stderr, and never ends the calling
   * process, when: java cannot be run (`code` is 'ENOENT' when there is none on the PATH, 'EACCES' when
   * it is not executable); java exits with a failing status or is ended by a signal before MockServer is
   * ready (`exitCode` and `signal` are set, and the message ends with its last output); or MockServer
   * does not become ready in the time `startupRetries` allows. A missing or renamed option rejects with
   * a string.
   */
  function start_mockserver(options: StartServerOptions): Promise<ReadinessResponse>;
  function stop_mockserver(options: StopServerOptions): Promise<void>;
  /** The launched MockServer java child process, or undefined before the first start. */
  function getMockServerProcess(): ChildProcess | undefined;
  /** The java process exit status once it has died; undefined while it is still running. */
  function getMockServerExit(): MockServerExit | undefined;
  /** The captured stdout+stderr tail (optionally limited to the last `maxLines` non-empty lines). */
  function getMockServerOutput(maxLines?: number): string;
}

export = mockserverNode;
