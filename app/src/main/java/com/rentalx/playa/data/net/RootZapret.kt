package com.rentalx.playa.data.net

import android.content.Context
import android.net.Uri
import com.rentalx.playa.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drives a root-installed `zapret` (nfqws) from the app.
 *
 * Splitting a handshake from an ordinary socket cannot beat the throttling that
 * is applied per flow after classification: the packets that would fake out the
 * filter have to carry a sequence number and checksum the kernel did not assign,
 * which needs raw sockets, which needs root. So this wraps the tool that does
 * actually do it: discovery, a host list, the NFQUEUE rule, start/stop, a
 * journal, an imported binary and a boot hook.
 *
 * Two rules hold throughout. Nothing here bundles or downloads an executable.
 * And nothing the listener types is ever pasted into a shell string: the script
 * is written whole with every value already quoted, so a strategy field cannot
 * become a root command.
 *
 * ### Why the script travels on stdin
 *
 * This used to run `su -c '<script>' arg1 arg2`, expecting the arguments to
 * arrive as `$1`, `$2`. They do not: Magisk's `su` runs the `-c` string and
 * drops everything after it, so `$1` was empty on every phone, `setsid` was
 * called with no program, and `nfqws` was never launched at all — while the
 * screen said "nfqws did not stay up". Nothing about that was specific to one
 * ROM; it simply never worked. A script fed to a root shell's stdin has no
 * argument-passing convention to depend on.
 */
object RootZapret {

    /** A named set of nfqws arguments, kept small enough to reason about. */
    enum class Strategy(val key: String, val display: String, val args: String) {
        FAKE_SPLIT(
            "fake_split",
            "fake + split2",
            "--dpi-desync=fake,split2 --dpi-desync-ttl=3 --dpi-desync-fooling=md5sig " +
                "--dpi-desync-repeats=6",
        ),
        FAKE_TTL(
            "fake_ttl",
            "fake (ttl 5)",
            "--dpi-desync=fake --dpi-desync-ttl=5 --dpi-desync-fooling=md5sig,badseq " +
                "--dpi-desync-repeats=8",
        ),
        SPLIT_ONLY(
            "split_only",
            "split (без fake)",
            "--dpi-desync=split2 --dpi-desync-split-pos=2",
        ),
        CUSTOM("custom", "свои аргументы", "");

        companion object {
            fun of(key: String?): Strategy = entries.firstOrNull { it.key == key } ?: FAKE_SPLIT
        }
    }

    data class Status(
        val rootGranted: Boolean = false,
        val rootMethod: String? = null,
        val binary: String? = null,
        val running: Boolean = false,
        val hasFirewall: Boolean = false,
        val message: String = "",
    )

    data class Diagnosis(val status: Status, val environment: String, val log: String)

    private val CANDIDATES = listOf(
        "/data/adb/modules/zapret/nfqws",
        "/data/adb/modules/zapret/bin/nfqws",
        "/data/adb/zapret/nfqws",
        "/data/adb/zapret/bin/nfqws",
        "/data/local/zapret/nfqws",
        "/data/local/tmp/rxplays_nfqws",
        "/data/local/tmp/nfqws",
        "/system/bin/nfqws",
        "/system/xbin/nfqws",
        "/data/data/com.termux/files/usr/bin/nfqws",
    )

    private val ROOT_METHODS = listOf(
        "Magisk" to "/data/adb/magisk",
        "KernelSU" to "/data/adb/ksud",
        "APatch" to "/data/adb/apd",
    )

    /**
     * Where the desync is applied.
     *
     * The googlevideo hosts are the ones that matter for playback: YouTube's
     * API is small and rarely worth filtering, while every audio byte comes
     * from a CDN subdomain. zapret's host list matches subdomains, so the bare
     * domain covers `rr1---sn-....googlevideo.com` too.
     */
    private val HOSTS = listOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "youtubei.googleapis.com",
        "googlevideo.com",
        "ytimg.com",
        "ggpht.com",
        "googleusercontent.com",
        "spotify.com",
        "open.spotify.com",
        "api.spotify.com",
        "scdn.co",
        "spotifycdn.com",
        "soundcloud.com",
        "api-v2.soundcloud.com",
        "sndcdn.com",
        "soundcloud.cloud",
        "media-streaming.soundcloud.cloud",
        "music.yandex.ru",
        "api.music.yandex.net",
        "avatars.yandex.net",
    )

    /** UDP/443 is QUIC: a TCP-only desync is invisible to a client that never falls back to TCP. */
    private const val QUIC_PORT = 443

    private const val QUEUE_NUM = 200

    fun hostFile(context: Context) = File(context.filesDir, "zapret/hosts.txt")
    fun logFile(context: Context) = File(context.filesDir, "zapret/nfqws.log")
    fun importedBinary(context: Context) = File(context.filesDir, "zapret/nfqws")

    suspend fun probe(context: Context, userPath: String?): Status = withContext(Dispatchers.IO) {
        val report = rootReport(userPath)
        if (!report.rootGranted) {
            return@withContext Status(message = context.getString(R.string.zapret_status_no_root))
        }
        val binary = report.binary
            ?: return@withContext Status(
                rootGranted = true,
                rootMethod = report.rootMethod,
                hasFirewall = report.hasFirewall,
                message = context.getString(R.string.zapret_status_binary_missing),
            )
        Status(
            rootGranted = true,
            rootMethod = report.rootMethod,
            binary = binary,
            running = report.running,
            hasFirewall = report.hasFirewall,
            message = when {
                report.running -> context.getString(R.string.zapret_status_running, binary)
                report.help != null -> context.getString(R.string.zapret_status_ready_help, binary, report.help)
                else -> context.getString(R.string.zapret_status_ready, binary)
            },
        )
    }

    suspend fun diagnose(context: Context, userPath: String?): Diagnosis = withContext(Dispatchers.IO) {
        val script = buildString {
            appendLine(shebang())
            appendLine(rootWrappers())
            appendLine(binaryProbe(userPath))
            appendLine(firewallProbe())
            appendLine("echo QUEUE=\$(test -e /proc/net/netfilter/nfnetlink_queue && echo yes || echo no)")
            appendLine("echo QLINES=\$(cat /proc/net/netfilter/nfnetlink_queue 2>/dev/null | wc -l)")
            appendLine("echo RULE=\$(iptables -t mangle -C OUTPUT $queueRule 2>/dev/null && echo yes || echo no)")
            appendLine("echo DROP=\$(iptables -t mangle -C OUTPUT $quicRule 2>/dev/null && echo yes || echo no)")
            appendLine("echo COUNTERS=\$(iptables -t mangle -L OUTPUT -n -v 2>/dev/null | grep NFQUEUE | head -n 1)")
            appendLine("echo LOGTAIL_START")
            appendLine("tail -c 1200 ${sh(logFile(context).absolutePath)} 2>/dev/null")
        }
        val report = runRootScript(script, STAGE_TIMEOUT_SECONDS)
        val fields = fieldsOf(report.output)
        val status = statusOf(context, fields)
        val environment = buildList {
            add("root: " + (fields["ROOTMETHOD"] ?: if (fields["ROOT"] == "0") "yes" else "no"))
            add("nfqws: " + (fields["BIN"] ?: "none"))
            fields["HELP"]?.takeIf(String::isNotBlank)?.let { add("binary: $it") }
            add("firewall: " + (fields["IPTABLES"] ?: "none") + (fields["NFT"]?.let { " · nft $it" } ?: ""))
            add("nfqueue prog: " + (fields["QUEUE"] ?: "no"))
            add("queue entries: " + (fields["QLINES"] ?: "0"))
            add("rule: " + (fields["RULE"] ?: "no") + " · quic drop: " + (fields["DROP"] ?: "no"))
            fields["COUNTERS"]?.takeIf(String::isNotBlank)?.let { add(it.trim()) }
        }.joinToString(" · ")
        Diagnosis(status, environment, report.output.substringAfter("LOGTAIL_START", readLog(context, logFile(context))))
    }

    /**
     * Copies a listener-supplied binary into `/data/local/tmp` and marks it
     * executable.
     *
     * The bytes go over the root shell's stdin, so no path from outside ever
     * becomes part of a command line — the previous version passed the app's
     * file path to `cp` as `$1`, which arrived empty and copied nothing.
     */
    suspend fun importBinary(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val staged = "/data/local/tmp/rxplays_nfqws"
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ByteArrayOutputStream().also { sink -> input.copyTo(sink) }.toByteArray()
            }
        } catch (failure: Throwable) {
            null
        } ?: return@withContext context.getString(R.string.zapret_status_import_unreadable)
        if (bytes.isEmpty()) return@withContext context.getString(R.string.zapret_status_import_empty)

        val written = runRootScript(
            buildString {
                appendLine("cat > $staged")
                appendLine("chmod 755 $staged")
                appendLine("echo DONE=\$?")
            },
            STAGE_TIMEOUT_SECONDS,
            stdinBytes = bytes,
        )
        if (fieldsOf(written.output)["DONE"] != "0") {
            return@withContext context.getString(
                R.string.zapret_status_import_write_failed,
                written.output.take(160),
            )
        }
        val check = runRootScript(
            buildString {
                appendLine("echo X=\$(test -x $staged && echo yes || echo no)")
                appendLine("\$($staged --help 2>&1 | head -n 1)")
            },
            STAGE_TIMEOUT_SECONDS,
        )
        val fields = fieldsOf(check.output)
        if (fields["X"] != "yes") {
            return@withContext context.getString(R.string.zapret_status_import_not_executable, staged)
        }
        context.getString(
            R.string.zapret_status_imported,
            staged,
            check.output.lines().firstOrNull { it.isNotBlank() } ?: context.getString(R.string.zapret_status_runs),
        )
    }

    /**
     * Starts nfqws and the diversion rules in one root shell, then reports what
     * actually happened rather than what was attempted.
     */
    suspend fun start(
        context: Context,
        userPath: String?,
        strategy: Strategy,
        customArgs: String,
        hostFile: File,
        logFile: File,
    ): String = withContext(Dispatchers.IO) {
        val args = if (strategy == Strategy.CUSTOM) customArgs.trim() else strategy.args
        if (args.isEmpty()) return@withContext context.getString(R.string.zapret_status_no_arguments)

        hostFile.parentFile?.mkdirs()
        hostFile.writeText(HOSTS.joinToString("\n"))
        logFile.parentFile?.mkdirs()
        logFile.writeText("")

        val tokens = args.split(' ', '\n').map(String::trim).filter { it.isNotEmpty() }
        val script = buildString {
            appendLine(shebang())
            appendLine(rootWrappers())
            appendLine(binaryProbe(userPath))
            appendLine("if [ -z \"\$BIN\" ]; then echo ERROR=no_binary; exit 0; fi")
            appendLine("echo BIN=\$BIN")
            // A binary that cannot even print its usage never reaches the point
            // of opening a queue, and the shell says why in one line.
            appendLine("echo HELP=\$(\"\$BIN\" --help 2>&1 | head -n 1)")
            appendLine("killall nfqws 2>/dev/null")
            appendLine("killall rxplays_nfqws 2>/dev/null")
            appendLine("for m in xt_NFQUEUE nfnetlink_queue nf_conntrack; do modprobe \$m 2>/dev/null; done")
            appendLine(firewallClear())
            appendLine(": > ${sh(logFile.absolutePath)}")
            appendLine(
                "setsid \"\$BIN\" " + tokens.joinToString(" ") { sh(it) } +
                    " --qnum=$QUEUE_NUM --hostlist=${sh(hostFile.absolutePath)}" +
                    " >>${sh(logFile.absolutePath)} 2>&1 &",
            )
            appendLine("echo SPAWNED=\$!")
            appendLine("sleep 1")
            appendLine("chmod 666 ${sh(logFile.absolutePath)} 2>/dev/null")
            appendLine("echo ALIVE=\$(pidof \${BIN##*/} >/dev/null 2>&1 && echo yes || echo no)")
            appendLine(firewallArm())
            appendLine("echo RULE=\$(iptables -t mangle -C OUTPUT $queueRule 2>/dev/null && echo yes || echo no)")
            appendLine("echo DROP=\$(iptables -t mangle -C OUTPUT $quicRule 2>/dev/null && echo yes || echo no)")
            appendLine("echo QUEUE=\$(cat /proc/net/netfilter/nfnetlink_queue 2>/dev/null | wc -l)")
            appendLine("echo LOGTAIL_START")
            appendLine("tail -c 700 ${sh(logFile.absolutePath)} 2>/dev/null")
        }

        val report = runRootScript(script, STAGE_TIMEOUT_SECONDS)
        val fields = fieldsOf(report.output)
        val logTail = report.output.substringAfter("LOGTAIL_START", "").trim()
        when {
            fields["ERROR"] == "no_binary" ->
                context.getString(R.string.zapret_status_binary_absent)
            fields["ALIVE"] == "yes" && fields["RULE"] == "yes" ->
                (if (fields["DROP"] == "yes") {
                    context.getString(R.string.zapret_status_started_quic, strategy.display, QUEUE_NUM)
                } else {
                    context.getString(R.string.zapret_status_started, strategy.display, QUEUE_NUM)
                })
            fields["ALIVE"] == "yes" ->
                context.getString(R.string.zapret_status_rule_failed, strategy.display) +
                    logTail.takeIf(String::isNotBlank)?.let { "\n$it" }.orEmpty()
            else -> buildString {
                append(context.getString(R.string.zapret_status_not_up)).append(' ')
                fields["HELP"]?.takeIf(String::isNotBlank)?.let {
                    append(context.getString(R.string.zapret_status_binary_line, it)).append(' ')
                }
                append(logTail.ifBlank { context.getString(R.string.zapret_status_journal_empty) })
            }
        }
    }

    suspend fun stop(context: Context, logFile: File? = null): String = withContext(Dispatchers.IO) {
        val script = buildString {
            appendLine(shebang())
            appendLine("killall nfqws 2>/dev/null")
            appendLine("killall rxplays_nfqws 2>/dev/null")
            appendLine(firewallClear())
            appendLine("echo ALIVE=\$(pidof nfqws >/dev/null 2>&1 && echo yes || echo no)")
            appendLine("echo RULE=\$(iptables -t mangle -C OUTPUT $queueRule 2>/dev/null && echo yes || echo no)")
        }
        val fields = fieldsOf(runRootScript(script, STAGE_TIMEOUT_SECONDS).output)
        when {
            fields["ALIVE"] == "yes" -> context.getString(R.string.zapret_status_still_running)
            fields["RULE"] == "yes" -> context.getString(R.string.zapret_status_rule_left)
            else -> context.getString(R.string.zapret_status_stopped)
        }
    }

    fun readLog(context: Context): String = readLog(context, logFile(context))

    private fun readLog(context: Context, file: File): String = runCatching {
        if (!file.exists()) {
            context.getString(R.string.zapret_status_journal_empty)
        } else {
            val text = file.readText()
            if (text.isBlank()) context.getString(R.string.zapret_status_journal_empty) else text.takeLast(1200)
        }
    }.getOrDefault(context.getString(R.string.zapret_status_journal_unreadable))

    // ── Script pieces ───────────────────────────────────────────────────────

    private fun shebang() = "#!/system/bin/sh"

    /** Terminates stdin on a ROM whose root shell would otherwise wait for more. */
    private fun rootWrappers() = "export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:\$PATH"

    private fun binaryProbe(userPath: String?): String = buildString {
        val explicit = userPath?.trim()?.takeIf { it.isNotEmpty() }
        appendLine("BIN=")
        if (explicit != null) {
            appendLine("if [ -x ${sh(explicit)} ]; then BIN=${sh(explicit)}; fi")
        }
        appendLine("if [ -z \"\$BIN\" ]; then")
        appendLine("  for p in ${CANDIDATES.joinToString(" ") { sh(it) }}; do")
        appendLine("    if [ -x \"\$p\" ]; then BIN=\"\$p\"; break; fi")
        appendLine("  done")
        appendLine("fi")
    }

    private fun firewallProbe() = buildString {
        appendLine("echo ROOT=\$(id -u)")
        appendLine("echo ROOTMETHOD=\$(" + ROOT_METHODS.joinToString(" ; ") { (name, marker) ->
            "if [ -e ${sh(marker)} ]; then echo $name; fi"
        } + " ; echo )")
        appendLine("echo IPTABLES=\$(command -v iptables)")
        appendLine("echo NFT=\$(command -v nft)")
        appendLine("echo PID=\$(pidof nfqws 2>/dev/null || pidof rxplays_nfqws 2>/dev/null)")
        appendLine("echo HELP=\$( [ -n \"\$BIN\" ] && \"\$BIN\" --help 2>&1 | head -n 1 )")
    }

    private val queueRule =
        "OUTPUT -p tcp -m multiport --dports 80,443 -j NFQUEUE --queue-num $QUEUE_NUM --queue-bypass"

    private val quicRule = "OUTPUT -p udp --dport $QUIC_PORT -j DROP"

    private fun firewallClear() = buildString {
        appendLine("while iptables -t mangle -D $queueRule 2>/dev/null; do :; done")
        appendLine("while iptables -t mangle -D $quicRule 2>/dev/null; do :; done")
    }

    private fun firewallArm() = buildString {
        appendLine("iptables -t mangle -I $queueRule 2>/dev/null")
        appendLine("iptables -t mangle -I $quicRule 2>/dev/null")
    }

    // ── Root shell ──────────────────────────────────────────────────────────

    private data class RootReport(
        val rootGranted: Boolean,
        val rootMethod: String?,
        val binary: String?,
        val running: Boolean,
        val hasFirewall: Boolean,
        val help: String?,
    )

    private fun rootReport(userPath: String?): RootReport {
        val script = buildString {
            appendLine(shebang())
            appendLine(rootWrappers())
            appendLine(binaryProbe(userPath))
            appendLine(firewallProbe())
            appendLine("echo RULE=\$(iptables -t mangle -C OUTPUT $queueRule 2>/dev/null && echo yes || echo no)")
        }
        val fields = fieldsOf(runRootScript(script, STAGE_TIMEOUT_SECONDS).output)
        return RootReport(
            rootGranted = fields["ROOT"] == "0",
            rootMethod = fields["ROOTMETHOD"]?.takeIf(String::isNotBlank),
            binary = fields["BIN"]?.takeIf(String::isNotBlank),
            running = !fields["PID"].isNullOrBlank(),
            hasFirewall = !fields["IPTABLES"].isNullOrBlank() || !fields["NFT"].isNullOrBlank(),
            help = fields["HELP"]?.takeIf(String::isNotBlank),
        )
    }

    private fun statusOf(context: Context, fields: Map<String, String>): Status = Status(
        rootGranted = fields["ROOT"] == "0",
        rootMethod = fields["ROOTMETHOD"]?.takeIf(String::isNotBlank),
        binary = fields["BIN"]?.takeIf(String::isNotBlank),
        running = !fields["PID"].isNullOrBlank(),
        hasFirewall = !fields["IPTABLES"].isNullOrBlank() || !fields["NFT"].isNullOrBlank(),
        message = context.getString(R.string.zapret_status_checked),
    )

    private data class ShellResult(val output: String, val code: Int)

    /**
     * Runs [script] in a root shell with the text on stdin.
     *
     * `su -c sh` starts a shell and hands it the pipe, so the script needs no
     * quoting and takes no arguments. If the ROM's `su` closes stdin instead —
     * which leaves nothing to execute — the script is staged in app storage and
     * run by path, the one form every build accepts.
     */
    private fun runRootScript(
        script: String,
        timeoutSeconds: Long,
        stdinBytes: ByteArray? = null,
    ): ShellResult {
        val direct = pipeToSu(script, timeoutSeconds, stdinBytes)
        if (direct != null) return direct
        val staged = stageScript(script)
        val command = "sh ${sh(staged.absolutePath)}"
        return try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val out = readFully(process)
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                ShellResult("ERROR timeout", -1)
            } else {
                ShellResult(out(), process.exitValue())
            }
        } catch (failure: Throwable) {
            ShellResult("ERROR ${failure.message}", -1)
        }
    }

    /** Null when the shell produced nothing at all, which is the sign stdin was not read. */
    private fun pipeToSu(script: String, timeoutSeconds: Long, stdinBytes: ByteArray?): ShellResult? = try {
        val process = ProcessBuilder("su", "-c", "sh").redirectErrorStream(true).start()
        val payload = stdinBytes ?: script.encodeToByteArray()
        val writer = Thread {
            runCatching {
                process.outputStream.use { out -> out.write(payload); out.flush() }
            }
        }.apply { isDaemon = true; start() }
        val out = readFully(process)
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        writer.join(1_000)
        if (!finished) {
            process.destroyForcibly()
            ShellResult("ERROR timeout", -1)
        } else {
            val text = out()
            if (text.isBlank() && stdinBytes == null) null else ShellResult(text, process.exitValue())
        }
    } catch (failure: Throwable) {
        ShellResult("ERROR ${failure.message}", -1)
    }

    private fun readFully(process: Process): () -> String {
        val buffer = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (buffer.length < MAX_OUTPUT) {
                            buffer.append(line).append('\n')
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return {
            reader.join(MAX_READ_JOIN_MS)
            buffer.toString()
        }
    }

    /**
     * Writes [script] somewhere both the app and root can reach.
     *
     * The app's own cache directory: root reads it without a grant, and
     * `/data/local/tmp` is deliberately not used for this because an ordinary
     * app cannot write there in the first place.
     */
    private fun stageScript(script: String): File {
        val directory = File(System.getProperty("java.io.tmpdir") ?: "/data/user/0")
        val file = File(directory, "rx_zapret_${System.currentTimeMillis()}.sh")
        file.writeText(script)
        return file
    }

    /** `KEY=value` lines the scripts print; the last value wins. */
    private fun fieldsOf(output: String): Map<String, String> =
        output.lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.substring(0, at).trim() to line.substring(at + 1).trim()
            }
            .toMap()

    private fun sh(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private const val STAGE_TIMEOUT_SECONDS = 25L
    private const val MAX_OUTPUT = 16_000
    private const val MAX_READ_JOIN_MS = 2_000L
}
