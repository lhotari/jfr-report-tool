package io.github.lhotari

import com.google.common.util.concurrent.AtomicLongMap
import com.jrockit.mc.common.IMCFrame
import com.jrockit.mc.flightrecorder.FlightRecording
import com.jrockit.mc.flightrecorder.FlightRecordingLoader
import com.jrockit.mc.flightrecorder.internal.model.FLRStackTrace
import com.jrockit.mc.flightrecorder.spi.*
import com.jrockit.mc.flightrecorder.util.TimeRange
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.transform.stc.ClosureParams
import groovy.transform.stc.SimpleType

import java.lang.reflect.Method
import java.util.concurrent.TimeUnit
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
    int timeWindowDuration = -1
    Closure<?> outputMessage = {}
    boolean reverse = false
    int begin
    int length
    boolean firstSplit

    @ReportAction("creates flamegraph in svg format, default action")
    def flameGraph(File jfrFile, File outputFile) {
        handleRecordingByWindowByFile(jfrFile, outputFile) { IView view, File currentOutputFile ->
            File tempFile = File.createTempFile("flamegraph_input", ".txt")
            def entryCount = tempFile.withWriter { writer ->
                convertToFlameGraphFormat(view, writer)
            }
            if (entryCount) {
                ProcessBuilder builder = new ProcessBuilder(flameGraphCommand, "--width", flameGraphWidth.toString())
                def dateFormatter = { new Date(((it as long) / 1000000L).longValue()).format("yyyy-MM-dd HH:mm:ss") }
                builder.command().with {
                    add("--title='Duration ${dateFormatter(view.range.startTimestamp)} - ${dateFormatter(view.range.endTimestamp)}'".toString())
                    add(tempFile.absolutePath)
                }
                builder.redirectOutput(currentOutputFile)
                Process process = builder.start()
                process.waitFor()
            }
            tempFile.delete()
        }
    }

    @ReportAction("creates flamegraph input file")
    def stacks(File jfrFile, File outputFile) {
        handleRecordingByWindowByFile(jfrFile, outputFile) { IView view, File currentOutputFile ->
            currentOutputFile.withWriter { writer ->
                convertToFlameGraphFormat(view, writer)
            }
        }
    }

    @ReportAction("shows top methods")
    def topframes(File jfrFile, File outputFile) {
        handleRecordingByWindowByFile(jfrFile, outputFile) { IView view, File currentOutputFile ->
            AtomicLongMap<String> methodCounts = AtomicLongMap.create()
            forEachFLRStackTrace(view) { FLRStackTrace flrStackTrace ->
                List<String> stackTrace = convertStackTrace(flrStackTrace)
                if (matchesGrepFilter(stackTrace)) {
                    stackTrace.each { String methodSignature ->
                        if (matchesMethod(methodSignature)) {
                            methodCounts.incrementAndGet(methodSignature)
                        }
                    }
                }
            }
            currentOutputFile.withWriter { writer ->
                writeStackCounts(methodCounts.asMap(), writer, true)
            }
        }
    }

    @ReportAction("dump info")
    def dumpinfo(File jfrFile) {
        def recording = FlightRecordingLoader.loadFile(jfrFile)
        def view = recording.createView()
        view.setFilter(new IEventFilter() {
            boolean accept(IEvent iEvent) {
                iEvent.eventType.name in ['JVM Information', 'OS Information', 'CPU Information', 'Physical Memory']
            }
        })
        Set<String> seen = [] as Set
        for (IEvent event : view) {
            String eventTypeName = event.eventType.name
            if (!seen.contains(eventTypeName)) {
                Map<String, Object> fields = [:]
                event.eventType.getFields().each { IField field ->
                    fields[field.name] = field.getValue(event)
                }
                println event.eventType.name
                fields.each { k, v ->
                    println "${k.padRight(20)} $v"
                }
                println()
                seen.add(eventTypeName)
            }
        }
    }

    @CompileDynamic
    @ReportAction("dump record types")
    def recordtypes(File jfrFile) {
        def recording = FlightRecordingLoader.loadFile(jfrFile)
        recording.m_repository.eventTypes.each { IEventType eventType ->
            println "${eventType.name.padRight(33)}$eventType.description"
        }
    }

    void handleRecordingByWindowByFile(File jfrFile, File outputFile, Closure<?> handler) {
        handleRecordingByWindow(jfrFile) { IView view, int fileNumber ->
            File currentOutputFile
            if (fileNumber > 1) {
                currentOutputFile = createNewFileName(fileNumber, outputFile)
            } else {
                currentOutputFile = outputFile
            }
            handler(view, currentOutputFile)
            if (currentOutputFile.length() > 0) {
                outputMessage.call(currentOutputFile)
            } else {
                currentOutputFile.delete()
            }
        }
    }

    private File createNewFileName(int fileNumber, File templateFile) {
        String fileName = templateFile.getName()
        List<String> m = (List<String>) (fileName =~ ~/^(.*\.)(.*?)$/).find { it }
        if (m) {
            fileName = "${m[1]}${fileNumber}.${m[2]}"
        } else {
            fileName = "${fileName}.${fileNumber}"
        }
        new File(templateFile.getParentFile() ?: new File(''), fileName)
    }

    void handleRecordingByWindow(File jfrFile, Closure<?> handler) {
        def recording = FlightRecordingLoader.loadFile(jfrFile)
        def fullRange = recording.timeRange
        long startTime = fullRange.startTimestamp + TimeUnit.SECONDS.toNanos(begin)
        long fullRangeEnd
        if (length > 0) {
            fullRangeEnd = startTime + TimeUnit.SECONDS.toNanos(length)
        } else {
            fullRangeEnd = fullRange.endTimestamp
        }
        long windowDuration = timeWindowDuration > 0 ? TimeUnit.SECONDS.toNanos(timeWindowDuration) : (fullRangeEnd - startTime)
        int fileNumber
        while (startTime < fullRangeEnd) {
            fileNumber++
            long endTime = startTime + ((firstSplit && fileNumber == 1 ? windowDuration / 2 : windowDuration) as long)
            IView view = createView(recording)
            view.setRange(new TimeRange(startTime, endTime))
            handler(view, fileNumber)
            startTime = endTime + 1
        }
    }

    boolean matchesGrepFilter(List<String> stackTrace) {
        grepFilter == null || stackTrace.any { String methodSignature -> methodSignature && methodSignature =~ grepFilter }
    }

    private boolean matchesMethod(String methodSignature) {
        (includeFilter == null || methodSignature =~ includeFilter) && (excludeFilter == null || !(methodSignature =~ excludeFilter))
    }

    void forEachFLRStackTrace(IView view,
                              @ClosureParams(value = SimpleType, options = "com.jrockit.mc.flightrecorder.internal.model.FLRStackTrace") Closure<?> handler) {
        for (IEvent event : view) {
            FLRStackTrace flrStackTrace = (FLRStackTrace) event.getValue("(stackTrace)")
            handler(flrStackTrace)
        }
    }

    private IView createView(FlightRecording recording) {
        IView view = recording.createView()
        view.setFilter(new IEventFilter() {
            boolean accept(IEvent iEvent) {
                iEvent.eventType.name == SAMPLING_EVENT_TYPE
            }
        })
        view
    }

    int convertToFlameGraphFormat(IView view, Writer writer) {
        AtomicLongMap<String> stackCounts = AtomicLongMap.create()
        forEachFLRStackTrace(view) { FLRStackTrace flrStackTrace ->
            def stackTrace = convertStackTrace(flrStackTrace)
            if (matchesGrepFilter(stackTrace)) {
                def filtered = stackTrace.findAll { matchesMethod(it) }
                if (!reverse) {
                    filtered = filtered.reverse()
                }

                def flameGraphFormatted = filtered.collect { formatMethodName(it) }.join(';')

                stackCounts.incrementAndGet(flameGraphFormatted)
            }
        }
        writeStackCounts(stackCounts.asMap(), writer, sortFrames)
    }

    private List<String> convertStackTrace(FLRStackTrace flrStackTrace) {
        flrStackTrace.frames.collect { frame ->
            convertToMethodSignature((IMCFrame) frame)
        }?.findAll { it }
    }

    String convertToMethodSignature(IMCFrame frame) {
        // getHumanReadable(boolean showReturnValue, boolean useQualifiedReturnValue, boolean showClassName, boolean useQualifiedClassName, boolean showArguments, boolean useQualifiedArguments)
        frame.method?.getHumanReadable(false, true, true, true, true, true)
    }

    private int writeStackCounts(Map<String, Long> map, Writer writer, boolean sort) {
        def counter = 0
        def writeEntry = { Map.Entry<String, Long> entry ->
            if (entry.value > minimumSamples) {
                writer.write entry.key
                writer.write ' '
                writer.write entry.value.toString()
                writer.write '\n'
                counter++
            }
        }
        sort ? map.collect { entry -> entry }.sort { a, b -> b.value <=> a.value }.each(writeEntry) : map.each(writeEntry)
        counter
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
        cli.stopAtNonOption = false
        def reportActions = scanReportActions(JfrReportTool)
        cli.with {
            h 'Help', longOpt: 'help'
            i 'Regexp include filter for methods', longOpt: 'include', args: 1, argName: 'filter'
            e 'Regexp exclude filter for methods', longOpt: 'exclude', args: 1, argName: 'filter'
            g 'Regexp to include all stacks with match in any frame', longOpt: 'grep', args: 1, argName: 'filter'
            a "Tool action. Valid choices: ${reportActions.keySet().join(', ')}", longOpt: 'action', args: 1, argName: 'action'
            o 'Output file', longOpt: 'output', args: 1, argName: 'file'
            w 'Width of flamegraph', longOpt: 'width', args: 1, argName: 'pixels'
            _ 'flamegraph.pl path', longOpt: 'flamegraph-command', args: 1, argName: 'cmd'
            s 'Sort frames', longOpt: 'sort'
            m 'Minimum number of samples', longOpt: 'min', args: 1, argName: 'value'
            d 'Duration of time window, splits output in to multiple files', longOpt: 'duration', args: 1, argName: 'seconds'
            f 'First window duration half of given duration', longOpt: 'first-split'
            r 'Process stacks in reverse order', longOpt: 'reverse'
            b 'Begin time', longOpt: 'begin', args: 1, argName: 'seconds'
            l 'Length of selected time', longOpt: 'length', args: 1, argName: 'seconds'

        }
        cli.usage = "jfr-report-tool [-${cli.options.options.opt.findAll { it }.sort().join('')}] [jfrFile]"

        def options = cli.parse(args)
        def arguments = options.arguments().findAll { it }
        if (options.h || !arguments) {
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
        if (options.d) {
            jfrReportTool.timeWindowDuration = options.d as int
        }
        if (options.r) {
            jfrReportTool.reverse = true
        }
        if (options.b) {
            jfrReportTool.begin = options.b as int
        }
        if (options.l) {
            jfrReportTool.length = options.l as int
        }
        if (options.f) {
            jfrReportTool.firstSplit = true
        }

        Closure methodClosure = jfrReportTool.&"$action"
        def file = new File(arguments.first()).absoluteFile
        def outputFile = (options.o) ? new File(String.valueOf(options.o)).getAbsoluteFile() : new File(file.parentFile, file.name + "." + (DEFAULT_EXTENSION[action] ?: 'svg'))
        def allFiles = []
        jfrReportTool.outputMessage = { File writtenFile ->
            println "Output in ${writtenFile}"
            println "URL ${writtenFile.canonicalFile.toURI().toURL()}"
            allFiles << writtenFile
        }
        println "Converting $file"
        try {
            def methodParams = [input    : file,
                                output   : outputFile,
                                arguments: arguments,
                                options  : options]
            if (methodClosure.maximumNumberOfParameters == 2) {
                if (methodClosure.parameterTypes[1] == Map) {
                    methodClosure(file, methodParams)
                } else {
                    methodClosure(file, outputFile)
                }
            } else if (methodClosure.maximumNumberOfParameters == 1) {
                if (methodClosure.parameterTypes[0] == Map) {
                    methodClosure(methodParams)
                } else {
                    methodClosure(file)
                }
            } else {
                println "Unsupported action"
            }
            if (allFiles.size() > 1) {
                println "Index is ${jfrReportTool.createIndexFile(allFiles).toURI().toURL()}"
            }
        } catch (Throwable t) {
            t.printStackTrace()
        }
    }

    File createIndexFile(List<File> allFiles) {
        File firstFile = allFiles[0]
        File indexFile = new File(firstFile.getParentFile() ?: new File(''), firstFile.getName() + ".html")
        indexFile.withWriter { writer ->
            writer << """
<html>
<head><title>Index of generated files</title></head>
<body>
"""
            allFiles.each { file ->
                def fileurl = file.toURI().toURL()
                writer << "<a href='${fileurl}'>$file.name</a><br/>\n"
                if (file.name.endsWith(".svg")) {
                    writer << "<img src='${fileurl}' border=0 />\n"
                }
            }
            writer << "</body></html>\n"
        }
        indexFile
    }
}


