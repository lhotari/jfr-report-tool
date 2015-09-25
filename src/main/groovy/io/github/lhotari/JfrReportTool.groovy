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

import java.util.regex.Pattern

@CompileStatic
class JfrReportTool {
    private static
    final Map<String, String> DEFAULT_EXTENSION = [flameGraph: 'svg', stacks: 'txt', topframes: 'top.txt']
    private static final String SAMPLING_EVENT_TYPE = "Method Profiling Sample"
    Pattern frameFilter = ~/^((?!java\.|sun\.|com\.sun\.|org\.codehaus\.groovy\.|groovy\.|org\.apache\.).)*$/
    int flameGraphWidth = 5000
    String flameGraphCommand = "flamegraph.pl"
    boolean sortFrames = false

    def flameGraph(File jfrFile, File outputFile) {
        ProcessBuilder builder = new ProcessBuilder(flameGraphCommand, "--width", flameGraphWidth.toString())
        builder.redirectOutput(outputFile)
        Process process = builder.start()
        convertToFlameGraphFormat(jfrFile, new OutputStreamWriter(process.getOutputStream()))
    }

    def stacks(File jfrFile, File outputFile) {
        outputFile.withWriter { writer ->
            convertToFlameGraphFormat(jfrFile, writer)
        }
    }

    def topframes(File jfrFile, File outputFile) {
        outputFile.withWriter { writer ->
            AtomicLongMap<String> methodCounts = AtomicLongMap.create()
            forEachFLRStackTrace(jfrFile) { FLRStackTrace flrStackTrace ->
                flrStackTrace.frames.each { frame ->
                    // getHumanReadable(boolean showReturnValue, boolean useQualifiedReturnValue, boolean showClassName, boolean useQualifiedClassName, boolean showArguments, boolean useQualifiedArguments)
                    String methodName = ((IMCFrame) frame).method.getHumanReadable(false, true, true, true, true, true)
                    if (methodName =~ frameFilter) {
                        methodCounts.incrementAndGet(methodName)
                    }
                }
            }
            writeStackCounts(methodCounts.asMap(), writer, true)
            writer.close()
        }
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

            def filtered = stackTrace.findAll { it =~ frameFilter }

            def flameGraphFormatted = filtered.join(';')

            stackCounts.incrementAndGet(flameGraphFormatted)
        }
        writeStackCounts(stackCounts.asMap(), writer, sortFrames)
        writer.close()
    }

    private void writeStackCounts(Map<String, Long> map, Writer writer, boolean sort) {
        def writeEntry = { Map.Entry<String, Long> entry ->
            writer.write entry.key
            writer.write ' '
            writer.write entry.value.toString()
            writer.write '\n'
        }
        sort ? map.collect { entry -> entry }.sort { a, b -> b.value <=> a.value }.each(writeEntry) : map.each(writeEntry)
    }

    @CompileDynamic
    public static void main(String[] args) {
        def cli = new CliBuilder()
        cli.with {
            h 'Help', longOpt: 'help'
            f 'Regexp filter for methods', longOpt: 'filter', args: 1, argName: 'filter'
            a 'Tool action', longOpt: 'action', args: 1, argName: 'action'
            o 'Output file', longOpt: 'output', args: 1, argName: 'file'
            w 'Width of flamegraph', longOpt: 'width', args: 1, argName: 'pixels'
            _ 'flamegraph.pl path', longOpt: 'flamegraph-command', args: 1, argName: 'cmd'
            s 'Sort frames', longOpt: 'sort'
        }
        cli.usage = "jfr-report-tool [-${cli.options.options.opt.findAll { it }.sort().join('')}] [jfrFile]"

        def options = cli.parse(args)
        if (options.h || !options.arguments()) {
            cli.usage()
            return
        }

        def jfrReportTool = new JfrReportTool()
        if (options.f) {
            jfrReportTool.frameFilter = Pattern.compile(options.f)
        }
        if (options.w) {
            jfrReportTool.flameGraphWidth = options.w as int
        }
        if (options.'flamegraph-command') {
            jfrReportTool.flameGraphCommand = options.'flamegraph-command'
        }
        def file = new File(options.arguments().first()).absoluteFile
        def action = options.action ?: 'flameGraph'
        def outputFile = (options.o) ? new File(String.valueOf(options.o)) : new File(file.parentFile, file.name + "." + (DEFAULT_EXTENSION[action] ?: 'svg'))
        if (options.s) jfrReportTool.sortFrames = true
        println "Converting $file"
        jfrReportTool."$action"(file, outputFile)
        println "Output in ${outputFile}"
        println "URL ${outputFile.canonicalFile.toURI().toURL()}"
    }
}
