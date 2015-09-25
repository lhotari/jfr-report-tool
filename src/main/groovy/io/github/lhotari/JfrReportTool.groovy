package io.github.lhotari

import com.google.common.util.concurrent.AtomicLongMap
import com.jrockit.mc.common.IMCFrame
import com.jrockit.mc.flightrecorder.FlightRecording
import com.jrockit.mc.flightrecorder.FlightRecordingLoader
import com.jrockit.mc.flightrecorder.internal.model.FLRStackTrace
import com.jrockit.mc.flightrecorder.spi.IEvent
import com.jrockit.mc.flightrecorder.spi.IEventFilter
import com.jrockit.mc.flightrecorder.spi.IView
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

import java.lang.reflect.Method
import java.util.regex.Pattern

@CompileStatic
class JfrReportTool {
    private static
    final Map<String, String> DEFAULT_EXTENSION = [flameGraph: 'svg', stacks: 'txt', topframes: 'top.txt']
    private static final String SAMPLING_EVENT_TYPE = "Method Profiling Sample"
    Pattern excludeFilter = ~/^(java\.|sun\.|com\.sun\.|org\.codehaus\.groovy\.|groovy\.|org\.apache\.)/
    Pattern includeFilter = null
    Pattern grepFilter = null
    int flameGraphWidth = 1850
    String flameGraphCommand = "flamegraph.pl"
    boolean sortFrames = false
    int minimumSamples = 3

    @ReportAction("creates flamegraph in svg format, default action")
    def flameGraph(File jfrFile, File outputFile) {
        ProcessBuilder builder = new ProcessBuilder(flameGraphCommand, "--width", flameGraphWidth.toString())
        builder.redirectOutput(outputFile)
        Process process = builder.start()
        convertToFlameGraphFormat(jfrFile, new OutputStreamWriter(process.getOutputStream()))
    }

    @ReportAction("creates flamegraph input file")
    def stacks(File jfrFile, File outputFile) {
        outputFile.withWriter { writer ->
            convertToFlameGraphFormat(jfrFile, writer)
        }
    }

    @ReportAction("shows top methods")
    def topframes(File jfrFile, File outputFile) {
        outputFile.withWriter { writer ->
            AtomicLongMap<String> methodCounts = AtomicLongMap.create()
            forEachFLRStackTrace(jfrFile) { FLRStackTrace flrStackTrace ->
                def stackTrace = flrStackTrace.frames.collect { frame ->
                    // getHumanReadable(boolean showReturnValue, boolean useQualifiedReturnValue, boolean showClassName, boolean useQualifiedClassName, boolean showArguments, boolean useQualifiedArguments)
                    ((IMCFrame) frame).method.getHumanReadable(false, true, true, true, true, true)
                }
                if (matchesGrepFilter(stackTrace)) {
                    stackTrace.each { String methodSignature ->
                        if (matchesMethod(methodSignature)) {
                            methodCounts.incrementAndGet(methodSignature)
                        }
                    }
                }
            }
            writeStackCounts(methodCounts.asMap(), writer, true)
            writer.close()
        }
    }

    boolean matchesGrepFilter(List<String> stackTrace) {
        grepFilter == null || stackTrace.any { String methodSignature -> methodSignature =~ grepFilter }
    }

    private boolean matchesMethod(String methodSignature) {
        (includeFilter == null || methodSignature =~ includeFilter) && (excludeFilter == null || !(methodSignature =~ excludeFilter))
    }

    void forEachFLRStackTrace(File jfrFile,
                              @ClosureParams(value = SimpleType, options = "com.jrockit.mc.flightrecorder.internal.model.FLRStackTrace") Closure<?> handler) {
        FlightRecording recording = FlightRecordingLoader.loadFile(jfrFile)
        IView view = recording.createView()
        view.setFilter(new IEventFilter() {
            boolean accept(IEvent iEvent) {
                iEvent.eventType.name == SAMPLING_EVENT_TYPE
            }
        })
        for (IEvent event : view) {
            FLRStackTrace flrStackTrace = (FLRStackTrace) event.getValue("(stackTrace)")
            handler(flrStackTrace)
        }
    }

    def convertToFlameGraphFormat(File jfrFile, Writer writer) {
        AtomicLongMap<String> stackCounts = AtomicLongMap.create()
        forEachFLRStackTrace(jfrFile) { FLRStackTrace flrStackTrace ->
            def stackTrace = flrStackTrace.frames.collect { frame ->
                // getHumanReadable(boolean showReturnValue, boolean useQualifiedReturnValue, boolean showClassName, boolean useQualifiedClassName, boolean showArguments, boolean useQualifiedArguments)
                ((IMCFrame) frame).method.getHumanReadable(false, true, true, true, true, true)
            }
            if (matchesGrepFilter(stackTrace)) {
                def filtered = stackTrace.findAll { matchesMethod(it) }

                def flameGraphFormatted = filtered.reverse().collect { formatMethodName(it) }.join(';')

                stackCounts.incrementAndGet(flameGraphFormatted)
            }
        }
        writeStackCounts(stackCounts.asMap(), writer, sortFrames)
        writer.close()
    }

    private void writeStackCounts(Map<String, Long> map, Writer writer, boolean sort) {
        def writeEntry = { Map.Entry<String, Long> entry ->
            if (entry.value > minimumSamples) {
                writer.write entry.key
                writer.write ' '
                writer.write entry.value.toString()
                writer.write '\n'
            }
        }
        sort ? map.collect { entry -> entry }.sort { a, b -> b.value <=> a.value }.each(writeEntry) : map.each(writeEntry)
    }

    String formatMethodName(String s) {
        List<String> m = (List<String>) (s =~ /^(.*?)\((.*)\)$/).find { it }
        if (m) {
            return { String classAndMethod, String arguments ->
                def compactMethod = classAndMethod.split(/\./).takeRight(3).join('.')
                def compactArgs = arguments.split(/, /).collect { String argType ->
                    argType.split(/\./).last()
                }.join(', ')
                "$compactMethod(${compactArgs ?: ''})".toString()
            }(m[1], m[2])
        }
        s
    }

    static Map<String, String> scanReportActions(Class clazz) {
        Map<String, String> reportActions = [:]
        clazz.getDeclaredMethods().each { Method method ->
            ReportAction reportAction = method.getAnnotation(ReportAction)
            if (reportAction) {
                reportActions.put(method.name, reportAction.value())
            }
        }
        reportActions
    }

    @CompileDynamic
    public static void main(String[] args) {
        def cli = new CliBuilder()
        def reportActions = scanReportActions(JfrReportTool)
        cli.with {
            h 'Help', longOpt: 'help'
            i 'Regexp include filter for methods', longOpt: 'include', args: 1, argName: 'filter'
            e 'Regexp exclude filter for methods', longOpt: 'exclude', args: 1, argName: 'filter'
            g 'Regexp to include all stacks with match in any frame', longOpt: 'grep', args: 1, argName: 'filter'
            a 'Tool action', longOpt: 'action', args: 1, argName: 'action'
            o 'Output file', longOpt: 'output', args: 1, argName: 'file'
            w 'Width of flamegraph', longOpt: 'width', args: 1, argName: 'pixels'
            _ 'flamegraph.pl path', longOpt: 'flamegraph-command', args: 1, argName: 'cmd'
            s 'Sort frames', longOpt: 'sort'
            m 'Minimum number of samples', longOpt: 'min', args: 1, argName: 'value'
        }
        cli.usage = "jfr-report-tool [-${cli.options.options.opt.findAll { it }.sort().join('')}] [jfrFile]"

        def options = cli.parse(args)
        if (options.h || !options.arguments()) {
            cli.usage()
            println "Supported actions:"
            reportActions.each { String action, String description ->
                println "${action.padRight(33)}${description}"
            }
            return
        }

        def action = options.action ?: 'flameGraph'

        if (!reportActions.containsKey(action)) {
            println "Unknown action $action"
            System.exit(1)
        }

        def jfrReportTool = new JfrReportTool()
        if (options.i) {
            jfrReportTool.includeFilter = Pattern.compile(options.i)
        }
        if (options.e) {
            jfrReportTool.excludeFilter = (options.e == 'none') ? null : Pattern.compile(options.e)
        }
        if (options.g) {
            jfrReportTool.grepFilter = Pattern.compile(options.g)
        }
        if (options.w) {
            jfrReportTool.flameGraphWidth = options.w as int
        }
        if (options.'flamegraph-command') {
            jfrReportTool.flameGraphCommand = options.'flamegraph-command'
        }
        if (options.m) jfrReportTool.minimumSamples = options.m as int
        if (options.s) jfrReportTool.sortFrames = true

        Closure methodClosure = jfrReportTool.&"$action"
        def file = new File(options.arguments().first()).absoluteFile
        def outputFile = (options.o) ? new File(String.valueOf(options.o)) : new File(file.parentFile, file.name + "." + (DEFAULT_EXTENSION[action] ?: 'svg'))
        if (methodClosure.maximumNumberOfParameters == 2) {
            println "Converting $file"
            methodClosure(file, outputFile)
            println "Output in ${outputFile}"
            println "URL ${outputFile.canonicalFile.toURI().toURL()}"
        } else if (methodClosure.maximumNumberOfParameters == 1) {
            methodClosure([input: file, output: outputFile, arguments: options.arguments(), options: options])
        } else {
            println "Unsupported action"
        }
    }
}


